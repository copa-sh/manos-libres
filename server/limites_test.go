package main

import (
	"net/http/httptest"
	"testing"
	"time"
)

func TestLimitadorVentanaDeslizante(t *testing.T) {
	l := NuevoLimitador()
	ahora := time.Unix(1000, 0)
	l.ahora = func() time.Time { return ahora }

	for i := 0; i < 3; i++ {
		if ok, _ := l.Permitir("k", 3, time.Minute); !ok {
			t.Fatalf("el evento %d debería caber", i)
		}
	}
	ok, espera := l.Permitir("k", 3, time.Minute)
	if ok || espera <= 0 || espera > time.Minute {
		t.Errorf("cuarto evento: ok=%v espera=%v", ok, espera)
	}
	if ok, _ := l.Permitir("otra", 3, time.Minute); !ok {
		t.Error("las claves son independientes")
	}
	ahora = ahora.Add(61 * time.Second)
	if ok, _ := l.Permitir("k", 3, time.Minute); !ok {
		t.Error("pasada la ventana vuelve a caber")
	}
}

func TestBloqueoAuth(t *testing.T) {
	b := NuevoBloqueoAuth(5, 10*time.Minute)
	ahora := time.Unix(1000, 0)
	b.ahora = func() time.Time { return ahora }

	for i := 0; i < 4; i++ {
		b.Fallo("1.2.3.4")
	}
	if bl, _ := b.Bloqueada("1.2.3.4"); bl {
		t.Error("4 fallos no bloquean")
	}
	b.Fallo("1.2.3.4")
	if bl, resto := b.Bloqueada("1.2.3.4"); !bl || resto != 10*time.Minute {
		t.Errorf("5 fallos: bloqueada=%v resto=%v", bl, resto)
	}
	if bl, _ := b.Bloqueada("5.6.7.8"); bl {
		t.Error("el bloqueo es por IP")
	}
	ahora = ahora.Add(11 * time.Minute)
	if bl, _ := b.Bloqueada("1.2.3.4"); bl {
		t.Error("el bloqueo es temporal")
	}
}

func TestConexionesPorTokenYPorNodo(t *testing.T) {
	c := NuevasConexiones(2, 3)
	a1, a2 := c.Tomar("a"), c.Tomar("a")
	if a1 == nil || a2 == nil {
		t.Fatal("dos conexiones del mismo token caben")
	}
	if c.Tomar("a") != nil {
		t.Error("la tercera del mismo token no cabe")
	}
	b1 := c.Tomar("b")
	if b1 == nil {
		t.Fatal("otro token cabe")
	}
	if c.Tomar("b") != nil {
		t.Error("el nodo ya está en su máximo (3)")
	}
	a1()
	a1() // liberar dos veces no debe contar doble
	if c.Tomar("b") == nil {
		t.Error("al liberar queda hueco")
	}
}

func TestIPClienteSoloConfiaEnXFFDelProxy(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "127.0.0.1:5000"
	r.Header.Set("X-Forwarded-For", "6.6.6.6, 9.9.9.9")
	if got := ipCliente(r); got != "9.9.9.9" {
		t.Errorf("tras el proxy = %q, quería la última entrada", got)
	}
	r.RemoteAddr = "8.8.8.8:5000"
	if got := ipCliente(r); got != "8.8.8.8" {
		t.Errorf("directo = %q: un cliente externo no puede falsear su IP", got)
	}
}
