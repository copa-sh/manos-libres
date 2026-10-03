// Comprobación de la versión del CLI al arrancar.
//
// La versión se fija en el despliegue (CLAUDE_VERSION) porque un CLI más nuevo puede cambiar
// el defecto de `-p` a `--bare`, y en modo bare no lee las credenciales OAuth: el nodo
// pasaría a facturar por token en silencio (ver la cabecera de claudecode.go). Esto no
// bloquea el arranque —un nodo caído es peor que uno con un aviso—, pero lo deja en el log.
package main

import (
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"time"
)

var reVersion = regexp.MustCompile(`\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?`)

// VersionCLI extrae la versión de la salida de `claude --version` («2.1.3 (Claude Code)»).
func VersionCLI(salida string) string { return reVersion.FindString(salida) }

// ComprobarVersionCLI devuelve el aviso que toque, o "" si todo está en orden. `ejecutar`
// devuelve la salida de `<bin> --version`; es un parámetro para poder probarlo sin el CLI.
func ComprobarVersionCLI(cfg Config, ejecutar func(bin string) (string, error)) string {
	salida, err := ejecutar(cfg.BinarioClaude)
	if err != nil {
		return fmt.Sprintf("no se pudo ejecutar `%s --version`: %v", cfg.BinarioClaude, err)
	}
	instalada := VersionCLI(salida)
	switch {
	case instalada == "":
		return fmt.Sprintf("no se reconoce la versión del CLI en %q", salida)
	case cfg.VersionClaudeFijada == "":
		return fmt.Sprintf("CLAUDE_VERSION no está fijada (CLI instalado: %s); fíjala para "+
			"enterarte si un cambio de defecto de `-p` te saca de la suscripción", instalada)
	case instalada != cfg.VersionClaudeFijada:
		return fmt.Sprintf("el CLI instalado es %s y el nodo se probó con %s: revisa que `-p` "+
			"siga usando la suscripción (el cambio a `--bare` por defecto la rompería)",
			instalada, cfg.VersionClaudeFijada)
	}
	return ""
}

func ejecutarVersion(bin string) (string, error) {
	ctx, cancelar := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelar()
	out, err := exec.CommandContext(ctx, bin, "--version").Output()
	return string(out), err
}
