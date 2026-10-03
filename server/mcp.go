// El servidor MCP del nodo.
//
// Con el SDK esto era un servidor «en proceso» que se le pasaba a `query()`. Desde Go no
// existe esa opción: el CLI habla MCP y punto. Así que el nodo levanta un servidor MCP en
// loopback, con puerto efímero, y le pasa la URL al CLI con `--mcp-config`. La llamada a la
// herramienta vuelve entonces al mismo proceso que tiene la sesión, que es lo que hace falta
// para que un permiso pueda esperar a que alguien conteste en el teléfono.
//
// Expone dos herramientas y un endpoint que no es MCP:
//
//	permiso  el `--permission-prompt-tool`: es el antiguo `canUseTool`. Bloquea.
//	avisar   la que el agente llama para pedir atención del usuario.
//	/hook    donde aterrizan los hooks de fin de tarea, que son comandos y no MCP.
//
// SIN VERIFICAR CONTRA EL CLI REAL: lo de abajo implementa lo que documentan la especificación
// de MCP (revisiones 2024-11-05, 2025-03-26 y 2025-06-18, transporte «streamable HTTP») y la
// documentación del permission-prompt-tool de Claude Code, y está probado en mcp_test.go
// contra esa documentación, no contra el binario. Falta confirmarlo con el CLI fijado
// (docs/08-tareas.md). VERIFICAR: la forma exacta del transporte HTTP de MCP, el `protocolVersion` que espera el
// CLI y el contrato de respuesta del permission-prompt-tool dependen de la versión fijada
// del binario. Los tres puntos están marcados abajo. Es el mismo tipo de deriva que tenían
// los VERIFICAR del adaptador en TypeScript, movido de sitio.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type ServidorMCP struct {
	anfitrion Anfitrion
	oyente    net.Listener
	servidor  *http.Server
}

func ArrancarMCP(anf Anfitrion) (*ServidorMCP, error) {
	// Puerto efímero y solo loopback: esto no debe ser alcanzable desde fuera de la máquina.
	oyente, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}

	m := &ServidorMCP{anfitrion: anf, oyente: oyente}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /mcp", m.rpc)
	// Streamable HTTP: el servidor puede no ofrecer el flujo GET de notificaciones, y debe
	// contestar 405 en ese caso (así el cliente sabe que no hay y no reintenta). Idem DELETE
	// (cierre de sesión MCP, que no mantenemos).
	mux.HandleFunc("GET /mcp", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Allow", "POST")
		http.Error(w, "este servidor no ofrece flujo GET", http.StatusMethodNotAllowed)
	})
	mux.HandleFunc("DELETE /mcp", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Allow", "POST")
		http.Error(w, "sin sesiones MCP", http.StatusMethodNotAllowed)
	})
	mux.HandleFunc("POST /hook", m.hook)
	m.servidor = &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}

	go func() {
		if err := m.servidor.Serve(oyente); err != nil && err != http.ErrServerClosed {
			fmt.Printf("mcp: %v\n", err)
		}
	}()
	return m, nil
}

func (m *ServidorMCP) base() string { return "http://" + m.oyente.Addr().String() }

// Config es lo que se le pasa al CLI en `--mcp-config`.
//
// VERIFICAR: el nombre del servidor decide el prefijo de las herramientas
// (`mcp__manos_libres__permiso`), que es lo que se pasa en `--permission-prompt-tool` y en
// `--allowedTools`. Si cambia uno hay que cambiar los tres.
func (m *ServidorMCP) Config() string {
	cfg := map[string]any{
		"mcpServers": map[string]any{
			"manos_libres": map[string]any{
				"type": "http",
				"url":  m.base() + "/mcp",
			},
		},
	}
	b, _ := json.Marshal(cfg)
	return string(b)
}

func (m *ServidorMCP) URLHook() string { return m.base() + "/hook" }

func (m *ServidorMCP) Parar() error {
	ctx, cancelar := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancelar()
	return m.servidor.Shutdown(ctx)
}

// ── JSON-RPC ────────────────────────────────────────────────────────────────────────────

type peticionRPC struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Metodo  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type respuestaRPC struct {
	JSONRPC   string          `json:"jsonrpc"`
	ID        json.RawMessage `json:"id,omitempty"`
	Resultado any             `json:"result,omitempty"`
	Error     *errorRPC       `json:"error,omitempty"`
}

