// Una sesión de agente.
//
// Traduce los eventos del adaptador a frames del protocolo, los numera, los guarda para el
// replay y los reparte entre los clientes suscritos. También es quien mantiene las decisiones
// pendientes: mientras una lo esté, el agente está parado.
//
// A diferencia de la versión en Node, aquí hay concurrencia de verdad: el adaptador emite
// desde la goroutine que lee el stdout del CLI y los suscriptores entran y salen desde las
// goroutines de cada petición HTTP. Todo el estado va bajo un mutex, y la entrega a cada
// suscriptor es por canal con buffer para que un móvil lento no bloquee al agente.
package main

import (
	"context"
	"errors"
	"fmt"
	"path"
	"sync"
	"time"
)

const MaxTexto = 16 * 1024

// Suscriptor es la cola de un cliente. Con buffer: si se llena, el cliente va tan atrasado
// que lo correcto es cortarlo y que reconecte con `since`, no retener al agente.
type Suscriptor chan Frame

type decisionPendiente struct {
	id        string
	peticion  PeticionDecision
	respuesta chan RespuestaDecision
	desde     time.Time
}

type Sesion struct {
	SessionID string
	Cwd       string
	Motor     string

	adaptador Adaptador

	mu              sync.Mutex
	titulo          string
	estado          Estado
	seq             int64
	replay          []Frame // buffer en anillo; en memoria y volátil a propósito
	maxReplay       int
	suscriptores    map[Suscriptor]bool
	decisiones      map[string]*decisionPendiente
	sigDecision     int
	ultimaActividad time.Time

	// Para no emitir `session.state` repetidos: el CLI manda tres `idle` seguidos.
	detalleEstado string
	// Si ya hay un frame de estado publicado; antes del primero siempre se emite.
	hayEstado bool

	cerrada bool
	// Id de sesión del motor a reanudar con `--resume`; vacío si es una sesión nueva.
	reanudar string
	// Último id del motor que se notificó a `alMotor`.
	motorNotificado string
	// Lo llama la sesión (sin mutex tomado) cuando el motor publica su id, para que el hub lo
	// persista y se pueda reanudar tras reiniciar el nodo.
	alMotor func()
}

func NuevaSesion(sessionID string, ad Adaptador, cwd, titulo string, maxReplay int) *Sesion {
	if titulo == "" {
		titulo = path.Base(cwd)
	}
	return &Sesion{
		SessionID:       sessionID,
		Cwd:             cwd,
		Motor:           ad.Motor(),
		adaptador:       ad,
		titulo:          titulo,
		estado:          EstadoIdle,
		maxReplay:       maxReplay,
		suscriptores:    map[Suscriptor]bool{},
		decisiones:      map[string]*decisionPendiente{},
		sigDecision:     1,
		ultimaActividad: time.Now(),
	}
}

func (s *Sesion) Arrancar(ctx context.Context, modelo string) error {
	return s.adaptador.Arrancar(ctx, s, OpcionesAdaptador{Cwd: s.Cwd, Modelo: modelo,
		Reanudar: s.reanudar})
}

// IdMotor es el id de sesión del propio motor (el que se pasa a `--resume`), o el que se
// pidió reanudar si el motor aún no ha publicado uno nuevo.
func (s *Sesion) IdMotor() string {
	if id := s.adaptador.SesionDelMotor(); id != "" {
		return id
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.reanudar
}

// Inactiva dice si la sesión se puede cerrar por abandono: sin actividad desde `limite`, sin
// nadie siguiéndola, sin decisiones pendientes y con el agente esperando al usuario. Una
// sesión que trabaja o que espera una respuesta nunca es «inactiva», por muy callada que esté.
func (s *Sesion) Inactiva(limite time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return !s.cerrada && len(s.suscriptores) == 0 && len(s.decisiones) == 0 &&
		s.estado == EstadoIdle && s.ultimaActividad.Before(limite)
}

func (s *Sesion) Info() InfoSesion {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.info()
}

func (s *Sesion) info() InfoSesion {
	return InfoSesion{
		SessionID:  s.SessionID,
		Titulo:     s.titulo,
		Cwd:        s.Cwd,
		Motor:      s.Motor,
		Estado:     s.estado,
		Seq:        s.seq,
		Editado:    s.ultimaActividad.UnixMilli(),
		Pendientes: len(s.decisiones),
	}
}

// ── Suscripción y replay ────────────────────────────────────────────────────────────────

// Suscribir engancha un cliente y le reenvía lo que se perdió.
//
// Devuelve `false` si `desdeSeq` es más viejo que el buffer: el transporte debe entonces
// mandar un `error` con `replay_gap`, en vez de dejar al cliente creyendo que está al día.
func (s *Sesion) Suscribir(sub Suscriptor, desdeSeq int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.suscriptores[sub] = true

	hueco := len(s.replay) > 0 && s.replay[0].Seq() > desdeSeq+1
	for _, f := range s.replay {
		if f.Seq() > desdeSeq {
			entregar(sub, f)
		}
	}
	// Las decisiones aún pendientes se reenvían siempre: reconectar no debe perder que el
	// agente está bloqueado esperando a una persona.
	for _, d := range s.decisiones {
		entregar(sub, s.frameDecision(d))
	}
	return !hueco
}

func (s *Sesion) Desuscribir(sub Suscriptor) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.suscriptores, sub)
}

