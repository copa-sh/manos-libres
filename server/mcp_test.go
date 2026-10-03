package main

// Estas pruebas fijan el comportamiento de mcp.go contra lo que DOCUMENTAN la especificación
// de MCP y Claude Code. NO están verificadas contra el CLI real: ese es el bloqueante de
// docs/08-tareas.md. Si el CLI se comporta distinto, se corrige el código y estas pruebas.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type anfitrionMCP struct {
	eventos   []Evento
	respuesta RespuestaDecision
	peticion  PeticionDecision
}

func (a *anfitrionMCP) Emitir(e Evento) { a.eventos = append(a.eventos, e) }
func (a *anfitrionMCP) Decidir(_ context.Context, p PeticionDecision) (RespuestaDecision, error) {
	a.peticion = p
	return a.respuesta, nil
}

func llamarRPC(t *testing.T, m *ServidorMCP, cuerpo string, cab map[string]string) (*http.Response, string) {
	t.Helper()
	req := httptest.NewRequest("POST", "/mcp", strings.NewReader(cuerpo))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	for k, v := range cab {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	m.servidor.Handler.ServeHTTP(rec, req)
	res := rec.Result()
	b, _ := io.ReadAll(res.Body)
	return res, string(b)
}

func nuevoMCPDePrueba(t *testing.T, anf Anfitrion) *ServidorMCP {
	t.Helper()
	m, err := ArrancarMCP(anf)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = m.Parar() })
	return m
}

func TestMCPInitializeNegociaLaVersion(t *testing.T) {
	m := nuevoMCPDePrueba(t, &anfitrionMCP{})
	for pedida, quiere := range map[string]string{
		"2025-06-18": "2025-06-18",
		"2025-03-26": "2025-03-26",
		"2024-11-05": "2024-11-05",
		"2099-01-01": "2025-06-18", // desconocida: contesta con la más nueva que habla
		"":           "2025-06-18",
	} {
		_, cuerpo := llamarRPC(t, m, `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"`+pedida+`","capabilities":{},"clientInfo":{"name":"claude","version":"1"}}}`, nil)
		var r struct {
			ID     int `json:"id"`
			Result struct {
				ProtocolVersion string         `json:"protocolVersion"`
				Capabilities    map[string]any `json:"capabilities"`
				ServerInfo      map[string]any `json:"serverInfo"`
			} `json:"result"`
		}
		if err := json.Unmarshal([]byte(cuerpo), &r); err != nil {
			t.Fatalf("respuesta no es JSON: %q", cuerpo)
		}
		if r.Result.ProtocolVersion != quiere {
			t.Errorf("pedida %q: protocolVersion = %q, quería %q", pedida, r.Result.ProtocolVersion, quiere)
		}
		if _, ok := r.Result.Capabilities["tools"]; !ok || r.Result.ServerInfo["name"] == nil || r.ID != 1 {
			t.Errorf("initialize incompleto: %s", cuerpo)
		}
	}
}

func TestMCPStreamableHTTP(t *testing.T) {
	m := nuevoMCPDePrueba(t, &anfitrionMCP{})

	// POST con respuesta: application/json (el servidor puede elegir JSON en vez de SSE).
	res, _ := llamarRPC(t, m, `{"jsonrpc":"2.0","id":7,"method":"tools/list"}`, nil)
	if res.StatusCode != 200 || !strings.HasPrefix(res.Header.Get("Content-Type"), "application/json") {
		t.Errorf("tools/list: %d %s", res.StatusCode, res.Header.Get("Content-Type"))
	}

	// Una notificación (sin id) se contesta 202 sin cuerpo.
	res, cuerpo := llamarRPC(t, m, `{"jsonrpc":"2.0","method":"notifications/initialized"}`, nil)
	if res.StatusCode != http.StatusAccepted || cuerpo != "" {
		t.Errorf("notificación: %d %q, quería 202 vacío", res.StatusCode, cuerpo)
	}

	// ping → resultado vacío.
	_, cuerpo = llamarRPC(t, m, `{"jsonrpc":"2.0","id":2,"method":"ping"}`, nil)
	if !strings.Contains(cuerpo, `"result":{}`) {
		t.Errorf("ping: %q", cuerpo)
	}

	// Método desconocido: error JSON-RPC -32601.
	_, cuerpo = llamarRPC(t, m, `{"jsonrpc":"2.0","id":3,"method":"nope"}`, nil)
	if !strings.Contains(cuerpo, "-32601") {
		t.Errorf("método desconocido: %q", cuerpo)
	}

	// El flujo GET no se ofrece: 405.
	req := httptest.NewRequest("GET", "/mcp", nil)
	rec := httptest.NewRecorder()
	m.servidor.Handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET /mcp = %d, quería 405", rec.Code)
	}
}

