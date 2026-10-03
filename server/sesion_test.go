package main

import (
	"context"
	"sync"
	"testing"
	"time"
)

// adaptadorFalso deja arrancar una sesión sin un CLI detrás y registra lo que se le pide.
type adaptadorFalso struct {
	mu             sync.Mutex
	enviados       []string
	interrupciones int
	paradas        int
	opciones       OpcionesAdaptador
	idMotor        string
	errInterrumpir error
}

func (a *adaptadorFalso) Motor() string { return "falso" }
func (a *adaptadorFalso) SesionDelMotor() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.idMotor
}
func (a *adaptadorFalso) ponerMotor(id string) { a.mu.Lock(); a.idMotor = id; a.mu.Unlock() }
func (a *adaptadorFalso) Arrancar(_ context.Context, _ Anfitrion, o OpcionesAdaptador) error {
	a.mu.Lock()
	a.opciones = o
	a.mu.Unlock()
	return nil
}
func (a *adaptadorFalso) Enviar(t string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.enviados = append(a.enviados, t)
	return nil
}
func (a *adaptadorFalso) Interrumpir() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.interrupciones++
	return a.errInterrumpir
}
func (a *adaptadorFalso) Parar() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.paradas++
	return nil
}
func (a *adaptadorFalso) cuenta() (enviados, interrupciones, paradas int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.enviados), a.interrupciones, a.paradas
}

func nuevaSesionDePrueba(maxReplay int) *Sesion {
	difundir = nil // el hub no participa en estas pruebas
	return NuevaSesion("s_1", &adaptadorFalso{}, "/tmp/proyecto", "", maxReplay)
}

func recibir(t *testing.T, sub Suscriptor) Frame {
	t.Helper()
	select {
	case f := <-sub:
		return f
	case <-time.After(time.Second):
		t.Fatal("no llegó ningún frame")
		return nil
	}
}

func TestSeqEsMonotonoYElTituloSaleDelCwd(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	if s.Info().Titulo != "proyecto" {
		t.Errorf("Titulo = %q, quería el último tramo del cwd", s.Info().Titulo)
	}

	sub := make(Suscriptor, 8)
	s.Suscribir(sub, 0)

	for i := 0; i < 3; i++ {
		s.Emitir(Evento{Clase: EvMensaje, MessageID: "m", Rol: "assistant", Tipo: "text",
			Texto: "hola"})
	}
	for i := int64(1); i <= 3; i++ {
		if seq := recibir(t, sub).Seq(); seq != i {
			t.Errorf("seq = %d, quería %d", seq, i)
		}
	}
}

func TestReanudarSoloReenviaLoQueFalta(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	for i := 0; i < 4; i++ {
		s.Emitir(Evento{Clase: EvDelta, MessageID: "m", Texto: "x"})
	}

	sub := make(Suscriptor, 8)
	if completo := s.Suscribir(sub, 2); !completo {
		t.Error("no debería haber hueco: el buffer llega hasta el seq 1")
	}

	// Se pidió desde el 2, así que llegan el 3 y el 4 y nada más.
	if seq := recibir(t, sub).Seq(); seq != 3 {
		t.Errorf("primer frame reenviado seq = %d, quería 3", seq)
	}
	if seq := recibir(t, sub).Seq(); seq != 4 {
		t.Errorf("segundo frame reenviado seq = %d, quería 4", seq)
	}
	select {
	case f := <-sub:
		t.Errorf("llegó un frame de más: %s seq=%d", f.Tipo(), f.Seq())
	default:
	}
}

func TestReanudarAvisaDelHueco(t *testing.T) {
	// Buffer de 2 y 5 frames emitidos: los tres primeros ya no están.
	s := nuevaSesionDePrueba(2)
	for i := 0; i < 5; i++ {
		s.Emitir(Evento{Clase: EvDelta, MessageID: "m", Texto: "x"})
	}

	sub := make(Suscriptor, 8)
	if completo := s.Suscribir(sub, 1); completo {
		t.Error("debería avisar de que faltan frames en vez de dejar al cliente creyéndose al día")
	}
}