// entregar nunca bloquea. Un suscriptor que no vacía su cola se queda sin ese frame y
// reconectará pidiendo replay; retener al agente por un móvil lento sería peor.
func entregar(sub Suscriptor, f Frame) {
	select {
	case sub <- f:
	default:
	}
}

// publicar exige el mutex tomado.
func (s *Sesion) publicar(f Frame) {
	s.ultimaActividad = time.Now()
	s.replay = append(s.replay, f)
	if len(s.replay) > s.maxReplay {
		s.replay = s.replay[1:]
	}
	for sub := range s.suscriptores {
		entregar(sub, f)
	}
	// Sin suscriptores no hay nada que hacer: el frame ya está en el buffer y el aviso se
	// dispara cuando la app reconecta. No hay push que mandar (docs/07-decisiones.md §3).
	if difundir != nil && EsDespertar(f) {
		difundir(f)
	}
}

// difundir manda los frames de despertar al canal de control, para que una sesión que nadie
// está siguiendo pueda reclamar atención. Lo cablea el hub al arrancar; es una variable y no
// un campo para que la sesión no tenga que conocer al hub.
var difundir func(Frame)

// ── Emisión ─────────────────────────────────────────────────────────────────────────────

// Emitir implementa Anfitrion: de evento normalizado a frame del protocolo.
func (s *Sesion) Emitir(ev Evento) {
	s.emitir(ev)

	// Fuera del mutex: el hub, al persistir, vuelve a preguntar a las sesiones.
	if s.alMotor != nil {
		if id := s.adaptador.SesionDelMotor(); id != "" {
			s.mu.Lock()
			nuevo := id != s.motorNotificado
			s.motorNotificado = id
			s.mu.Unlock()
			if nuevo {
				s.alMotor()
			}
		}
	}
}

func (s *Sesion) emitir(ev Evento) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.cerrada {
		return
	}
	// Un estado igual al anterior no es información: gastaría un `seq` y un frame.
	if ev.Clase == EvEstado && s.hayEstado && ev.Estado == s.estado && ev.Detalle == s.detalleEstado {
		return
	}

	s.seq++
	seq := s.seq
	cab := func(t string) cabecera { return cabecera{T: t, S: seq} }

	switch ev.Clase {
	case EvEstado:
		s.estado = ev.Estado
		s.detalleEstado = ev.Detalle
		s.hayEstado = true
		s.publicar(&EstadoSesion{cabecera: cab("session.state"), SessionID: s.SessionID,
			Estado: ev.Estado, Detalle: ev.Detalle})

	case EvDelta:
		s.publicar(&Delta{cabecera: cab("text.delta"), SessionID: s.SessionID,
			MessageID: ev.MessageID, Texto: ev.Texto})

	case EvMensaje:
		texto := recortar(ev.Texto, MaxTexto)
		s.publicar(&Mensaje{cabecera: cab("message"), SessionID: s.SessionID,
			MessageID: ev.MessageID, Rol: ev.Rol, Clase: ev.Tipo,
			Texto: texto, Narrable: ANarrable(texto)})

	case EvHerramienta:
		s.publicar(&Herramienta{cabecera: cab("tool"), SessionID: s.SessionID,
			ToolUseID: ev.ToolUseID, Fase: ev.Fase,
			Etiqueta: EtiquetaHerramienta(ev.Nombre, ev.Input), Ok: ev.Ok})

	case EvAviso:
		hablado := ""
		if ev.Mensaje != "" {
			hablado = unirFrases(ANarrable(ev.Mensaje).Frases, 0)
		}
		s.publicar(&Aviso{cabecera: cab("alert"), SessionID: s.SessionID,
			Patron: ev.Patron, Origen: ev.Origen, Mensaje: ev.Mensaje,
			Hablado: hablado, Sonido: ev.Sonido})

	case EvHecho:
		s.publicar(&TareaHecha{cabecera: cab("task.done"), SessionID: s.SessionID,
			Resumen: ev.Resumen, Hablado: unirFrases(ANarrable(ev.Resumen).Frases, 2),
			CosteUsd: ev.CosteUsd, DuracionMs: ev.DuracionMs})

	case EvError:
		s.publicar(&ErrorFrame{cabecera: cab("error"), SessionID: s.SessionID,
			Codigo: ErrMotor, Mensaje: ev.Mensaje})

	default:
		// Clase desconocida: se descarta el `seq` gastado y no se publica nada.
		s.seq--
	}
}

// unirFrases junta las primeras `n` frases (0 = todas). El resumen hablado de una tarea son
// dos frases: más que eso y deja de ser un aviso para volverse una lectura.
func unirFrases(frases []string, n int) string {
	if n > 0 && len(frases) > n {
		frases = frases[:n]
	}
	out := ""
	for i, f := range frases {
		if i > 0 {
			out += " "
		}
		out += f
	}
	return out
}