func TestMCPRechazaOrigenesNoLocales(t *testing.T) {
	m := nuevoMCPDePrueba(t, &anfitrionMCP{})
	cuerpo := `{"jsonrpc":"2.0","id":1,"method":"ping"}`
	if res, _ := llamarRPC(t, m, cuerpo, map[string]string{"Origin": "https://evil.example"}); res.StatusCode != 403 {
		t.Errorf("origen ajeno = %d, quería 403", res.StatusCode)
	}
	if res, _ := llamarRPC(t, m, cuerpo, map[string]string{"Origin": "http://localhost:3000"}); res.StatusCode != 200 {
		t.Errorf("origen local = %d, quería 200", res.StatusCode)
	}
	if res, _ := llamarRPC(t, m, cuerpo, nil); res.StatusCode != 200 {
		t.Errorf("sin Origin = %d, quería 200", res.StatusCode)
	}
}

func TestMCPToolsListExponePermisoYAvisar(t *testing.T) {
	m := nuevoMCPDePrueba(t, &anfitrionMCP{})
	_, cuerpo := llamarRPC(t, m, `{"jsonrpc":"2.0","id":1,"method":"tools/list"}`, nil)
	for _, n := range []string{`"permiso"`, `"avisar"`, `"inputSchema"`} {
		if !strings.Contains(cuerpo, n) {
			t.Errorf("falta %s en %s", n, cuerpo)
		}
	}
}