func TestUnaDecisionBloqueaAlAgenteHastaQueAlguienContesta(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	sub := make(Suscriptor, 8)
	s.Suscribir(sub, 0)

	respuestas := make(chan RespuestaDecision, 1)
	go func() {
		r, err := s.Decidir(context.Background(), PeticionDecision{
			Origen:   OrigenPermiso,
			Pregunta: "¿Borro?",
			Opciones: []Opcion{{ID: "allow"}, {ID: "deny"}},
		})
		if err != nil {
			t.Errorf("Decidir: %v", err)
		}
		respuestas <- r
	}()

	f := recibir(t, sub)
	pide, ok := f.(*PideDecision)
	if !ok {
		t.Fatalf("primer frame = %s, quería decision.request", f.Tipo())
	}
	if s.Info().Pendientes != 1 {
		t.Errorf("Pendientes = %d, quería 1", s.Info().Pendientes)
	}

	// Sin respuesta, el agente sigue parado.
	select {
	case <-respuestas:
		t.Fatal("Decidir volvió sin que nadie contestara")
	case <-time.After(50 * time.Millisecond):
	}

	if !s.Responder(pide.DecisionID, []string{"allow"}, false, "movil") {
		t.Fatal("Responder no encontró la decisión")
	}

	select {
	case r := <-respuestas:
		if len(r.OpcionIDs) != 1 || r.OpcionIDs[0] != "allow" {
			t.Errorf("respuesta = %v", r.OpcionIDs)
		}
	case <-time.After(time.Second):
		t.Fatal("Decidir no volvió tras contestar")
	}

	if s.Info().Pendientes != 0 {
		t.Errorf("Pendientes = %d tras contestar, quería 0", s.Info().Pendientes)
	}
}

func TestSegundaRespuestaALaMismaDecisionNoCuela(t *testing.T) {
	// Dos dispositivos contestan a la vez: el segundo tiene que enterarse de que ya está
	// resuelta, no reabrirla ni bloquear.
	s := nuevaSesionDePrueba(10)
	go s.Decidir(context.Background(), PeticionDecision{Pregunta: "¿?",
		Opciones: []Opcion{{ID: "allow"}}})

	sub := make(Suscriptor, 8)
	s.Suscribir(sub, 0)
	pide := recibir(t, sub).(*PideDecision)

	if !s.Responder(pide.DecisionID, []string{"allow"}, false, "movil") {
		t.Fatal("la primera respuesta debería valer")
	}
	if s.Responder(pide.DecisionID, []string{"deny"}, false, "reloj") {
		t.Error("la segunda respuesta no debería valer")
	}
}

func TestCerrarConDecisionesPendientesLasDeniega(t *testing.T) {
	// Cerrar no puede significar aprobar.
	s := nuevaSesionDePrueba(10)

	respuestas := make(chan RespuestaDecision, 1)
	go func() {
		r, _ := s.Decidir(context.Background(), PeticionDecision{Pregunta: "¿borro /?",
			Opciones: []Opcion{{ID: "allow"}, {ID: "deny"}}})
		respuestas <- r
	}()

	// Espera a que la decisión esté registrada antes de cerrar.
	for i := 0; i < 100 && s.Info().Pendientes == 0; i++ {
		time.Sleep(time.Millisecond)
	}

	if err := s.Cerrar(); err != nil {
		t.Fatalf("Cerrar: %v", err)
	}
	select {
	case r := <-respuestas:
		if len(r.OpcionIDs) != 1 || r.OpcionIDs[0] != "deny" {
			t.Errorf("al cerrar la respuesta fue %v, quería deny", r.OpcionIDs)
		}
	case <-time.After(time.Second):
		t.Fatal("cerrar dejó la decisión colgada")
	}
}

func TestUnSuscriptorLentoNoBloqueaAlAgente(t *testing.T) {
	// Cola de 1 y tres frames: el móvil lento pierde frames y reconectará pidiendo replay,
	// pero el agente no se queda esperándole.
	s := nuevaSesionDePrueba(10)
	lento := make(Suscriptor, 1)
	s.Suscribir(lento, 0)

	hecho := make(chan bool, 1)
	go func() {
		for i := 0; i < 3; i++ {
			s.Emitir(Evento{Clase: EvDelta, MessageID: "m", Texto: "x"})
		}
		hecho <- true
	}()

	select {
	case <-hecho:
	case <-time.After(time.Second):
		t.Fatal("el agente se bloqueó por un suscriptor que no vacía su cola")
	}
}

func TestElTextoSeRecortaAntesDeNarrarlo(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	sub := make(Suscriptor, 4)
	s.Suscribir(sub, 0)

	largo := ""
	for len(largo) < MaxTexto*2 {
		largo += "palabra "
	}
	s.Emitir(Evento{Clase: EvMensaje, MessageID: "m", Rol: "assistant", Tipo: "text", Texto: largo})

	msg := recibir(t, sub).(*Mensaje)
	if len([]rune(msg.Texto)) > MaxTexto {
		t.Errorf("el texto no se recortó: %d runas", len([]rune(msg.Texto)))
	}
}

func estados(frames []Frame) (n int) {
	for _, f := range frames {
		if f.Tipo() == "session.state" {
			n++
		}
	}
	return n
}

