// El transporte: HTTP/3 por fuera, HTTP normal por dentro.
//
// El nodo no habla QUIC. Caddy termina HTTP/3 delante y hace de proxy en claro contra este
// servidor dentro de la red de compose, exactamente igual que en echo-server. Eso decide la
// forma del protocolo, y es la razón de que ya no haya WebSocket:
//
//   - **Descendente**: un GET largo con el cuerpo en streaming, en formato SSE. El campo
//     `id:` de cada evento *es* el `seq` del frame, así que la reanudación tras reconectar
//     es la cabecera `Last-Event-ID` que el propio cliente reenvía — la máquina de estados
//     de `session.attach {sinceSeq}` sale gratis.
//   - **Ascendente**: peticiones sueltas. Un prompt o una respuesta a una decisión son
//     pequeños y raros; no necesitan un canal abierto.
//
// Hay dos flujos descendentes distintos, y no es por gusto: `seq` es monótono **por sesión**,
// y `Last-Event-ID` es uno solo por flujo. Un flujo por sesión mantiene esa correspondencia,
// y sobre HTTP/3 son streams de la misma conexión QUIC, que es justo para lo que sirve.
// El canal de control lleva aparte la lista de sesiones y los despertares de las que no se
// están siguiendo.
//
// Caddy hace flush solo con `text/event-stream`; sin eso retendría los frames en el buffer y
// el «canal siempre abierto» no entregaría nada hasta cerrarse. Ver el Caddyfile.
package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type Servidor struct {
	cfg Config
	hub *Hub
}

func NuevoServidor(cfg Config, hub *Hub) *Servidor {
	return &Servidor{cfg: cfg, hub: hub}
}

func (s *Servidor) Rutas() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /salud", func(w http.ResponseWriter, r *http.Request) {
		escribirJSON(w, http.StatusOK, map[string]any{"ok": true, "protocolo": VersionProtocolo})
	})

	mux.HandleFunc("GET /v1/control", s.conAuth(s.control))
	mux.HandleFunc("POST /v1/chat", s.conAuth(s.chat))
	mux.HandleFunc("GET /v1/sesiones", s.conAuth(s.listar))
	mux.HandleFunc("POST /v1/sesiones", s.conAuth(s.abrir))
	mux.HandleFunc("GET /v1/sesiones/{id}/flujo", s.conAuth(s.flujo))
	mux.HandleFunc("POST /v1/sesiones/{id}/prompt", s.conAuth(s.prompt))
	mux.HandleFunc("POST /v1/sesiones/{id}/decision", s.conAuth(s.decision))
	mux.HandleFunc("POST /v1/sesiones/{id}/interrupcion", s.conAuth(s.interrumpir))
	mux.HandleFunc("POST /v1/sesiones/{id}/narracion", s.conAuth(s.narracion))

	return cabecerasSeguridad(mux)
}

// ── Autenticación ───────────────────────────────────────────────────────────────────────

// conAuth exige `Authorization: Bearer` y la versión del protocolo.
//
// El token va en cabecera y no en la URL, al revés que en echo-server: allí está forzado
// porque Echo no deja configurar cabeceras, y el precio es que el secreto acaba en los logs
// de acceso de Caddy. La app de manos-libres es nuestra, así que ese precio no se paga.
func (s *Servidor) conAuth(siguiente func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dado := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if subtle.ConstantTimeCompare([]byte(dado), []byte(s.cfg.Token)) != 1 {
			fallo(w, http.StatusUnauthorized, ErrAuth, "token inválido")
			return
		}
		if p := r.Header.Get("X-Protocolo"); p != "" && p != VersionProtocolo {
			fallo(w, http.StatusBadRequest, ErrProtocolo, "este nodo habla "+VersionProtocolo)
			return
		}
		dispositivo := r.Header.Get("X-Dispositivo")
		if dispositivo == "" {
			dispositivo = "desconocido"
		}
		siguiente(w, r, dispositivo)
	}
}

// ── Canal de control ────────────────────────────────────────────────────────────────────

func (s *Servidor) control(w http.ResponseWriter, r *http.Request, _ string) {
	flujo, err := abrirSSE(w)
	if err != nil {
		fallo(w, http.StatusInternalServerError, ErrPeticion, err.Error())
		return
	}

	sub := make(Suscriptor, 64)
	s.hub.SuscribirControl(sub)
	defer s.hub.DesuscribirControl(sub)

	flujo.frame(&Hello{
		cabecera:  cabecera{T: "hello"},
		Protocolo: VersionProtocolo,
		Nodo:      "manos-libres/nodo 0.0.0",
		Motores:   []string{"claude-code"},
		Sesiones:  s.hub.Listar(),
		Chat:      s.hub.HistorialChat(),
	})

	s.bombear(r.Context(), flujo, sub)
}