// veredictoDe extrae el JSON del bloque de texto que devuelve `permiso`.
func veredictoDe(t *testing.T, cuerpo string) map[string]any {
	t.Helper()
	var r struct {
		Result struct {
			Content []struct{ Type, Text string } `json:"content"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(cuerpo), &r); err != nil || len(r.Result.Content) != 1 || r.Result.Content[0].Type != "text" {
		t.Fatalf("respuesta de tools/call inesperada: %s", cuerpo)
	}
	var v map[string]any
	if err := json.Unmarshal([]byte(r.Result.Content[0].Text), &v); err != nil {
		t.Fatalf("el bloque de texto no es JSON: %q", r.Result.Content[0].Text)
	}
	return v
}

func TestPermisoPermiteConUpdatedInput(t *testing.T) {
	for _, opcion := range []string{"allow", "always"} {
		anf := &anfitrionMCP{respuesta: RespuestaDecision{OpcionIDs: []string{opcion}}}
		m := nuevoMCPDePrueba(t, anf)
		_, cuerpo := llamarRPC(t, m, `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"permiso","arguments":{"tool_name":"Bash","input":{"command":"ls -la"}}}}`, nil)
		v := veredictoDe(t, cuerpo)
		if v["behavior"] != "allow" {
			t.Fatalf("%s: behavior = %v", opcion, v["behavior"])
		}
		ui, _ := v["updatedInput"].(map[string]any)
		if ui["command"] != "ls -la" {
			t.Errorf("updatedInput = %v: debe ser el input original", v["updatedInput"])
		}
		if anf.peticion.Origen != OrigenPermiso {
			t.Errorf("Origen = %v", anf.peticion.Origen)
		}
	}
}

func TestPermisoDenegaConMessage(t *testing.T) {
	for _, r := range []RespuestaDecision{{OpcionIDs: []string{"deny"}}, {}, {OpcionIDs: []string{"cualquier-cosa"}}} {
		m := nuevoMCPDePrueba(t, &anfitrionMCP{respuesta: r})
		_, cuerpo := llamarRPC(t, m, `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"permiso","arguments":{"tool_name":"Bash","input":{"command":"rm -rf /"}}}}`, nil)
		v := veredictoDe(t, cuerpo)
		if v["behavior"] != "deny" || v["message"] == nil || v["message"] == "" {
			t.Errorf("respuesta %v: veredicto = %v, quería deny con message", r.OpcionIDs, v)
		}
		if _, hay := v["updatedInput"]; hay {
			t.Errorf("un deny no lleva updatedInput: %v", v)
		}
	}
}

func TestPermisoSinInputPermiteConObjetoVacio(t *testing.T) {
	v := veredictoPermiso(RespuestaDecision{OpcionIDs: []string{"allow"}}, nil)
	ui, ok := v["updatedInput"].(map[string]any)
	if !ok || len(ui) != 0 {
		t.Errorf("updatedInput = %#v, quería {} (es obligatorio al permitir)", v["updatedInput"])
	}
}

func TestPermisoBloqueaHastaQueContestanYSeCancelaConLaPeticion(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	m := nuevoMCPDePrueba(t, s)

	ctx, cancelar := context.WithCancel(context.Background())
	hecho := make(chan error, 1)
	go func() {
		_, err := m.permiso(ctx, map[string]any{"tool_name": "Bash", "input": map[string]any{}})
		hecho <- err
	}()
	select {
	case <-hecho:
		t.Fatal("permiso volvió sin que nadie contestara")
	case <-time.After(50 * time.Millisecond):
	}
	cancelar()
	select {
	case err := <-hecho:
		if err == nil {
			t.Error("al cancelarse la petición debería devolver error, no un veredicto")
		}
	case <-time.After(time.Second):
		t.Fatal("permiso no se liberó al cancelar")
	}
}

func TestAvisarRecortaYNormalizaElPatron(t *testing.T) {
	anf := &anfitrionMCP{}
	m := nuevoMCPDePrueba(t, anf)
	largo := strings.Repeat("x", 500)
	llamarRPC(t, m, `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"avisar","arguments":{"mensaje":"`+largo+`","patron":"raro"}}}`, nil)
	if len(anf.eventos) != 1 {
		t.Fatalf("eventos = %d", len(anf.eventos))
	}
	e := anf.eventos[0]
	if e.Clase != EvAviso || e.Patron != PatronCorto || len([]rune(e.Mensaje)) > 120 || e.Origen != "agent" {
		t.Errorf("aviso = %+v", e)
	}
}

func TestMCPConfigApuntaALaURLDelServidor(t *testing.T) {
	m := nuevoMCPDePrueba(t, &anfitrionMCP{})
	var cfg struct {
		McpServers map[string]struct{ Type, URL string } `json:"mcpServers"`
	}
	if err := json.Unmarshal([]byte(m.Config()), &cfg); err != nil {
		t.Fatal(err)
	}
	s := cfg.McpServers["manos_libres"]
	if s.Type != "http" || !strings.HasPrefix(s.URL, "http://127.0.0.1:") || !strings.HasSuffix(s.URL, "/mcp") {
		t.Errorf("config = %+v", s)
	}
	// Y responde de verdad por esa URL.
	res, err := http.Post(s.URL, "application/json", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
	if err != nil || res.StatusCode != 200 {
		t.Errorf("POST a %s: %v %v", s.URL, res, err)
	}
}
