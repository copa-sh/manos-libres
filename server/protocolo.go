// Protocolo `manos-libres/1`.
//
// Fuente de verdad de la conversación entre la app y el nodo. La app replica estos tipos en
// Kotlin (`app/.../net/Protocol.kt`); cualquier cambio aquí se refleja allí.
//
// Reglas que el sistema de tipos no expresa y que están en docs/03-protocolo.md:
//   - Todo frame nodo→app perteneciente a una sesión lleva `seq`, monótono por sesión.
//   - El receptor ignora los `t` que no conoce: añadir frames no rompe compatibilidad.
//   - Quitar un frame o cambiar la forma de uno existente exige `manos-libres/2`.
//
// Lo que la app manda al nodo ya no son frames: con el transporte HTTP/3 cada uno es una
// petición a su endpoint, y sus cuerpos viven en `transporte.go`. Esa asimetría es real —
// el canal descendente es un flujo largo y el ascendente son peticiones sueltas y raras.
package main

import "strings"

const VersionProtocolo = "manos-libres/1"

// Estado de una sesión. Lo único que la app mira para saber si «está pasando algo».
type Estado string

const (
	EstadoIdle       Estado = "idle"     // esperando al usuario
	EstadoPensando   Estado = "thinking" // el modelo está generando
	EstadoTrabajando Estado = "working"  // ejecutando herramientas
	EstadoEsperando  Estado = "waiting"  // bloqueado en una decisión
	EstadoError      Estado = "error"
)

// Patrones hápticos. Son cuatro a propósito: en el bolsillo, con abrigo, no se distinguen
// más. El mapeo a milisegundos es del cliente, no del protocolo.
type Patron string

const (
	PatronCorto   Patron = "corto"
	PatronDoble   Patron = "doble"
	PatronLargo   Patron = "largo"
	PatronUrgente Patron = "urgente"
)

// Carga afectiva de una opción de decisión. Determina color, grosor de borde, icono y
// háptica de confirmación — de modo que aprobar algo destructivo se *sienta* distinto de
// aprobar algo inocuo, sin necesidad de leer.
type Tono string

const (
	TonoNeutral Tono = "neutral"
	TonoGo      Tono = "go"
	TonoStop    Tono = "stop"
	TonoPeligro Tono = "danger"
)

// De dónde viene una decisión. Cambia el encabezado y si un plazo tiene sentido.
type Origen string

const (
	OrigenPermiso  Origen = "permission"
	OrigenPregunta Origen = "ask_user_question"
	OrigenNodo     Origen = "node"
)

type CodigoError string

const (
	ErrAuth      CodigoError = "auth_failed"
	ErrProtocolo CodigoError = "protocol_mismatch"
	ErrSinSesion CodigoError = "session_gone"
	ErrHueco     CodigoError = "replay_gap"
	ErrMotor     CodigoError = "engine_error"
	ErrOcupado   CodigoError = "busy"
	ErrPeticion  CodigoError = "bad_request"
	ErrRuta      CodigoError = "forbidden_path"
	ErrTasa      CodigoError = "rate_limited"
)

// InfoSesion es el resumen de una sesión que viaja en `hello` y en `session.list`.
type InfoSesion struct {
	SessionID string `json:"sessionId"`
	Titulo    string `json:"title"`
	Cwd       string `json:"cwd"`
	Motor     string `json:"engine"`
	Estado    Estado `json:"state"`
	// Último `seq` emitido. La app lo usa como `since` al reconectar.
	Seq     int64 `json:"seq"`
	Editado int64 `json:"updatedAt"`
	// Decisiones pendientes: reconectar no debe perder que el agente está bloqueado.
	Pendientes int `json:"pendingDecisions"`
}

// Narrable separa lo que se narra de lo que se ve.
//
// `Frases` es la unidad de narración: cada elemento se encola como una locución
// independiente, y su índice —junto al id del mensaje— es la coordenada estable que permite
// el retroceso frase a frase y la continuidad tras reconectar.
type Narrable struct {
	Frases []string `json:"sentences"`
	// Cuántos fragmentos (código, diffs, salidas) se sustituyeron por un anuncio.
	Elididos int `json:"elided"`
}

