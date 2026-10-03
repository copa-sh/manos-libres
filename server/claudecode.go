// Adaptador de Claude Code.
//
// **No hay Agent SDK para Go**: existe para Python y TypeScript, y la vía oficial desde
// cualquier otro lenguaje es ejecutar el CLI como subproceso. Así que esto es lo que el SDK
// hacía por debajo: un `claude -p` de vida larga, mensajes de usuario por stdin en
// `stream-json`, y sus mensajes de vuelta por stdout, una línea de JSON por mensaje.
//
// La correspondencia con lo que ofrecía el SDK:
//
//	query() con entrada en streaming  →  --input-format stream-json, escribiendo en stdin
//	canUseTool                        →  --permission-prompt-tool, contra el MCP del nodo
//	servidor MCP en proceso           →  --mcp-config con una url a nuestro propio MCP
//	hooks Stop / TaskCompleted        →  --settings con los hooks en línea
//	interrupt()                       →  SIGINT (SIGTERM deja el turno a medias, sale 143)
//	resume                            →  --resume con el id que publica el system/init
//
// **`--bare` está prohibido aquí, y no por gusto.** La documentación lo recomienda para
// llamadas programáticas y dice que pasará a ser el modo por defecto de `-p`, pero en modo
// bare el CLI no lee las credenciales OAuth ni el llavero: exige `ANTHROPIC_API_KEY`. Este
// proyecto existe para consumir la *suscripción*, así que el día que `-p` cambie de defecto
// el nodo se pondría a facturar por token en silencio, sin fallar. De ahí que la versión del
// binario se fije en el despliegue.
//
// El precio de no usarlo es que la sesión carga el `CLAUDE.md`, las skills, los servidores
// MCP y **los hooks** del directorio de trabajo. Para este proyecto lo primero es deseable
// —el agente de la calle debe portarse como el de tu terminal— y lo último es una superficie
// de ejecución que `ALLOWED_ROOTS` no cubre; se cierra contenerizando el agente, en H6.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os/exec"
	"strings"
	"sync"
	"syscall"
)

type AdaptadorClaudeCode struct {
	cfg Config

	mu          sync.Mutex
	cmd         *exec.Cmd
	stdin       io.WriteCloser
	anfitrion   Anfitrion
	sesionMotor string
	mcp         *ServidorMCP
	cancelar    context.CancelFunc
	parando     bool

	// Herramientas en curso, por `tool_use_id`, para poder etiquetar la fase `end` cuando
	// llega el `tool_result` (que solo trae el id).
	enCurso map[string]herramientaEnCurso
}

type herramientaEnCurso struct {
	nombre string
	input  map[string]any
}

func NuevoAdaptadorClaudeCode(cfg Config) *AdaptadorClaudeCode {
	return &AdaptadorClaudeCode{cfg: cfg, enCurso: map[string]herramientaEnCurso{}}
}

func (a *AdaptadorClaudeCode) Motor() string { return "claude-code" }

func (a *AdaptadorClaudeCode) SesionDelMotor() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.sesionMotor
}

func (a *AdaptadorClaudeCode) Arrancar(ctx context.Context, anf Anfitrion, opts OpcionesAdaptador) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.cmd != nil {
		return errors.New("el adaptador ya está arrancado")
	}
	a.anfitrion = anf

	// El MCP del nodo escucha en loopback y le pasamos su URL al CLI. Tiene que estar en
	// pie antes de arrancar el CLI: con `--mcp-config`, Claude Code espera a que los
	// servidores conecten antes del primer turno, hasta MCP_TIMEOUT (30 s por defecto).
	mcp, err := ArrancarMCP(anf)
	if err != nil {
		return fmt.Errorf("no se pudo levantar el MCP del nodo: %w", err)
	}
	a.mcp = mcp

	ctx, a.cancelar = context.WithCancel(ctx)

	args := []string{
		"-p",
		"--input-format", "stream-json",
		"--output-format", "stream-json",
		// `--verbose` es obligatorio con stream-json; sin él el CLI no emite el flujo.
		"--verbose",
		// Los deltas alimentan la pantalla. La narración espera al mensaje completo.
		"--include-partial-messages",
		// El modo que pregunta. `bypassPermissions` está prohibido en este proyecto:
		// ver docs/05-seguridad.md.
		"--permission-mode", "default",
		"--permission-prompt-tool", "mcp__manos_libres__permiso",
		"--mcp-config", mcp.Config(),
		// La herramienta de avisos no debe generar una decisión cada vez que se usa.
		"--allowedTools", "mcp__manos_libres__avisar",
		"--settings", ajustesConHooks(mcp.URLHook()),
		"--model", opts.Modelo,
	}
	if opts.Reanudar != "" {
		args = append(args, "--resume", opts.Reanudar)
	}
	// TODO(H6): `--disallowedTools` con lo que no tiene sentido en remoto.

	cmd := exec.CommandContext(ctx, a.cfg.BinarioClaude, args...)
	cmd.Dir = opts.Cwd
	// Sin esto, `CommandContext` mata el proceso con SIGKILL al cancelar y se pierde el
	// cierre limpio de la sesión del motor.
	cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGINT) }

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("no se pudo arrancar %s: %w", a.cfg.BinarioClaude, err)
	}

	a.cmd, a.stdin = cmd, stdin
	go func() {
		a.consumir(stdout)
		a.alTerminarSalida()
	}()
	go registrarStderr(stderr)
	return nil
}