// ── Chat entre dispositivos ─────────────────────────────────────────────────────────────

func (s *Servidor) chat(w http.ResponseWriter, r *http.Request, dispositivo string) {
	var cuerpo struct {
		Texto string `json:"text"`
	}
	if !leerJSON(w, r, &cuerpo) {
		return
	}
	c, err := s.hub.EnviarChat(dispositivo, cuerpo.Texto)
	if err != nil {
		fallo(w, http.StatusBadRequest, ErrPeticion, err.Error())
		return
	}
	escribirJSON(w, http.StatusCreated, c)
}

// ── Flujo de una sesión ─────────────────────────────────────────────────────────────────

func (s *Servidor) flujo(w http.ResponseWriter, r *http.Request, _ string) {
	sesion := s.hub.Sesion(r.PathValue("id"))
	if sesion == nil {
		fallo(w, http.StatusNotFound, ErrSinSesion, r.PathValue("id"))
		return
	}

	// `Last-Event-ID` lo reenvía el cliente solo al reconectar; `desde` es para el primer
	// enganche, cuando la app ya sabe por dónde iba de una ejecución anterior.
	desde := entero64(r.Header.Get("Last-Event-ID"), entero64(r.URL.Query().Get("desde"), 0))

	flujo, err := abrirSSE(w)
	if err != nil {
		fallo(w, http.StatusInternalServerError, ErrPeticion, err.Error())
		return
	}

	sub := make(Suscriptor, 256)
	completo := sesion.Suscribir(sub, desde)
	defer sesion.Desuscribir(sub)

	if !completo {
		// Un hueco no es motivo para cortar: el cliente sigue recibiendo lo que viene, pero
		// tiene que saber que se perdió algo en vez de creerse al día.
		flujo.frame(&ErrorFrame{cabecera: cabecera{T: "error"}, SessionID: sesion.SessionID,
			Codigo: ErrHueco, Mensaje: "faltan frames: el buffer no llega tan atrás"})
	}

	s.bombear(r.Context(), flujo, sub)
}

// bombear vacía la cola del suscriptor por el flujo hasta que el cliente se va.
//
// El keepalive es un comentario SSE: no llega a la app como frame, pero mantiene viva la
// conexión frente al NAT de la operadora y frente a los plazos del proxy.
func (s *Servidor) bombear(ctx context.Context, flujo *sse, sub Suscriptor) {
	tic := time.NewTicker(time.Duration(s.cfg.KeepaliveSegundos) * time.Second)
	defer tic.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case f := <-sub:
			if err := flujo.frame(f); err != nil {
				return
			}
		case <-tic.C:
			if err := flujo.comentario("ping"); err != nil {
				return
			}
		}
	}
}

// ── Peticiones del cliente ──────────────────────────────────────────────────────────────

func (s *Servidor) listar(w http.ResponseWriter, _ *http.Request, _ string) {
	escribirJSON(w, http.StatusOK, map[string]any{"sessions": s.hub.Listar()})
}

func (s *Servidor) abrir(w http.ResponseWriter, r *http.Request, _ string) {
	var cuerpo struct {
		Cwd    string `json:"cwd"`
		Titulo string `json:"title"`
	}
	if !leerJSON(w, r, &cuerpo) {
		return
	}
	sesion, err := s.hub.Abrir(cuerpo.Cwd, cuerpo.Titulo)
	if err != nil {
		fallo(w, http.StatusForbidden, ErrRuta, err.Error())
		return
	}
	escribirJSON(w, http.StatusCreated, sesion.Info())
}

func (s *Servidor) prompt(w http.ResponseWriter, r *http.Request, _ string) {
	sesion := s.sesionDe(w, r)
	if sesion == nil {
		return
	}
	var cuerpo struct {
		Texto string `json:"text"`
	}
	if !leerJSON(w, r, &cuerpo) {
		return
	}
	if strings.TrimSpace(cuerpo.Texto) == "" {
		fallo(w, http.StatusBadRequest, ErrPeticion, "el prompt está vacío")
		return
	}
	if err := sesion.Prompt(cuerpo.Texto); err != nil {
		fallo(w, http.StatusInternalServerError, ErrMotor, err.Error())
		return
	}
	w.WriteHeader(http.StatusAccepted)
}

