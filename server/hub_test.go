package main

import (
	"context"
	"strings"
	"testing"
	"time"
)

func nuevoHubDePrueba(t *testing.T) *Hub {
	t.Helper()
	return NuevoHub(context.Background(), Config{})
}

func TestEnviarChatRechazaTextoVacio(t *testing.T) {
	h := nuevoHubDePrueba(t)
	if _, err := h.EnviarChat("movil", "   "); err == nil {
		t.Error("un mensaje vacío no debería aceptarse")
	}
}

func TestEnviarChatSeRecortaYSeGuardaEnElHistorial(t *testing.T) {
	h := nuevoHubDePrueba(t)

	largo := strings.Repeat("a", MaxChatTexto*2)
	c, err := h.EnviarChat("escritorio", largo)
	if err != nil {
		t.Fatalf("EnviarChat: %v", err)
	}
	if len([]rune(c.Texto)) != MaxChatTexto {
		t.Errorf("Texto tiene %d runas, quería %d", len([]rune(c.Texto)), MaxChatTexto)
	}
	if c.De != "escritorio" {
		t.Errorf("De = %q, quería %q", c.De, "escritorio")
	}

	historial := h.HistorialChat()
	if len(historial) != 1 || historial[0].ChatID != c.ChatID {
		t.Fatalf("HistorialChat = %+v, quería un único mensaje %+v", historial, c)
	}
}

func TestEnviarChatEsMonotonoYSeReparteEnElCanalDeControl(t *testing.T) {
	h := nuevoHubDePrueba(t)

	sub := make(Suscriptor, 8)
	h.SuscribirControl(sub)
	defer h.DesuscribirControl(sub)

	primero, err := h.EnviarChat("movil", "hola")
	if err != nil {
		t.Fatalf("EnviarChat: %v", err)
	}
	segundo, err := h.EnviarChat("escritorio", "qué tal")
	if err != nil {
		t.Fatalf("EnviarChat: %v", err)
	}
	if segundo.Seq() <= primero.Seq() {
		t.Errorf("seq no es monótono: %d luego %d", primero.Seq(), segundo.Seq())
	}

	for _, quiero := range []*Chat{primero, segundo} {
		select {
		case f := <-sub:
			c, ok := f.(*Chat)
			if !ok || c.ChatID != quiero.ChatID {
				t.Fatalf("llegó %+v, quería %+v", f, quiero)
			}
		case <-time.After(time.Second):
			t.Fatal("no llegó el mensaje de chat por el canal de control")
		}
	}
}

func TestHistorialChatSeRecortaAlBufferMaximo(t *testing.T) {
	h := nuevoHubDePrueba(t)
	for i := 0; i < MaxChatBuffer+10; i++ {
		if _, err := h.EnviarChat("movil", "m"); err != nil {
			t.Fatalf("EnviarChat: %v", err)
		}
	}
	if n := len(h.HistorialChat()); n != MaxChatBuffer {
		t.Errorf("HistorialChat tiene %d mensajes, quería el tope de %d", n, MaxChatBuffer)
	}
}
