// Lectura y validación del entorno.
package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type Config struct {
	Host string
	Port string
	// Token de dispositivo. TODO(H6): sustituir por token por dispositivo + firma Ed25519.
	Token            string
	RaicesPermitidas []string
	BufferReplay     int
	MotorPorDefecto  string
	ModeloAgente     string
	// Silencio antes de mandar un comentario de keepalive por el canal SSE. Es lo que se
	// paga en batería por tener el canal abierto: subirlo ahorra, pero se arriesga a que el
	// NAT de la operadora cierre la conexión sin avisar.
	KeepaliveSegundos int
	// Ruta del binario de Claude Code. Se deja configurable porque la versión importa:
	// ver la advertencia sobre `--bare` en claudecode.go.
	BinarioClaude string
	// Versión del CLI con la que se probó el nodo. Si la instalada es otra, se avisa al
	// arrancar (ver version.go). Vacío = sin fijar, y también se avisa.
	VersionClaudeFijada string
	// Minutos sin actividad tras los que se cierra una sesión ociosa. 0 = nunca.
	InactividadMinutos int
	// Dónde se guardan las sesiones para poder reanudarlas con `--resume` tras reiniciar el
	// nodo. Vacío = no se persiste.
	FicheroEstado string
	// Límites de docs/05-seguridad.md. Todos por minuto salvo las conexiones.
	LimiteAbrirMin     int
	LimitePromptMin    int
	MaxConexionesToken int
	MaxConexionesNodo  int
	// Intentos de autenticación fallidos por IP antes del bloqueo (ventana y bloqueo: 10 min).
	MaxFallosAuth int
}

func CargarConfig() (Config, error) {
	c := Config{
		Host:              env("HOST", "127.0.0.1"),
		Port:              env("PORT", "8787"),
		Token:             strings.TrimSpace(os.Getenv("DEV_TOKEN")),
		BufferReplay:      entero("REPLAY_BUFFER", 500),
		MotorPorDefecto:   env("DEFAULT_ENGINE", "claude-code"),
		ModeloAgente:      env("AGENT_MODEL", "opus"),
		KeepaliveSegundos: entero("KEEPALIVE_SEGUNDOS", 20),
		BinarioClaude:     env("CLAUDE_BIN", "claude"),

		VersionClaudeFijada: env("CLAUDE_VERSION", ""),
		InactividadMinutos:  enteroONulo("SESSION_IDLE_MINUTES", 240),
		FicheroEstado:       env("STATE_FILE", "estado.json"),
		LimiteAbrirMin:      entero("LIMITE_ABRIR_MIN", 3),
		LimitePromptMin:     entero("LIMITE_PROMPT_MIN", 30),
		MaxConexionesToken:  entero("MAX_CONEXIONES_TOKEN", 4),
		MaxConexionesNodo:   entero("MAX_CONEXIONES_NODO", 20),
		MaxFallosAuth:       entero("MAX_FALLOS_AUTH", 5),
	}
	if c.Token == "" {
		return c, errors.New("falta la variable de entorno DEV_TOKEN (ver .env.example)")
	}

	raices := strings.TrimSpace(os.Getenv("ALLOWED_ROOTS"))
	if raices == "" {
		return c, errors.New("falta la variable de entorno ALLOWED_ROOTS (ver .env.example)")
	}
	for _, r := range strings.Split(raices, ":") {
		if r = strings.TrimSpace(r); r != "" {
			abs, err := filepath.Abs(r)
			if err != nil {
				return c, fmt.Errorf("ALLOWED_ROOTS: %w", err)
			}
			c.RaicesPermitidas = append(c.RaicesPermitidas, abs)
		}
	}
	return c, nil
}

// RaizPermitida acepta un `cwd` solo si está bajo una de las raíces permitidas. Es la barrera
// que evita abrir una sesión de agente en `/` o en `~/.ssh` desde el teléfono.
//
// Ojo con lo que NO cubre: el CLI carga el `CLAUDE.md`, las skills y —lo importante— los
// hooks del `.claude/settings.json` del directorio, y no hay bandera que lo impida sin
// perder la suscripción (ver claudecode.go). Un repo bajo una raíz permitida puede traer
// código que se ejecuta solo. La respuesta buena es contenerizar el agente, en H6.
func (c Config) RaizPermitida(cwd string) bool {
	destino, err := filepath.Abs(cwd)
	if err != nil {
		return false
	}
	for _, raiz := range c.RaicesPermitidas {
		if destino == raiz || strings.HasPrefix(destino, raiz+string(filepath.Separator)) {
			return true
		}
	}
	return false
}

func env(clave, pordefecto string) string {
	if v := strings.TrimSpace(os.Getenv(clave)); v != "" {
		return v
	}
	return pordefecto
}

func entero(clave string, pordefecto int) int {
	if v := strings.TrimSpace(os.Getenv(clave)); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
		fmt.Fprintf(os.Stderr, "aviso: %s no es un número, usando %d\n", clave, pordefecto)
	}
	return pordefecto
}

// enteroONulo es como `entero` pero admite 0 (que significa «desactivado»).
func enteroONulo(clave string, pordefecto int) int {
	if v := strings.TrimSpace(os.Getenv(clave)); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			return n
		}
		fmt.Fprintf(os.Stderr, "aviso: %s no es un número, usando %d\n", clave, pordefecto)
	}
	return pordefecto
}

// CargarDotEnv lee un .env sin pisar lo que ya venga del entorno, igual que en echo-server.
// Una dependencia menos, y el formato que hace falta cabe en veinte líneas.
func CargarDotEnv(ruta string) error {
	datos, err := os.ReadFile(ruta)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	for _, linea := range strings.Split(string(datos), "\n") {
		linea = strings.TrimSpace(linea)
		if linea == "" || strings.HasPrefix(linea, "#") {
			continue
		}
		clave, valor, ok := strings.Cut(linea, "=")
		clave, valor = strings.TrimSpace(clave), strings.TrimSpace(valor)
		if !ok || clave == "" {
			continue
		}
		if _, existe := os.LookupEnv(clave); !existe {
			_ = os.Setenv(clave, valor)
		}
	}
	return nil
}
