package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// anfitrionGrabador recoge los eventos que emite un adaptador.
type anfitrionGrabador struct {
	mu      sync.Mutex
	eventos []Evento
	nuevo   chan struct{}
}

func nuevoGrabador() *anfitrionGrabador { return &anfitrionGrabador{nuevo: make(chan struct{}, 256)} }

func (g *anfitrionGrabador) Emitir(e Evento) {
	g.mu.Lock()
	g.eventos = append(g.eventos, e)
	g.mu.Unlock()
	select {
	case g.nuevo <- struct{}{}:
	default:
	}
}
func (g *anfitrionGrabador) Decidir(context.Context, PeticionDecision) (RespuestaDecision, error) {
	return RespuestaDecision{}, nil
}
func (g *anfitrionGrabador) copia() []Evento {
	g.mu.Lock()
	defer g.mu.Unlock()
	return append([]Evento(nil), g.eventos...)
}

// esperar bloquea hasta que haya un evento que cumpla `ok`, o falla.
func (g *anfitrionGrabador) esperar(t *testing.T, desc string, ok func(Evento) bool) Evento {
	t.Helper()
	limite := time.After(5 * time.Second)
	visto := 0
	for {
		ev := g.copia()
		for ; visto < len(ev); visto++ {
			if ok(ev[visto]) {
				return ev[visto]
			}
		}
		select {
		case <-g.nuevo:
		case <-limite:
			t.Fatalf("no llegó: %s (eventos: %+v)", desc, ev)
		}
	}
}

// ── Contrato del stream-json ────────────────────────────────────────────────────────────
//
// Muestras con la forma de los mensajes del CLI en `--output-format stream-json --verbose
// --include-partial-messages`, según su documentación y las trazas con las que se escribió
// el adaptador. Si el CLI cambia de forma, este test falla en lugar de que lo haga la sesión
// en silencio. TODO: sustituir por líneas grabadas del CLI fijado (docs/08-tareas.md).
var muestrasStreamJSON = []struct {
	nombre   string
	linea    string
	esperado []Evento // comparados en Clase y los campos listados abajo
}{
	{
		"system init",
		`{"type":"system","subtype":"init","session_id":"3f2c-engine","cwd":"/work","tools":["Bash","Read"],"mcp_servers":[{"name":"manos_libres","status":"connected"}],"model":"opus","permissionMode":"default"}`,
		[]Evento{{Clase: EvEstado, Estado: EstadoIdle}},
	},
	{
		"delta de texto",
		`{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hola"}},"session_id":"3f2c-engine","uuid":"u-1"}`,
		[]Evento{{Clase: EvDelta, MessageID: "u-1", Texto: "Hola"}},
	},
	{
		"otros stream_event se ignoran",
		`{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_1"}},"uuid":"u-2"}`,
		nil,
	},
	{
		"delta que no es texto se ignora",
		`{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"a\""}},"uuid":"u-3"}`,
		nil,
	},
	{
		"assistant con texto y herramienta",
		`{"type":"assistant","uuid":"u-4","session_id":"3f2c-engine","message":{"id":"msg_2","role":"assistant","content":[{"type":"text","text":"Voy a mirar."},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}`,
		[]Evento{
			{Clase: EvMensaje, MessageID: "u-4", Rol: "assistant", Tipo: "text", Texto: "Voy a mirar."},
			{Clase: EvHerramienta, ToolUseID: "toolu_1", Fase: "start", Nombre: "Bash"},
			{Clase: EvEstado, Estado: EstadoTrabajando},
		},
	},
	{
		"tool_result correcto cierra la herramienta",
		`{"type":"user","uuid":"u-5","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"a.txt\nb.txt","is_error":false}]}}`,
		[]Evento{{Clase: EvHerramienta, ToolUseID: "toolu_1", Fase: "end", Nombre: "Bash"}},
	},
	{
		"tool_result con error",
		`{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_2","content":"boom","is_error":true}]}}`,
		[]Evento{{Clase: EvHerramienta, ToolUseID: "toolu_2", Fase: "end"}},
	},
	{
		"mensaje user con texto plano no rompe",
		`{"type":"user","message":{"role":"user","content":"hola"}}`,
		nil,
	},
	{
		"result success",
		`{"type":"result","subtype":"success","is_error":false,"duration_ms":1234,"total_cost_usd":0.05,"result":"Hecho.","session_id":"3f2c-engine"}`,
		[]Evento{{Clase: EvHecho, Resumen: "Hecho.", CosteUsd: 0.05, DuracionMs: 1234}, {Clase: EvEstado, Estado: EstadoIdle}},
	},
	{
		"result con error",
		`{"type":"result","subtype":"error_max_turns","is_error":true,"result":"demasiados turnos"}`,
		[]Evento{{Clase: EvError, Mensaje: "demasiados turnos"}},
	},
	{
		"tipo desconocido se ignora",
		`{"type":"rate_limit_event","info":{}}`,
		nil,
	},
}

