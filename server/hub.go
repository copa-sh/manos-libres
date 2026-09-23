// El hub: inventario de sesiones y canal de control.
//
// No sabe nada de motores de agente ni de narración, y desde el cambio a HTTP/3 tampoco sabe
// nada de conexiones: eso vive en transporte.go. Aquí solo queda qué sesiones hay, cómo se
// abren y cómo se cierran, más el canal de control por el que se difunden los frames de
// despertar de las sesiones que nadie está siguiendo.
package main

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"
)

// MaxChatBuffer es cuántos mensajes de chat conserva el nodo para quien se conecta después de
// que se mandaran. En memoria y en anillo, igual que el replay de una sesión: se pierde al
// reiniciar, a propósito.
const MaxChatBuffer = 200

// MaxChatTexto recorta un mensaje de chat. Es una conversación entre tus dispositivos, no un
// documento; el mismo criterio que el `avisar` de la herramienta MCP, con más margen porque
// aquí no hay TTS de por medio.
const MaxChatTexto = 4000

type Hub struct {
	cfg Config
	// El contexto de vida del nodo, no el de ninguna petición. Una sesión dura lo que dure
	// el trabajo, y el POST que la abre se responde en milisegundos: colgarla del contexto
	// de esa petición le manda un SIGINT al agente en cuanto se contesta.
	ctx context.Context

	mu       sync.Mutex
	sesiones map[string]*Sesion
	orden    []string // para listar en orden de creación y no al azar
	sigID    int

	// Suscriptores del canal de control. Reciben `session.list` y los frames de despertar.
	control map[Suscriptor]bool

	chatMu     sync.Mutex
	chatBuffer []*Chat
	chatSeq    int64
	sigChat    int
}

func NuevoHub(ctx context.Context, cfg Config) *Hub {
	h := &Hub{
		cfg:      cfg,
		ctx:      ctx,
		sesiones: map[string]*Sesion{},
		sigID:    1,
		control:  map[Suscriptor]bool{},
		sigChat:  1,
	}
	// La sesión no conoce al hub; le pasamos por dónde difundir.
	difundir = h.Difundir
	return h
}

func (h *Hub) Sesion(id string) *Sesion {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.sesiones[id]
}

func (h *Hub) Listar() []InfoSesion {
	h.mu.Lock()
	sesiones := make([]*Sesion, 0, len(h.orden))
	for _, id := range h.orden {
		if s := h.sesiones[id]; s != nil {
			sesiones = append(sesiones, s)
		}
	}
	h.mu.Unlock()

	// Fuera del mutex del hub: `Info` toma el de la sesión y anidarlos invita a un abrazo
	// mortal el día que una sesión llame al hub.
	infos := make([]InfoSesion, 0, len(sesiones))
	for _, s := range sesiones {
		infos = append(infos, s.Info())
	}
	return infos
}

// Abrir crea una sesión y arranca su adaptador.
func (h *Hub) Abrir(cwd, titulo string) (*Sesion, error) {
	if !h.cfg.RaizPermitida(cwd) {
		return nil, fmt.Errorf("%s no está bajo ALLOWED_ROOTS", cwd)
	}

	h.mu.Lock()
	id := fmt.Sprintf("s_%d", h.sigID)
	h.sigID++
	s := NuevaSesion(id, NuevoAdaptadorClaudeCode(h.cfg), cwd, titulo, h.cfg.BufferReplay)
	h.sesiones[id] = s
	h.orden = append(h.orden, id)
	h.mu.Unlock()

	if err := s.Arrancar(h.ctx, h.cfg.ModeloAgente); err != nil {
		h.mu.Lock()
		delete(h.sesiones, id)
		h.mu.Unlock()
		return nil, err
	}
	h.AnunciarLista()
	return s, nil
}

// ── Canal de control ────────────────────────────────────────────────────────────────────

func (h *Hub) SuscribirControl(sub Suscriptor) {
	h.mu.Lock()
	h.control[sub] = true
	h.mu.Unlock()
}

func (h *Hub) DesuscribirControl(sub Suscriptor) {
	h.mu.Lock()
	delete(h.control, sub)
	h.mu.Unlock()
}

// Difundir manda un frame a todos los que escuchan el canal de control.
//
// Solo pasan por aquí los frames de despertar, y es la razón de que el canal exista: una
// sesión que la app no está siguiendo tiene que poder reclamar atención. La app deduplica
// por `sessionId` y `seq`, que es lo que hace barato que un frame llegue por los dos sitios.
func (h *Hub) Difundir(f Frame) {
	h.mu.Lock()
	subs := make([]Suscriptor, 0, len(h.control))
	for sub := range h.control {
		subs = append(subs, sub)
	}
	h.mu.Unlock()

	for _, sub := range subs {
		entregar(sub, f)
	}
}

func (h *Hub) AnunciarLista() {
	h.Difundir(&ListaSesiones{cabecera: cabecera{T: "session.list"}, Sesiones: h.Listar()})
}

// ── Chat entre dispositivos ─────────────────────────────────────────────────────────────
//
// Un mensaje directo entre tus dispositivos, fuera de cualquier sesión de agente. Vive en el
// hub y no en una sesión porque no pertenece a ninguna: es del nodo. Se reparte por el mismo
// canal de control que ya lleva `session.list` y los despertares — no es un canal aparte que
// mantener — y se guarda en un buffer en anillo para que quien estaba desconectado lo reciba
// en el siguiente `hello` (ver docs/07-decisiones.md §11).

// EnviarChat manda un mensaje de un dispositivo a todos los demás. `dispositivo` es el
// `X-Dispositivo` de quien lo manda; no hay verificación de que sea quien dice ser más allá
// del token compartido — la misma confianza que ya existe para `by` en una decisión.
func (h *Hub) EnviarChat(dispositivo, texto string) (*Chat, error) {
	texto = strings.TrimSpace(texto)
	if texto == "" {
		return nil, fmt.Errorf("el mensaje está vacío")
	}
	texto = recortar(texto, MaxChatTexto)

	h.chatMu.Lock()
	id := h.sigChat
	h.sigChat++
	h.chatSeq++
	c := &Chat{
		cabecera: cabecera{T: "chat.message", S: h.chatSeq},
		ChatID:   fmt.Sprintf("c_%d", id),
		De:       dispositivo,
		Texto:    texto,
		Enviado:  time.Now().UnixMilli(),
	}
	h.chatBuffer = append(h.chatBuffer, c)
	if len(h.chatBuffer) > MaxChatBuffer {
		h.chatBuffer = h.chatBuffer[1:]
	}
	h.chatMu.Unlock()

	h.Difundir(c)
	return c, nil
}

// HistorialChat devuelve los últimos mensajes, en orden, para el `hello` de quien se conecta.
func (h *Hub) HistorialChat() []*Chat {
	h.chatMu.Lock()
	defer h.chatMu.Unlock()
	out := make([]*Chat, len(h.chatBuffer))
	copy(out, h.chatBuffer)
	return out
}

func (h *Hub) Cerrar() {
	h.mu.Lock()
	sesiones := make([]*Sesion, 0, len(h.sesiones))
	for _, s := range h.sesiones {
		sesiones = append(sesiones, s)
	}
	h.sesiones = map[string]*Sesion{}
	h.orden = nil
	h.mu.Unlock()

	for _, s := range sesiones {
		_ = s.Cerrar()
	}
}