func TestSessionStateNoSeRepite(t *testing.T) {
	// En una traza real llegan tres `idle` seguidos: solo el primero es información.
	s := nuevaSesionDePrueba(20)
	sub := make(Suscriptor, 20)
	s.Suscribir(sub, 0)

	for i := 0; i < 3; i++ {
		s.Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle})
	}
	s.Emitir(Evento{Clase: EvEstado, Estado: EstadoTrabajando})
	s.Emitir(Evento{Clase: EvEstado, Estado: EstadoTrabajando})
	s.Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle})

	var frames []Frame
	for len(sub) > 0 {
		frames = append(frames, <-sub)
	}
	if len(frames) != 3 {
		t.Fatalf("llegaron %d frames, quería 3 (idle, working, idle)", len(frames))
	}
	for i, f := range frames {
		if f.Seq() != int64(i+1) {
			t.Errorf("frame %d tiene seq %d: el dedupe no debe gastar seq", i, f.Seq())
		}
	}
	// Un estado igual pero con otro detalle sí es información.
	s.Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle, Detalle: "otro"})
	if estados(s.replay) != 4 {
		t.Errorf("un detalle distinto debería emitirse")
	}
}

func TestInterrumpirNoCierraLaSesionYCancelaLasDecisiones(t *testing.T) {
	ad := &adaptadorFalso{}
	difundir = nil
	s := NuevaSesion("s_1", ad, "/tmp/p", "", 20)

	respuestas := make(chan RespuestaDecision, 1)
	go func() {
		r, _ := s.Decidir(context.Background(), PeticionDecision{Pregunta: "¿?",
			Opciones: []Opcion{{ID: "allow"}, {ID: "deny"}}})
		respuestas <- r
	}()
	for i := 0; i < 100 && s.Info().Pendientes == 0; i++ {
		time.Sleep(time.Millisecond)
	}

	if err := s.Interrumpir(); err != nil {
		t.Fatalf("Interrumpir: %v", err)
	}
	_, interrupciones, paradas := ad.cuenta()
	if interrupciones != 1 || paradas != 0 {
		t.Errorf("interrupciones=%d paradas=%d: interrumpir no debe parar el motor", interrupciones, paradas)
	}
	select {
	case r := <-respuestas:
		if !contiene(r.OpcionIDs, "deny") {
			t.Errorf("la decisión pendiente se resolvió con %v, quería deny", r.OpcionIDs)
		}
	case <-time.After(time.Second):
		t.Fatal("la decisión pendiente quedó colgada")
	}

	var visto bool
	for _, f := range s.replay {
		if d, ok := f.(*DecisionResuelta); ok && d.Resolucion == "cancelled" {
			visto = true
		}
	}
	if !visto {
		t.Error("falta decision.resolved con resolution=cancelled")
	}

	// La sesión sigue viva: acepta prompts y emite.
	if err := s.Prompt("sigue"); err != nil {
		t.Errorf("Prompt tras interrumpir: %v", err)
	}
	if enviados, _, _ := ad.cuenta(); enviados != 1 {
		t.Errorf("el prompt posterior no llegó al motor")
	}
}

func TestCerrarEsIdempotenteYPublicaSessionClosed(t *testing.T) {
	ad := &adaptadorFalso{}
	difundir = nil
	s := NuevaSesion("s_1", ad, "/tmp/p", "", 20)
	sub := make(Suscriptor, 8)
	s.Suscribir(sub, 0)

	if err := s.CerrarPor("idle"); err != nil {
		t.Fatal(err)
	}
	_ = s.Cerrar()
	c, ok := recibir(t, sub).(*SesionCerrada)
	if !ok || c.Motivo != "idle" {
		t.Fatalf("frame de cierre = %+v", c)
	}
	if _, _, paradas := ad.cuenta(); paradas != 1 {
		t.Errorf("Parar se llamó %d veces, quería 1", paradas)
	}
	if err := s.Prompt("hola"); err == nil {
		t.Error("una sesión cerrada no debería aceptar prompts")
	}
	s.Emitir(Evento{Clase: EvDelta, Texto: "tarde"}) // no debe entrar en el replay
	if len(s.replay) != 1 {
		t.Errorf("replay tiene %d frames tras cerrar, quería 1", len(s.replay))
	}
}

func TestInactiva(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	futuro := time.Now().Add(time.Hour)
	if !s.Inactiva(futuro) {
		t.Error("una sesión idle, sin suscriptores y vieja debería estar inactiva")
	}
	if s.Inactiva(time.Now().Add(-time.Hour)) {
		t.Error("una sesión con actividad reciente no está inactiva")
	}
	sub := make(Suscriptor, 1)
	s.Suscribir(sub, 0)
	if s.Inactiva(futuro) {
		t.Error("una sesión que alguien sigue no está inactiva")
	}
	s.Desuscribir(sub)
	s.Emitir(Evento{Clase: EvEstado, Estado: EstadoTrabajando})
	if s.Inactiva(time.Now().Add(time.Hour)) {
		t.Error("una sesión trabajando no está inactiva")
	}
}