// ── Decisiones ──────────────────────────────────────────────────────────────────────────

// Decidir implementa Anfitrion. Bloquea hasta que llega una respuesta desde el teléfono o
// hasta que el contexto muere. No hay plazo que lo resuelva: un agente parado es preferible
// a un agente que hizo algo que nadie aprobó.
func (s *Sesion) Decidir(ctx context.Context, p PeticionDecision) (RespuestaDecision, error) {
	s.mu.Lock()
	id := fmt.Sprintf("d_%d", s.sigDecision)
	s.sigDecision++
	d := &decisionPendiente{
		id:        id,
		peticion:  p,
		respuesta: make(chan RespuestaDecision, 1),
		desde:     time.Now(),
	}
	s.decisiones[id] = d
	s.estado = EstadoEsperando
	s.seq++
	s.publicar(s.frameDecision(d))
	s.mu.Unlock()

	// TODO(H3): reinsistir con el aviso a los 30 s y a los 5 min si sigue pendiente.
	select {
	case r := <-d.respuesta:
		return r, nil
	case <-ctx.Done():
		s.mu.Lock()
		delete(s.decisiones, id)
		s.mu.Unlock()
		return RespuestaDecision{}, ctx.Err()
	}
}

// Responder resuelve una decisión pendiente. Devuelve false si ya no existe, que es lo que
// pasa cuando otro dispositivo contestó antes.
func (s *Sesion) Responder(decisionID string, opciones []string, siempre bool, por string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	d, ok := s.decisiones[decisionID]
	if !ok {
		return false
	}
	delete(s.decisiones, decisionID)
	d.respuesta <- RespuestaDecision{OpcionIDs: opciones, Siempre: siempre}

	s.seq++
	s.publicar(&DecisionResuelta{cabecera: cabecera{T: "decision.resolved", S: s.seq},
		SessionID: s.SessionID, DecisionID: decisionID, Resolucion: "answered", Por: por})
	return true
}

// frameDecision exige el mutex tomado.
func (s *Sesion) frameDecision(d *decisionPendiente) Frame {
	return &PideDecision{
		cabecera:   cabecera{T: "decision.request", S: s.seq},
		SessionID:  s.SessionID,
		DecisionID: d.id,
		Origen:     d.peticion.Origen,
		Multiple:   d.peticion.Multiple,
		Pregunta:   d.peticion.Pregunta,
		Hablada:    Enunciar(d.peticion.Pregunta, d.peticion.Opciones),
		Opciones:   d.peticion.Opciones,
	}
}

// ── Ciclo de vida ───────────────────────────────────────────────────────────────────────

func (s *Sesion) Prompt(texto string) error {
	s.mu.Lock()
	cerrada := s.cerrada
	s.mu.Unlock()
	if cerrada {
		return errSesionCerrada
	}
	s.Emitir(Evento{Clase: EvEstado, Estado: EstadoPensando})
	return s.adaptador.Enviar(texto)
}

var errSesionCerrada = errors.New("la sesión está cerrada")

// Interrumpir termina el turno en curso, **no** la sesión: no se cierra, no se baja del hub
// y conserva su buffer de replay. Las decisiones pendientes eran de ese turno, así que se
// deniegan (interrumpir no puede significar aprobar) y se anuncian como `cancelled`.
//
// Lo que el CLI real hace con SIGINT —terminar el turno y seguir vivo— está sin verificar
// (docs/08-tareas.md); la semántica de la sesión, en cambio, se cumple con cualquier motor.
func (s *Sesion) Interrumpir() error {
	s.mu.Lock()
	if s.cerrada {
		s.mu.Unlock()
		return errSesionCerrada
	}
	s.mu.Unlock()

	if err := s.adaptador.Interrumpir(); err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	for id, d := range s.decisiones {
		delete(s.decisiones, id)
		d.respuesta <- RespuestaDecision{OpcionIDs: []string{"deny"}}
		s.seq++
		s.publicar(&DecisionResuelta{cabecera: cabecera{T: "decision.resolved", S: s.seq},
			SessionID: s.SessionID, DecisionID: id, Resolucion: "cancelled"})
	}
	return nil
}

// Cerrar cierra la sesión por petición del usuario.
func (s *Sesion) Cerrar() error { return s.CerrarPor("user") }

// CerrarPor cierra la sesión y avisa a quien la siga con `session.closed`. Es idempotente.
// `motivo` es `user`, `idle` (limpieza por abandono) o `shutdown`.
func (s *Sesion) CerrarPor(motivo string) error {
	s.mu.Lock()
	if s.cerrada {
		s.mu.Unlock()
		return nil
	}
	s.cerrada = true
	for id, d := range s.decisiones {
		// Cerrar con decisiones pendientes las deniega: cerrar no puede significar aprobar.
		d.respuesta <- RespuestaDecision{OpcionIDs: []string{"deny"}}
		delete(s.decisiones, id)
	}
	s.seq++
	s.publicar(&SesionCerrada{cabecera: cabecera{T: "session.closed", S: s.seq},
		SessionID: s.SessionID, Motivo: motivo})
	s.mu.Unlock()
	return s.adaptador.Parar()
}