type errorRPC struct {
	Codigo  int    `json:"code"`
	Mensaje string `json:"message"`
}

// versionesMCP son las revisiones del protocolo que sabemos hablar, de más nueva a más
// vieja. En `initialize` el cliente propone una: si la conocemos la devolvemos tal cual; si
// no, respondemos con la nuestra más nueva y es el cliente quien decide si la acepta (spec
// MCP, «Lifecycle / Version Negotiation»).
var versionesMCP = []string{"2025-06-18", "2025-03-26", "2024-11-05"}

func negociarVersion(pedida string) string {
	for _, v := range versionesMCP {
		if v == pedida {
			return v
		}
	}
	return versionesMCP[0]
}

// origenLocal aplica la regla de seguridad del transporte streamable HTTP: si la petición
// trae `Origin`, tiene que ser de loopback, o una página web cualquiera podría hablarle a
// este servidor desde el navegador del usuario (DNS rebinding). El CLI no manda `Origin`.
func origenLocal(r *http.Request) bool {
	origen := r.Header.Get("Origin")
	if origen == "" {
		return true
	}
	u, err := url.Parse(origen)
	if err != nil {
		return false
	}
	switch u.Hostname() {
	case "127.0.0.1", "localhost", "::1":
		return true
	}
	return false
}

func (m *ServidorMCP) rpc(w http.ResponseWriter, r *http.Request) {
	if !origenLocal(r) {
		http.Error(w, "origen no permitido", http.StatusForbidden)
		return
	}
	var pet peticionRPC
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&pet); err != nil {
		http.Error(w, "json inválido", http.StatusBadRequest)
		return
	}

	// Una notificación no lleva id y no espera respuesta.
	if len(pet.ID) == 0 {
		w.WriteHeader(http.StatusAccepted)
		return
	}

	resp := respuestaRPC{JSONRPC: "2.0", ID: pet.ID}
	switch pet.Metodo {
	case "initialize":
		// VERIFICAR: `protocolVersion` tiene que ser una que el CLI acepte; se negocia.
		var p struct {
			Version string `json:"protocolVersion"`
		}
		_ = json.Unmarshal(pet.Params, &p)
		resp.Resultado = map[string]any{
			"protocolVersion": negociarVersion(p.Version),
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": "manos-libres", "version": "0.0.0"},
		}
	case "ping":
		resp.Resultado = map[string]any{}
	case "tools/list":
		resp.Resultado = map[string]any{"tools": herramientas()}
	case "tools/call":
		resultado, err := m.llamar(r.Context(), pet.Params)
		if err != nil {
			resp.Error = &errorRPC{Codigo: -32603, Mensaje: err.Error()}
		} else {
			resp.Resultado = resultado
		}
	default:
		resp.Error = &errorRPC{Codigo: -32601, Mensaje: "método desconocido: " + pet.Metodo}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func herramientas() []any {
	return []any{
		map[string]any{
			"name": "permiso",
			"description": "Pregunta al usuario si el agente puede usar una herramienta. " +
				"La llama Claude Code, no el agente.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"tool_name": map[string]any{"type": "string"},
					"input":     map[string]any{"type": "object"},
				},
				"required": []string{"tool_name", "input"},
			},
		},
		map[string]any{
			"name": "avisar",
			"description": "Pide la atención del usuario: vibra el teléfono y, si está " +
				"narrando, intercala el mensaje.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"mensaje": map[string]any{"type": "string", "maxLength": 120},
					"patron": map[string]any{
						"type": "string",
						"enum": []string{"corto", "doble", "largo", "urgente"},
					},
				},
				"required": []string{"mensaje"},
			},
		},
	}
}

func (m *ServidorMCP) llamar(ctx context.Context, params json.RawMessage) (any, error) {
	var p struct {
		Nombre string         `json:"name"`
		Args   map[string]any `json:"arguments"`
	}
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, err
	}

	switch p.Nombre {
	case "permiso":
		return m.permiso(ctx, p.Args)
	case "avisar":
		return m.avisar(p.Args)
	default:
		return nil, fmt.Errorf("herramienta desconocida: %s", p.Nombre)
	}
}