type Opcion struct {
	ID string `json:"id"`
	// Lo que se pinta en la franja: una o dos palabras, legible de reojo.
	Etiqueta string `json:"label"`
	// Lo que se pronuncia: sin puntuación rara ni identificadores en snake_case.
	Hablada string `json:"spoken"`
	// El detalle. Solo se muestra o se lee si el usuario lo pide.
	Detalle string `json:"description,omitempty"`
	Tono    Tono   `json:"tone"`
	// Marca la opción de «permitir siempre». Se pinta como una franja estrecha aparte,
	// nunca como una de las principales.
	Fija bool `json:"sticky,omitempty"`
}

// ── Nodo → app ──────────────────────────────────────────────────────────────────────────

// Frame es cualquier cosa que el nodo manda a la app. La interfaz existe para que el buffer
// de replay pueda ordenar y filtrar por `seq` sin conocer cada tipo.
type Frame interface {
	Tipo() string
	Seq() int64
}

// cabecera va incrustada en todos los frames. `encoding/json` aplana los structs anónimos,
// así que `t` y `seq` salen al nivel de arriba del objeto, como manda el protocolo.
type cabecera struct {
	T string `json:"t"`
	S int64  `json:"seq,omitempty"`
}

func (c cabecera) Tipo() string { return c.T }
func (c cabecera) Seq() int64   { return c.S }

type Hello struct {
	cabecera
	Protocolo string       `json:"protocol"`
	Nodo      string       `json:"node"`
	Motores   []string     `json:"engines"`
	Sesiones  []InfoSesion `json:"sessions"`
	// Reto a firmar con la clave del Keystore en el siguiente emparejamiento.
	Reto string `json:"challenge,omitempty"`
	// Los últimos mensajes de chat entre dispositivos. Buffer en anillo del nodo, igual que
	// el replay de una sesión: quien se conecta no empieza a ciegas, y se pierde al
	// reiniciar, a propósito (ver docs/07-decisiones.md §11).
	Chat []*Chat `json:"chatHistory,omitempty"`
}

type ListaSesiones struct {
	cabecera
	Sesiones []InfoSesion `json:"sessions"`
}

type EstadoSesion struct {
	cabecera
	SessionID string `json:"sessionId"`
	Estado    Estado `json:"state"`
	Detalle   string `json:"detail,omitempty"`
}

// Delta es texto en streaming. Solo para la pantalla: narrar deltas corta la entonación.
type Delta struct {
	cabecera
	SessionID string `json:"sessionId"`
	MessageID string `json:"messageId"`
	Texto     string `json:"text"`
}

type Mensaje struct {
	cabecera
	SessionID string `json:"sessionId"`
	MessageID string `json:"messageId"`
	Rol       string `json:"role"`
	Clase     string `json:"kind"`
	// Lo que se ve. Recortado a MaxTexto por el nodo.
	Texto string `json:"text"`
	// Lo que se oye.
	Narrable Narrable `json:"narratable"`
}

// Herramienta es actividad del agente, ya resumida a una etiqueta corta y narrable.
type Herramienta struct {
	cabecera
	SessionID string `json:"sessionId"`
	ToolUseID string `json:"toolUseId"`
	Fase      string `json:"phase"`
	Etiqueta  string `json:"label"`
	Ok        *bool  `json:"ok,omitempty"`
}

type PideDecision struct {
	cabecera
	SessionID  string `json:"sessionId"`
	DecisionID string `json:"decisionId"`
	Origen     Origen `json:"source"`
	Multiple   bool   `json:"multiSelect"`
	// La pregunta tal cual, para la pantalla.
	Pregunta string `json:"prompt"`
	// La pregunta más la enumeración de opciones, para la voz.
	Hablada  string   `json:"spoken"`
	Opciones []Opcion `json:"options"`
	// Plazo informativo. Los permisos del agente no caducan, así que el nodo nunca
	// responde por el usuario: pasado el plazo solo insiste con el aviso.
	PlazoMs int64 `json:"deadlineMs,omitempty"`
}