func TestContratoStreamJSON(t *testing.T) {
	for _, m := range muestrasStreamJSON {
		t.Run(m.nombre, func(t *testing.T) {
			g := nuevoGrabador()
			a := NuevoAdaptadorClaudeCode(Config{})
			a.anfitrion = g
			// Para el tool_result: hay que haber visto antes la herramienta.
			a.enCurso["toolu_1"] = herramientaEnCurso{"Bash", map[string]any{"command": "ls"}}

			var msg map[string]any
			if err := json.Unmarshal([]byte(m.linea), &msg); err != nil {
				t.Fatalf("la muestra no es JSON: %v", err)
			}
			a.despachar(msg)

			got := g.copia()
			if len(got) != len(m.esperado) {
				t.Fatalf("eventos = %+v, quería %d", got, len(m.esperado))
			}
			for i, w := range m.esperado {
				e := got[i]
				if e.Clase != w.Clase || e.Estado != w.Estado || e.MessageID != w.MessageID ||
					e.Texto != w.Texto || e.Rol != w.Rol || e.Tipo != w.Tipo ||
					e.ToolUseID != w.ToolUseID || e.Fase != w.Fase || e.Nombre != w.Nombre ||
					e.Mensaje != w.Mensaje || e.Resumen != w.Resumen ||
					e.CosteUsd != w.CosteUsd || e.DuracionMs != w.DuracionMs {
					t.Errorf("evento %d = %+v, quería %+v", i, e, w)
				}
			}
		})
	}
}

func TestToolResultFijaOk(t *testing.T) {
	g := nuevoGrabador()
	a := NuevoAdaptadorClaudeCode(Config{})
	a.anfitrion = g
	for _, l := range []string{
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a","is_error":false}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"b","is_error":true}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"c"}]}}`, // sin is_error
	} {
		var m map[string]any
		_ = json.Unmarshal([]byte(l), &m)
		a.despachar(m)
	}
	ev := g.copia()
	if len(ev) != 3 {
		t.Fatalf("eventos = %d", len(ev))
	}
	for i, quiere := range []bool{true, false, true} {
		if ev[i].Ok == nil || *ev[i].Ok != quiere {
			t.Errorf("evento %d Ok = %v, quería %v", i, ev[i].Ok, quiere)
		}
	}
}

func TestLaEtiquetaDeLaFaseEndEsLaDelStart(t *testing.T) {
	s := nuevaSesionDePrueba(10)
	g := &anfitrionSesion{s}
	a := NuevoAdaptadorClaudeCode(Config{})
	a.anfitrion = g
	for _, l := range []string{
		`{"type":"assistant","uuid":"u","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":false}]}}`,
	} {
		var m map[string]any
		_ = json.Unmarshal([]byte(l), &m)
		a.despachar(m)
	}
	var fases []*Herramienta
	for _, f := range s.replay {
		if h, ok := f.(*Herramienta); ok {
			fases = append(fases, h)
		}
	}
	if len(fases) != 2 || fases[0].Fase != "start" || fases[1].Fase != "end" {
		t.Fatalf("fases = %+v", fases)
	}
	if fases[0].Etiqueta != fases[1].Etiqueta || fases[1].Ok == nil || !*fases[1].Ok {
		t.Errorf("start=%q end=%q ok=%v", fases[0].Etiqueta, fases[1].Etiqueta, fases[1].Ok)
	}
}

type anfitrionSesion struct{ s *Sesion }

func (a *anfitrionSesion) Emitir(e Evento) { a.s.Emitir(e) }
func (a *anfitrionSesion) Decidir(ctx context.Context, p PeticionDecision) (RespuestaDecision, error) {
	return a.s.Decidir(ctx, p)
}

// ── Contra un «claude» falso ────────────────────────────────────────────────────────────
//
// Un script de shell que habla stream-json lo bastante bien como para probar lo que el
// adaptador hace con el proceso: los argumentos, la forma del mensaje por stdin y que SIGINT
// no lo mata. Lo que el CLI *real* haga con SIGINT sigue sin verificarse.

const claudeFalso = `#!/bin/sh
echo "$@" > "$FAKE_ARGS"
trap 'echo "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"interrumpido\"}"' INT
echo '{"type":"system","subtype":"init","session_id":"eng-1"}'
while true; do
  if read -r linea; then
    echo "$linea" >> "$FAKE_STDIN"
    echo '{"type":"assistant","uuid":"u1","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}'
    echo '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":false}]}}'
    echo '{"type":"result","subtype":"success","result":"listo"}'
  else
    rc=$?
    [ "$rc" -gt 128 ] && continue
    break
  fi
done
`