// Enviar mete un mensaje de usuario por stdin. El CLI lo encola: si hay un turno en curso,
// lo atiende cuando termine, que es el mismo comportamiento que tenía la cola del SDK.
func (a *AdaptadorClaudeCode) Enviar(texto string) error {
	a.mu.Lock()
	stdin := a.stdin
	a.mu.Unlock()
	if stdin == nil {
		return errors.New("el adaptador no está arrancado")
	}

	// VERIFICAR contra la versión fijada del CLI: la forma del mensaje de usuario en
	// `--input-format stream-json` es la de un SDKUserMessage y puede cambiar de versión.
	linea, err := json.Marshal(map[string]any{
		"type": "user",
		"message": map[string]any{
			"role":    "user",
			"content": []any{map[string]any{"type": "text", "text": texto}},
		},
	})
	if err != nil {
		return err
	}
	_, err = stdin.Write(append(linea, '\n'))
	return err
}

// Interrumpir termina el turno en curso sin matar la sesión. SIGINT y no SIGTERM: con
// SIGTERM el CLI sale con 143 y deja el turno sin terminar ni registrar.
func (a *AdaptadorClaudeCode) Interrumpir() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.cmd == nil || a.cmd.Process == nil {
		return errors.New("el adaptador no está arrancado")
	}
	return a.cmd.Process.Signal(syscall.SIGINT)
}

// alTerminarSalida avisa si el CLI se fue sin que lo hubiéramos pedido: por ejemplo, si
// SIGINT lo mata en vez de terminar el turno. Sin esto la sesión se quedaría «viva» y muda.
func (a *AdaptadorClaudeCode) alTerminarSalida() {
	a.mu.Lock()
	parando, anf := a.parando, a.anfitrion
	a.mu.Unlock()
	if parando || anf == nil {
		return
	}
	anf.Emitir(Evento{Clase: EvError, Mensaje: "el motor terminó inesperadamente"})
	anf.Emitir(Evento{Clase: EvEstado, Estado: EstadoError, Detalle: "el motor terminó"})
}

func (a *AdaptadorClaudeCode) Parar() error {
	a.mu.Lock()
	a.parando = true
	cancelar, stdin, cmd, mcp := a.cancelar, a.stdin, a.cmd, a.mcp
	a.cmd, a.stdin = nil, nil
	a.mu.Unlock()

	// Cerrar stdin es lo que le dice al CLI que no habrá más mensajes; una decisión que
	// siguiera pendiente se cancela sola al acabarse la entrada.
	if stdin != nil {
		_ = stdin.Close()
	}
	if cancelar != nil {
		cancelar()
	}
	if cmd != nil {
		_ = cmd.Wait()
	}
	if mcp != nil {
		return mcp.Parar()
	}
	return nil
}

// ── Consumo del stream ──────────────────────────────────────────────────────────────────

