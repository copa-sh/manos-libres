package main

import (
	"errors"
	"strings"
	"testing"
)

func TestVersionCLI(t *testing.T) {
	for entrada, quiere := range map[string]string{
		"2.1.3 (Claude Code)\n":      "2.1.3",
		"claude 10.0.12":             "10.0.12",
		"1.2.3-beta.1 (Claude Code)": "1.2.3-beta.1",
		"nada que ver":               "",
	} {
		if got := VersionCLI(entrada); got != quiere {
			t.Errorf("VersionCLI(%q) = %q, quería %q", entrada, got, quiere)
		}
	}
}

func TestComprobarVersionCLI(t *testing.T) {
	salida := func(s string, err error) func(string) (string, error) {
		return func(string) (string, error) { return s, err }
	}
	cfg := Config{BinarioClaude: "claude", VersionClaudeFijada: "2.1.3"}

	if aviso := ComprobarVersionCLI(cfg, salida("2.1.3 (Claude Code)", nil)); aviso != "" {
		t.Errorf("misma versión: aviso inesperado %q", aviso)
	}
	if aviso := ComprobarVersionCLI(cfg, salida("2.2.0 (Claude Code)", nil)); !strings.Contains(aviso, "2.2.0") || !strings.Contains(aviso, "2.1.3") {
		t.Errorf("versión distinta: aviso = %q", aviso)
	}
	cfg.VersionClaudeFijada = ""
	if aviso := ComprobarVersionCLI(cfg, salida("2.2.0", nil)); !strings.Contains(aviso, "CLAUDE_VERSION") {
		t.Errorf("sin fijar: aviso = %q", aviso)
	}
	if aviso := ComprobarVersionCLI(cfg, salida("", errors.New("no existe"))); aviso == "" {
		t.Error("un binario que no arranca debería avisar")
	}
}