func arrancarFalso(t *testing.T, reanudar string) (*AdaptadorClaudeCode, *anfitrionGrabador, string, string) {
	t.Helper()
	dir := t.TempDir()
	bin := filepath.Join(dir, "claude")
	if err := os.WriteFile(bin, []byte(claudeFalso), 0o755); err != nil {
		t.Fatal(err)
	}
	args, stdin := filepath.Join(dir, "args"), filepath.Join(dir, "stdin")
	t.Setenv("FAKE_ARGS", args)
	t.Setenv("FAKE_STDIN", stdin)

	g := nuevoGrabador()
	a := NuevoAdaptadorClaudeCode(Config{BinarioClaude: bin})
	err := a.Arrancar(context.Background(), g, OpcionesAdaptador{Cwd: dir, Modelo: "opus", Reanudar: reanudar})
	if err != nil {
		t.Fatalf("Arrancar: %v", err)
	}
	t.Cleanup(func() { _ = a.Parar() })
	return a, g, args, stdin
}

func TestAdaptadorPasaResumeYPublicaElIdDelMotor(t *testing.T) {
	a, g, rutaArgs, _ := arrancarFalso(t, "eng-previo")
	g.esperar(t, "system init", func(e Evento) bool { return e.Clase == EvEstado })

	var args string
	for i := 0; i < 100 && args == ""; i++ {
		b, _ := os.ReadFile(rutaArgs)
		args = string(b)
		time.Sleep(10 * time.Millisecond)
	}
	if !strings.Contains(args, "--resume eng-previo") {
		t.Errorf("args = %q, falta --resume con el id guardado", args)
	}
	if strings.Contains(args, "--bare") {
		t.Errorf("--bare está prohibido: %q", args)
	}
	if a.SesionDelMotor() != "eng-1" {
		t.Errorf("SesionDelMotor = %q", a.SesionDelMotor())
	}
}

func TestAdaptadorSinResumeNoLoPasa(t *testing.T) {
	_, g, rutaArgs, _ := arrancarFalso(t, "")
	g.esperar(t, "system init", func(e Evento) bool { return e.Clase == EvEstado })
	b, _ := os.ReadFile(rutaArgs)
	if strings.Contains(string(b), "--resume") {
		t.Errorf("args = %q: no debería haber --resume", b)
	}
}

func TestEnviarEscribeUnSDKUserMessageYElCicloDeHerramienta(t *testing.T) {
	a, g, _, rutaStdin := arrancarFalso(t, "")
	g.esperar(t, "init", func(e Evento) bool { return e.Clase == EvEstado })

	if err := a.Enviar("hola \"mundo\""); err != nil {
		t.Fatal(err)
	}
	fin := g.esperar(t, "tool end", func(e Evento) bool { return e.Clase == EvHerramienta && e.Fase == "end" })
	if fin.Ok == nil || !*fin.Ok || fin.Nombre != "Bash" {
		t.Errorf("end = %+v", fin)
	}
	g.esperar(t, "task done", func(e Evento) bool { return e.Clase == EvHecho })

	b, _ := os.ReadFile(rutaStdin)
	var linea struct {
		Type    string `json:"type"`
		Message struct {
			Role    string `json:"role"`
			Content []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(string(b))), &linea); err != nil {
		t.Fatalf("stdin no es una línea JSON: %q: %v", b, err)
	}
	if linea.Type != "user" || linea.Message.Role != "user" ||
		len(linea.Message.Content) != 1 || linea.Message.Content[0].Type != "text" ||
		linea.Message.Content[0].Text != "hola \"mundo\"" {
		t.Errorf("forma del mensaje de usuario = %+v", linea)
	}
}

func TestInterrumpirEnviaSIGINTYElProcesoSigueVivo(t *testing.T) {
	a, g, _, _ := arrancarFalso(t, "")
	g.esperar(t, "init", func(e Evento) bool { return e.Clase == EvEstado })

	if err := a.Interrumpir(); err != nil {
		t.Fatal(err)
	}
	g.esperar(t, "result tras SIGINT", func(e Evento) bool { return e.Clase == EvHecho && e.Resumen == "interrumpido" })

	// Sigue vivo: acepta otro turno completo y no se emitió «terminó inesperadamente».
	if err := a.Enviar("otra"); err != nil {
		t.Fatalf("Enviar tras interrumpir: %v", err)
	}
	g.esperar(t, "turno posterior", func(e Evento) bool { return e.Clase == EvHerramienta && e.Fase == "end" })
	for _, e := range g.copia() {
		if e.Clase == EvError {
			t.Errorf("error inesperado: %+v", e)
		}
	}
}

func TestSiElMotorMuereSeAvisa(t *testing.T) {
	a, g, _, _ := arrancarFalso(t, "")
	g.esperar(t, "init", func(e Evento) bool { return e.Clase == EvEstado })
	_ = a.cmd.Process.Kill()
	g.esperar(t, "aviso de muerte", func(e Evento) bool { return e.Clase == EvError })
	g.esperar(t, "estado error", func(e Evento) bool { return e.Clase == EvEstado && e.Estado == EstadoError })
}