// consumir lee el stdout del CLI: una línea de JSON por mensaje.
func (a *AdaptadorClaudeCode) consumir(stdout io.ReadCloser) {
	defer stdout.Close()

	lector := bufio.NewReaderSize(stdout, 64*1024)
	for {
		linea, err := leerLineaLarga(lector)
		if err != nil {
			if !errors.Is(err, io.EOF) {
				a.anfitrion.Emitir(Evento{Clase: EvError, Mensaje: err.Error()})
			}
			return
		}
		if len(strings.TrimSpace(linea)) == 0 {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal([]byte(linea), &msg); err != nil {
			// Una línea que no es JSON no es motivo para tirar la sesión: el CLI escribe
			// avisos sueltos por stdout en algunas versiones.
			log.Printf("línea no reconocida del CLI: %.120s", linea)
			continue
		}
		a.despachar(msg)
	}
}

// leerLineaLarga junta los trozos de una línea que no cabe en el buffer. Un mensaje del
// agente con un fichero entero dentro pasa de largo cualquier tamaño razonable, y perderlo
// por truncamiento sería peor que gastar la memoria.
func leerLineaLarga(r *bufio.Reader) (string, error) {
	var b strings.Builder
	for {
		trozo, mas, err := r.ReadLine()
		if err != nil {
			return b.String(), err
		}
		b.Write(trozo)
		if !mas {
			return b.String(), nil
		}
	}
}

func (a *AdaptadorClaudeCode) despachar(msg map[string]any) {
	switch cadena(msg["type"]) {
	case "system":
		if id := cadena(msg["session_id"]); id != "" {
			a.mu.Lock()
			a.sesionMotor = id
			a.mu.Unlock()
		}
		a.anfitrion.Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle})

	case "stream_event":
		// Un evento de streaming de la Messages API. Solo interesan los deltas de texto;
		// el resto es ruido para la pantalla.
		ev, _ := msg["event"].(map[string]any)
		delta, _ := ev["delta"].(map[string]any)
		if cadena(ev["type"]) == "content_block_delta" && cadena(delta["type"]) == "text_delta" {
			a.anfitrion.Emitir(Evento{
				Clase: EvDelta, MessageID: cadena(msg["uuid"]), Texto: cadena(delta["text"]),
			})
		}

	case "assistant":
		m, _ := msg["message"].(map[string]any)
		bloques, _ := m["content"].([]any)

		var textos []string
		for _, b := range bloques {
			bloque, _ := b.(map[string]any)
			if cadena(bloque["type"]) == "text" {
				textos = append(textos, cadena(bloque["text"]))
			}
		}
		if texto := strings.TrimSpace(strings.Join(textos, "\n")); texto != "" {
			a.anfitrion.Emitir(Evento{
				Clase: EvMensaje, MessageID: cadena(msg["uuid"]),
				Rol: "assistant", Tipo: "text", Texto: texto,
			})
		}

		for _, b := range bloques {
			bloque, _ := b.(map[string]any)
			if cadena(bloque["type"]) != "tool_use" {
				continue
			}
			input, _ := bloque["input"].(map[string]any)
			a.mu.Lock()
			a.enCurso[cadena(bloque["id"])] = herramientaEnCurso{cadena(bloque["name"]), input}
			a.mu.Unlock()
			a.anfitrion.Emitir(Evento{
				Clase: EvHerramienta, ToolUseID: cadena(bloque["id"]), Fase: "start",
				Nombre: cadena(bloque["name"]), Input: input,
			})
			a.anfitrion.Emitir(Evento{Clase: EvEstado, Estado: EstadoTrabajando})
		}

	case "user":
		// Los resultados de herramientas vuelven como mensajes `user` con bloques
		// `tool_result {tool_use_id, is_error}`. Cada uno cierra la fase de su herramienta.
		m, _ := msg["message"].(map[string]any)
		bloques, _ := m["content"].([]any) // `content` es un string cuando es texto plano
		for _, b := range bloques {
			bloque, _ := b.(map[string]any)
			if cadena(bloque["type"]) != "tool_result" {
				continue
			}
			id := cadena(bloque["tool_use_id"])
			esError, _ := bloque["is_error"].(bool)
			ok := !esError

			a.mu.Lock()
			h := a.enCurso[id]
			delete(a.enCurso, id)
			a.mu.Unlock()

			a.anfitrion.Emitir(Evento{
				Clase: EvHerramienta, ToolUseID: id, Fase: "end",
				Nombre: h.nombre, Input: h.input, Ok: &ok,
			})
		}

	case "result":
		if cadena(msg["subtype"]) != "success" {
			mensaje := cadena(msg["result"])
			if mensaje == "" {
				mensaje = "el turno falló"
			}
			a.anfitrion.Emitir(Evento{Clase: EvError, Mensaje: mensaje})
			return
		}
		a.anfitrion.Emitir(Evento{
			Clase: EvHecho, Resumen: recortar(cadena(msg["result"]), 400),
			CosteUsd: numero64(msg["total_cost_usd"]), DuracionMs: int64(numero64(msg["duration_ms"])),
		})
		a.anfitrion.Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle})
	}
}

// registrarStderr manda lo que el CLI escriba a stderr al log del nodo. Ahí es donde
// aparecen los avisos de configuración —un servidor MCP que no cargó, por ejemplo— que no
// salen por el flujo de mensajes.
func registrarStderr(stderr io.ReadCloser) {
	defer stderr.Close()
	sc := bufio.NewScanner(stderr)
	for sc.Scan() {
		log.Printf("claude: %s", sc.Text())
	}
}

// ajustesConHooks devuelve el JSON de `--settings` con los hooks de fin de tarea.
//
// Un hook es un comando, así que lo que hace es un POST contra el propio nodo. Es feo tener
// que salir al shell para volver a entrar en el mismo proceso, pero es lo que hay sin SDK:
// no existe hook en proceso desde fuera de Node y Python.
//
// TODO(H2): cablearlo de verdad, con un secreto de un solo uso en la URL para que solo el
// hook pueda llamar a ese endpoint.
func ajustesConHooks(urlHook string) string {
	ajustes := map[string]any{
		"hooks": map[string]any{
			"Stop": []any{map[string]any{
				"hooks": []any{map[string]any{
					"type":    "command",
					"command": fmt.Sprintf("curl -fsS -X POST %s >/dev/null 2>&1 || true", urlHook),
				}},
			}},
		},
	}
	b, _ := json.Marshal(ajustes)
	return string(b)
}

func cadena(v any) string {
	s, _ := v.(string)
	return s
}

func numero64(v any) float64 {
	f, _ := v.(float64)
	return f
}