func (s *Servidor) decision(w http.ResponseWriter, r *http.Request, dispositivo string) {
	sesion := s.sesionDe(w, r)
	if sesion == nil {
		return
	}
	var cuerpo struct {
		DecisionID string   `json:"decisionId"`
		OpcionIDs  []string `json:"optionIds"`
		Siempre    bool     `json:"always"`
	}
	if !leerJSON(w, r, &cuerpo) {
		return
	}
	if !sesion.Responder(cuerpo.DecisionID, cuerpo.OpcionIDs, cuerpo.Siempre, dispositivo) {
		// Ya la contestó otro dispositivo. No es un error del cliente: se le dice que está
		// resuelta y su pantalla de decisión se cierra sola.
		escribirJSON(w, http.StatusOK, map[string]any{
			"t": "decision.resolved", "decisionId": cuerpo.DecisionID, "resolution": "answered",
		})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Servidor) interrumpir(w http.ResponseWriter, r *http.Request, _ string) {
	sesion := s.sesionDe(w, r)
	if sesion == nil {
		return
	}
	if err := sesion.Interrumpir(); err != nil {
		fallo(w, http.StatusInternalServerError, ErrMotor, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Servidor) narracion(w http.ResponseWriter, r *http.Request, _ string) {
	if s.sesionDe(w, r) == nil {
		return
	}
	// TODO(H4): guardar la posición por dispositivo, para que al reconectar la app pueda
	// reanudar la narración donde se quedó.
	w.WriteHeader(http.StatusNoContent)
}

func (s *Servidor) sesionDe(w http.ResponseWriter, r *http.Request) *Sesion {
	id := r.PathValue("id")
	sesion := s.hub.Sesion(id)
	if sesion == nil {
		fallo(w, http.StatusNotFound, ErrSinSesion, id)
	}
	return sesion
}

// ── SSE ─────────────────────────────────────────────────────────────────────────────────

type sse struct {
	w http.ResponseWriter
	f http.Flusher
}

func abrirSSE(w http.ResponseWriter) (*sse, error) {
	f, ok := w.(http.Flusher)
	if !ok {
		return nil, fmt.Errorf("este ResponseWriter no deja hacer flush")
	}
	h := w.Header()
	h.Set("Content-Type", "text/event-stream; charset=utf-8")
	h.Set("Cache-Control", "no-cache")
	// Para nginx, que sí retiene por defecto. Caddy no lo necesita pero tampoco molesta.
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	f.Flush()
	return &sse{w: w, f: f}, nil
}

// frame manda un frame como evento SSE. El `id:` es el `seq`, que es lo que el cliente
// devuelve en `Last-Event-ID` al reconectar. Los frames sin `seq` —el `hello`, la lista de
// sesiones— van sin id a propósito: no son reanudables.
func (s *sse) frame(f Frame) error {
	datos, err := json.Marshal(f)
	if err != nil {
		return err
	}
	if seq := f.Seq(); seq > 0 {
		if _, err := fmt.Fprintf(s.w, "id: %d\n", seq); err != nil {
			return err
		}
	}
	// Un frame nunca lleva saltos de línea internos: `json.Marshal` los escapa, así que una
	// sola línea `data:` es suficiente y el receptor no tiene que reensamblar.
	if _, err := fmt.Fprintf(s.w, "data: %s\n\n", datos); err != nil {
		return err
	}
	s.f.Flush()
	return nil
}

func (s *sse) comentario(texto string) error {
	if _, err := fmt.Fprintf(s.w, ": %s\n\n", texto); err != nil {
		return err
	}
	s.f.Flush()
	return nil
}

// ── Utilidades ──────────────────────────────────────────────────────────────────────────

func cabecerasSeguridad(siguiente http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		siguiente.ServeHTTP(w, r)
	})
}

func escribirJSON(w http.ResponseWriter, estado int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(estado)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("no se pudo escribir la respuesta: %v", err)
	}
}

func fallo(w http.ResponseWriter, estado int, codigo CodigoError, mensaje string) {
	escribirJSON(w, estado, ErrorFrame{
		cabecera: cabecera{T: "error"}, Codigo: codigo, Mensaje: mensaje,
	})
}

// leerJSON limita el cuerpo: estos endpoints reciben un prompt, no un fichero.
func leerJSON(w http.ResponseWriter, r *http.Request, destino any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024))
	dec.DisallowUnknownFields()
	if err := dec.Decode(destino); err != nil {
		fallo(w, http.StatusBadRequest, ErrPeticion, "cuerpo no válido: "+err.Error())
		return false
	}
	return true
}

func entero64(s string, pordefecto int64) int64 {
	if n, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64); err == nil && n >= 0 {
		return n
	}
	return pordefecto
}