type DecisionResuelta struct {
	cabecera
	SessionID  string `json:"sessionId"`
	DecisionID string `json:"decisionId"`
	Resolucion string `json:"resolution"`
	// Qué dispositivo contestó, para avisar en los demás.
	Por string `json:"by,omitempty"`
}

type Aviso struct {
	cabecera
	SessionID string `json:"sessionId"`
	Patron    Patron `json:"pattern"`
	// `agent` cuando lo pidió el agente con la herramienta `avisar`.
	Origen  string `json:"source"`
	Mensaje string `json:"message,omitempty"`
	Hablado string `json:"spoken,omitempty"`
	Sonido  string `json:"sound,omitempty"`
}

type TareaHecha struct {
	cabecera
	SessionID  string  `json:"sessionId"`
	Resumen    string  `json:"summary"`
	Hablado    string  `json:"spoken"`
	CosteUsd   float64 `json:"costUsd,omitempty"`
	DuracionMs int64   `json:"durationMs,omitempty"`
}

// Chat es un mensaje directo entre dispositivos del mismo nodo, fuera de cualquier sesión de
// agente. No lleva `sessionId`: viaja por el canal de control (`/v1/control`), que ya reciben
// todos los dispositivos conectados, y no por el flujo de una sesión.
//
// No hay cifrado extremo a extremo por encima de TLS: el nodo ve el texto en claro, igual
// que ve el resto del protocolo. Es una decisión, no un olvido — ver
// docs/07-decisiones.md §11.
type Chat struct {
	cabecera
	ChatID string `json:"chatId"`
	// El dispositivo que lo mandó (`X-Dispositivo`), para que la app lo distinga de lo que
	// escribió el propio dispositivo.
	De      string `json:"from"`
	Texto   string `json:"text"`
	Enviado int64  `json:"sentAt"`
}

// SesionCerrada se manda por el flujo de la sesión cuando se
// cierra: el flujo termina justo después de este frame; la lista del canal de control deja de
// incluirla. `reason`: `user`, `idle` o
// `shutdown`. Es un frame nuevo y aditivo: un cliente que no lo conozca lo ignora y verá el
// flujo cerrarse.
type SesionCerrada struct {
	cabecera
	SessionID string `json:"sessionId"`
	Motivo    string `json:"reason"`
}

type ErrorFrame struct {
	cabecera
	SessionID string      `json:"sessionId,omitempty"`
	Codigo    CodigoError `json:"code"`
	Mensaje   string      `json:"message"`
}

// EsDespertar dice si un frame exige avisar al usuario: háptica y, si procede, notificación.
//
// Como el canal está siempre abierto y no hay push, un frame que no encuentra suscriptor se
// queda en el buffer de replay y se entrega —con su aviso— cuando la app reconecta. Estos
// son además los únicos frames que se duplican en el canal de control, para que una sesión
// que no se está siguiendo pueda reclamar atención (ver transporte.go).
func EsDespertar(f Frame) bool {
	switch f.Tipo() {
	case "alert", "decision.request", "task.done":
		return true
	}
	return false
}

// Enunciar produce la versión hablada de una decisión: la pregunta, y las opciones numeradas.
//
// El número es lo que hace posible contestar sin mirar —por voz o por N pulsaciones del botón
// del auricular—, así que va delante de cada opción, siempre.
func Enunciar(pregunta string, opciones []Opcion) string {
	var b strings.Builder
	b.WriteString(pregunta)
	n := 0
	for _, o := range opciones {
		if o.Fija {
			continue
		}
		n++
		b.WriteString(" Opción ")
		b.WriteString(numero(n))
		b.WriteString(": ")
		b.WriteString(o.Hablada)
		b.WriteString(".")
	}
	return b.String()
}

// numero deletrea el ordinal. Un TTS lee «1:» como «uno dos puntos» en unas voces y como
// «primero» en otras; escribirlo en letra es la única forma de que suene igual siempre.
func numero(n int) string {
	nombres := []string{"", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho"}
	if n < len(nombres) {
		return nombres[n]
	}
	return "siguiente"
}