// permiso es el antiguo `canUseTool`: **bloquea hasta que alguien conteste en el teléfono**.
// Mientras tanto el agente está parado, que es exactamente lo que se quiere.
//
// VERIFICAR contra la versión fijada del CLI: el permission-prompt-tool devuelve su veredicto
// como JSON dentro de un bloque de texto, con `behavior` allow/deny y, al permitir, el
// `updatedInput` que el agente acabará usando. Si la forma cambia, el agente se quedaría
// bloqueado sin decir por qué.
func (m *ServidorMCP) permiso(ctx context.Context, args map[string]any) (any, error) {
	nombre := cadena(args["tool_name"])
	input, _ := args["input"].(map[string]any)

	respuesta, err := m.anfitrion.Decidir(ctx, PeticionDecision{
		Origen:   OrigenPermiso,
		Pregunta: fmt.Sprintf("¿Dejo que %s?", EtiquetaHerramienta(nombre, input)),
		Opciones: []Opcion{
			{ID: "allow", Etiqueta: "Sí", Hablada: "sí, adelante", Tono: TonoGo},
			{ID: "deny", Etiqueta: "No", Hablada: "no, para", Tono: TonoStop},
			{ID: "always", Etiqueta: "Siempre", Hablada: "sí, y no vuelvas a preguntar",
				Tono: TonoNeutral, Fija: true},
		},
	})
	if err != nil {
		return nil, err
	}

	return contenidoJSON(veredictoPermiso(respuesta, input)), nil
}

// veredictoPermiso es el contrato del permission-prompt-tool según la documentación de
// Claude Code: un JSON, dentro de un bloque de texto, con `{"behavior":"allow",
// "updatedInput":{…}}` para permitir (`updatedInput` es obligatorio: si no se cambia nada,
// el mismo input) o `{"behavior":"deny","message":"…"}` para denegar. Todo lo que no sea un
// «sí» explícito deniega. SIN VERIFICAR contra el CLI real.
func veredictoPermiso(r RespuestaDecision, input map[string]any) map[string]any {
	if contiene(r.OpcionIDs, "allow") || contiene(r.OpcionIDs, "always") {
		if input == nil {
			input = map[string]any{}
		}
		return map[string]any{"behavior": "allow", "updatedInput": input}
	}
	return map[string]any{"behavior": "deny", "message": "el usuario lo denegó"}
}

func (m *ServidorMCP) avisar(args map[string]any) (any, error) {
	// El mensaje se recorta y no se interpreta: acaba en una notificación del sistema, y es
	// texto que escribe el agente (ver docs/05-seguridad.md §Superficie del agente).
	mensaje := recortar(strings.TrimSpace(cadena(args["mensaje"])), 120)

	patron := Patron(cadena(args["patron"]))
	switch patron {
	case PatronCorto, PatronDoble, PatronLargo, PatronUrgente:
	default:
		patron = PatronCorto
	}

	m.anfitrion.Emitir(Evento{Clase: EvAviso, Patron: patron, Origen: "agent", Mensaje: mensaje})
	return contenidoTexto("avisado"), nil
}

// hook recibe los hooks de fin de tarea, que son comandos del shell y no llamadas MCP.
//
// TODO(H2): distinguir `Stop` de `TaskCompleted` y traer el resumen de la tarea, que hoy
// llega por el mensaje `result` del propio flujo y hace este hook redundante. Existe porque
// `result` no se emite cuando el turno acaba sin resultado.
func (m *ServidorMCP) hook(w http.ResponseWriter, _ *http.Request) {
	m.anfitrion.Emitir(Evento{
		Clase: EvAviso, Patron: PatronDoble, Origen: "node", Mensaje: "tarea terminada",
	})
	w.WriteHeader(http.StatusNoContent)
}

// contenidoTexto y contenidoJSON envuelven el resultado en la forma que espera MCP: una
// lista de bloques de contenido, no un valor suelto.
func contenidoTexto(texto string) any {
	return map[string]any{"content": []any{map[string]any{"type": "text", "text": texto}}}
}

func contenidoJSON(v any) any {
	b, _ := json.Marshal(v)
	return contenidoTexto(string(b))
}

func contiene(xs []string, x string) bool {
	for _, s := range xs {
		if s == x {
			return true
		}
	}
	return false
}
