package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const tokenDePrueba = "token-de-prueba"

type banco struct {
	srv *httptest.Server
	hub *Hub
	cfg Config

	mu        sync.Mutex
	adaptores []*adaptadorFalso
}

func nuevoBanco(t *testing.T, ajustar func(*Config)) *banco {
	t.Helper()
	cfg := Config{
		Token: tokenDePrueba, RaicesPermitidas: []string{"/tmp"}, BufferReplay: 50,
		KeepaliveSegundos: 1, LimiteAbrirMin: 100, LimitePromptMin: 100,
		MaxConexionesToken: 8, MaxConexionesNodo: 20, MaxFallosAuth: 5,
	}
	if ajustar != nil {
		ajustar(&cfg)
	}
	b := &banco{cfg: cfg}
	b.hub = NuevoHub(context.Background(), cfg)
	b.hub.nuevoAdaptador = func() Adaptador {
		a := &adaptadorFalso{}
		b.mu.Lock()
		b.adaptores = append(b.adaptores, a)
		b.mu.Unlock()
		return a
	}
	b.srv = httptest.NewServer(NuevoServidor(cfg, b.hub).Rutas())
	t.Cleanup(b.srv.Close)
	return b
}

func (b *banco) ultimoAdaptador() *adaptadorFalso {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.adaptores[len(b.adaptores)-1]
}

func (b *banco) pedir(t *testing.T, metodo, ruta, cuerpo string, cab ...string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(metodo, b.srv.URL+ruta, strings.NewReader(cuerpo))
	req.Header.Set("Authorization", "Bearer "+tokenDePrueba)
	req.Header.Set("X-Dispositivo", "movil")
	for i := 0; i+1 < len(cab); i += 2 {
		if cab[i+1] == "" {
			req.Header.Del(cab[i])
		} else {
			req.Header.Set(cab[i], cab[i+1])
		}
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { res.Body.Close() })
	return res
}

func (b *banco) abrirSesion(t *testing.T, cuerpo string) string {
	t.Helper()
	if cuerpo == "" {
		cuerpo = `{"cwd":"/tmp/proyecto"}`
	}
	res := b.pedir(t, "POST", "/v1/sesiones", cuerpo)
	if res.StatusCode != http.StatusCreated {
		b, _ := io.ReadAll(res.Body)
		t.Fatalf("abrir sesión: %d %s", res.StatusCode, b)
	}
	var info InfoSesion
	_ = json.NewDecoder(res.Body).Decode(&info)
	return info.SessionID
}

// ── Lector SSE ──────────────────────────────────────────────────────────────────────────

type eventoSSE struct {
	ID    string
	Datos map[string]any
	Raw   string
}

type lectorSSE struct {
	res    *http.Response
	lineas chan string
}

func (b *banco) flujoSSE(t *testing.T, ruta string, cab ...string) *lectorSSE {
	t.Helper()
	res := b.pedir(t, "GET", ruta, "", cab...)
	if res.StatusCode != 200 {
		t.Fatalf("GET %s = %d", ruta, res.StatusCode)
	}
	if ct := res.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("Content-Type = %q", ct)
	}
	l := &lectorSSE{res: res, lineas: make(chan string, 256)}
	go func() {
		sc := bufio.NewScanner(res.Body)
		for sc.Scan() {
			l.lineas <- sc.Text()
		}
		close(l.lineas)
	}()
	return l
}

// siguiente devuelve el próximo evento de datos (saltando comentarios), o nil si el flujo
// terminó.
func (l *lectorSSE) siguiente(t *testing.T) *eventoSSE {
	t.Helper()
	ev := &eventoSSE{}
	limite := time.After(5 * time.Second)
	for {
		select {
		case linea, ok := <-l.lineas:
			if !ok {
				return nil
			}
			switch {
			case strings.HasPrefix(linea, "id: "):
				ev.ID = strings.TrimPrefix(linea, "id: ")
			case strings.HasPrefix(linea, "data: "):
				ev.Raw = strings.TrimPrefix(linea, "data: ")
				if err := json.Unmarshal([]byte(ev.Raw), &ev.Datos); err != nil {
					t.Fatalf("data no es JSON: %q", ev.Raw)
				}
			case linea == "" && ev.Raw != "":
				return ev
			}
		case <-limite:
			t.Fatal("el flujo SSE no entregó nada")
		}
	}
}

func (l *lectorSSE) cerrar() { l.res.Body.Close() }

// ── Autenticación ───────────────────────────────────────────────────────────────────────

func TestSaludNoPideToken(t *testing.T) {
	b := nuevoBanco(t, nil)
	res := b.pedir(t, "GET", "/salud", "", "Authorization", "")
	if res.StatusCode != 200 {
		t.Errorf("/salud = %d", res.StatusCode)
	}
}

func TestSinTokenOConTokenMaloEs401(t *testing.T) {
	b := nuevoBanco(t, func(c *Config) { c.MaxFallosAuth = 1000 }) // sin que el bloqueo interfiera
	rutas := [][2]string{
		{"GET", "/v1/control"}, {"GET", "/v1/sesiones"}, {"POST", "/v1/sesiones"},
		{"POST", "/v1/chat"}, {"GET", "/v1/sesiones/s_1/flujo"}, {"DELETE", "/v1/sesiones/s_1"},
		{"POST", "/v1/sesiones/s_1/prompt"}, {"POST", "/v1/sesiones/s_1/decision"},
		{"POST", "/v1/sesiones/s_1/interrupcion"}, {"POST", "/v1/sesiones/s_1/narracion"},
	}
	for _, r := range rutas {
		for _, auth := range []string{"", "Bearer malo"} {
			res := b.pedir(t, r[0], r[1], "{}", "Authorization", auth)
			if res.StatusCode != http.StatusUnauthorized {
				t.Errorf("%s %s con %q = %d, quería 401", r[0], r[1], auth, res.StatusCode)
				continue
			}
			var e map[string]any
			_ = json.NewDecoder(res.Body).Decode(&e)
			if e["t"] != "error" || e["code"] != "auth_failed" {
				t.Errorf("cuerpo del 401 = %v", e)
			}
		}
	}
}

func TestVersionDeProtocoloDistintaEs400(t *testing.T) {
	b := nuevoBanco(t, nil)
	res := b.pedir(t, "GET", "/v1/sesiones", "", "X-Protocolo", "manos-libres/99")
	if res.StatusCode != http.StatusBadRequest {
		t.Errorf("= %d, quería 400", res.StatusCode)
	}
	res = b.pedir(t, "GET", "/v1/sesiones", "", "X-Protocolo", VersionProtocolo)
	if res.StatusCode != 200 {
		t.Errorf("protocolo correcto = %d", res.StatusCode)
	}
}

func TestFallosDeAuthBloqueanLaIP(t *testing.T) {
	b := nuevoBanco(t, nil)
	for i := 0; i < 5; i++ {
		if res := b.pedir(t, "GET", "/v1/sesiones", "", "Authorization", "Bearer malo"); res.StatusCode != 401 {
			t.Fatalf("intento %d = %d", i, res.StatusCode)
		}
	}
	// Bloqueada: ni siquiera el token correcto vale, y dice cuándo reintentar.
	res := b.pedir(t, "GET", "/v1/sesiones", "")
	if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") == "" {
		t.Errorf("tras 5 fallos = %d (Retry-After %q), quería 429", res.StatusCode, res.Header.Get("Retry-After"))
	}
}

// ── Canal de control y chat ─────────────────────────────────────────────────────────────

func TestControlEmpiezaConHelloYRepartePorChat(t *testing.T) {
	b := nuevoBanco(t, nil)
	b.abrirSesion(t, "")

	c := b.flujoSSE(t, "/v1/control")
	defer c.cerrar()
	hello := c.siguiente(t)
	if hello.Datos["t"] != "hello" || hello.ID != "" {
		t.Fatalf("primer frame = %s (id %q): hello va sin id, no es reanudable", hello.Raw, hello.ID)
	}
	if hello.Datos["protocol"] != VersionProtocolo {
		t.Errorf("hello = %s", hello.Raw)
	}
	if ses, _ := hello.Datos["sessions"].([]any); len(ses) != 1 {
		t.Errorf("hello.sessions = %v", hello.Datos["sessions"])
	}

	res := b.pedir(t, "POST", "/v1/chat", `{"text":"hola desde el móvil"}`)
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("chat = %d", res.StatusCode)
	}
	ev := c.siguiente(t)
	if ev.Datos["t"] != "chat.message" || ev.Datos["text"] != "hola desde el móvil" || ev.Datos["from"] != "movil" {
		t.Errorf("chat.message = %s", ev.Raw)
	}
}

// ── Flujo de sesión y reanudación ───────────────────────────────────────────────────────

func TestFlujoUsaElSeqComoIdYReanudaPorLastEventID(t *testing.T) {
	b := nuevoBanco(t, nil)
	id := b.abrirSesion(t, "")
	s := b.hub.Sesion(id)
	for i := 0; i < 5; i++ {
		s.Emitir(Evento{Clase: EvDelta, MessageID: "m", Texto: "t"})
	}

	f := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo")
	for i := 1; i <= 5; i++ {
		ev := f.siguiente(t)
		if ev.ID != itoa(i) || ev.Datos["seq"] != float64(i) || ev.Datos["t"] != "text.delta" {
			t.Fatalf("evento %d = id %q %s", i, ev.ID, ev.Raw)
		}
	}
	// Llega en vivo lo que se emite después.
	s.Emitir(Evento{Clase: EvMensaje, MessageID: "m2", Rol: "assistant", Tipo: "text", Texto: "listo."})
	if ev := f.siguiente(t); ev.ID != "6" || ev.Datos["t"] != "message" {
		t.Errorf("en vivo = %q %s", ev.ID, ev.Raw)
	}
	f.cerrar()

	// Reconecta como lo haría el cliente: con Last-Event-ID del último visto (3).
	f2 := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo", "Last-Event-ID", "3")
	defer f2.cerrar()
	for _, quiere := range []string{"4", "5", "6"} {
		if ev := f2.siguiente(t); ev.ID != quiere {
			t.Errorf("reanudado: id %q, quería %s", ev.ID, quiere)
		}
	}

	// Y `?desde=` para el primer enganche.
	f3 := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo?desde=5")
	defer f3.cerrar()
	if ev := f3.siguiente(t); ev.ID != "6" {
		t.Errorf("desde=5: primer id %q, quería 6", ev.ID)
	}
}

func itoa(i int) string { return strconv.Itoa(i) }

func TestFlujoAvisaDelHueco(t *testing.T) {
	b := nuevoBanco(t, func(c *Config) { c.BufferReplay = 2 })
	id := b.abrirSesion(t, "")
	s := b.hub.Sesion(id)
	for i := 0; i < 5; i++ {
		s.Emitir(Evento{Clase: EvDelta, MessageID: "m", Texto: "t"})
	}
	f := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo", "Last-Event-ID", "1")
	defer f.cerrar()
	ev := f.siguiente(t)
	if ev.Datos["t"] != "error" || ev.Datos["code"] != "replay_gap" {
		t.Errorf("primer frame = %s, quería error replay_gap", ev.Raw)
	}
	if ev := f.siguiente(t); ev.ID != "4" {
		t.Errorf("tras el aviso llegan los frames que quedan: id %q", ev.ID)
	}
}

func TestFlujoDeSesionInexistenteEs404(t *testing.T) {
	b := nuevoBanco(t, nil)
	if res := b.pedir(t, "GET", "/v1/sesiones/s_99/flujo", ""); res.StatusCode != 404 {
		t.Errorf("= %d", res.StatusCode)
	}
}

func TestKeepaliveEsUnComentarioSSE(t *testing.T) {
	b := nuevoBanco(t, nil)
	id := b.abrirSesion(t, "")
	f := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo")
	defer f.cerrar()
	select {
	case l := <-f.lineas:
		if !strings.HasPrefix(l, ": ping") {
			t.Errorf("línea = %q", l)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no hubo keepalive")
	}
}

// ── Peticiones del cliente ──────────────────────────────────────────────────────────────

func TestPromptDecisionEInterrupcion(t *testing.T) {
	b := nuevoBanco(t, nil)
	id := b.abrirSesion(t, "")
	ruta := "/v1/sesiones/" + id

	if res := b.pedir(t, "POST", ruta+"/prompt", `{"text":"  "}`); res.StatusCode != 400 {
		t.Errorf("prompt vacío = %d", res.StatusCode)
	}
	if res := b.pedir(t, "POST", ruta+"/prompt", `{"text":"haz algo","extra":1}`); res.StatusCode != 400 {
		t.Errorf("campo desconocido = %d", res.StatusCode)
	}
	if res := b.pedir(t, "POST", ruta+"/prompt", `{"text":"haz algo"}`); res.StatusCode != 202 {
		t.Errorf("prompt = %d", res.StatusCode)
	}
	if enviados, _, _ := b.ultimoAdaptador().cuenta(); enviados != 1 {
		t.Errorf("el prompt no llegó al adaptador")
	}

	// Decisión: el agente bloquea, el móvil contesta, el segundo dispositivo recibe 200.
	f := b.flujoSSE(t, ruta+"/flujo")
	defer f.cerrar()
	for ev := f.siguiente(t); ev.Datos["t"] != "session.state" || ev.Datos["state"] != "thinking"; ev = f.siguiente(t) {
	}
	s := b.hub.Sesion(id)
	respuesta := make(chan RespuestaDecision, 1)
	go func() {
		r, _ := s.Decidir(context.Background(), PeticionDecision{Origen: OrigenPermiso, Pregunta: "¿?",
			Opciones: []Opcion{{ID: "allow"}, {ID: "deny"}}})
		respuesta <- r
	}()
	var pide *eventoSSE
	for pide = f.siguiente(t); pide.Datos["t"] != "decision.request"; pide = f.siguiente(t) {
	}
	did := pide.Datos["decisionId"].(string)

	cuerpo := `{"decisionId":"` + did + `","optionIds":["allow"]}`
	if res := b.pedir(t, "POST", ruta+"/decision", cuerpo); res.StatusCode != 204 {
		t.Errorf("decision = %d", res.StatusCode)
	}
	if r := <-respuesta; !contiene(r.OpcionIDs, "allow") {
		t.Errorf("respuesta = %v", r)
	}
	res := b.pedir(t, "POST", ruta+"/decision", `{"decisionId":"`+did+`","optionIds":["deny"]}`)
	var d map[string]any
	_ = json.NewDecoder(res.Body).Decode(&d)
	if res.StatusCode != 200 || d["t"] != "decision.resolved" {
		t.Errorf("segunda respuesta = %d %v", res.StatusCode, d)
	}

	if res := b.pedir(t, "POST", ruta+"/interrupcion", ""); res.StatusCode != 204 {
		t.Errorf("interrupcion = %d", res.StatusCode)
	}
	if _, interrupciones, paradas := b.ultimoAdaptador().cuenta(); interrupciones != 1 || paradas != 0 {
		t.Errorf("interrupciones=%d paradas=%d", interrupciones, paradas)
	}
	if res := b.pedir(t, "POST", ruta+"/narracion", `{}`); res.StatusCode != 204 {
		t.Errorf("narracion = %d", res.StatusCode)
	}
	// La sesión sigue en la lista tras interrumpir.
	if len(b.hub.Listar()) != 1 {
		t.Error("interrumpir no debe cerrar la sesión")
	}
}

func TestAbrirFueraDeLasRaicesEs403(t *testing.T) {
	b := nuevoBanco(t, nil)
	if res := b.pedir(t, "POST", "/v1/sesiones", `{"cwd":"/etc"}`); res.StatusCode != 403 {
		t.Errorf("= %d", res.StatusCode)
	}
}

// ── Cierre de sesiones ──────────────────────────────────────────────────────────────────

func TestCerrarSesionTerminaElFlujoYLaQuitaDeLaLista(t *testing.T) {
	b := nuevoBanco(t, nil)
	id := b.abrirSesion(t, "")
	f := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo")
	c := b.flujoSSE(t, "/v1/control")
	defer c.cerrar()
	c.siguiente(t) // hello

	if res := b.pedir(t, "DELETE", "/v1/sesiones/"+id, ""); res.StatusCode != 204 {
		t.Fatalf("DELETE = %d", res.StatusCode)
	}
	ev := f.siguiente(t)
	if ev.Datos["t"] != "session.closed" || ev.Datos["reason"] != "user" || ev.Datos["sessionId"] != id {
		t.Errorf("frame de cierre = %s", ev.Raw)
	}
	if ev.ID == "" {
		t.Error("session.closed lleva seq")
	}
	if f.siguiente(t) != nil {
		t.Error("el flujo debe terminar tras session.closed")
	}
	if _, _, paradas := b.ultimoAdaptador().cuenta(); paradas != 1 {
		t.Errorf("el motor no se paró")
	}

	// El canal de control anuncia la lista sin la sesión.
	lista := c.siguiente(t)
	if lista.Datos["t"] != "session.list" || len(lista.Datos["sessions"].([]any)) != 0 {
		t.Errorf("control = %s", lista.Raw)
	}
	if res := b.pedir(t, "DELETE", "/v1/sesiones/"+id, ""); res.StatusCode != 404 {
		t.Errorf("cerrar dos veces = %d, quería 404", res.StatusCode)
	}
	if res := b.pedir(t, "POST", "/v1/sesiones/"+id+"/prompt", `{"text":"x"}`); res.StatusCode != 404 {
		t.Errorf("prompt a cerrada = %d", res.StatusCode)
	}
}

func TestCierreAlternativoPorPOST(t *testing.T) {
	b := nuevoBanco(t, nil)
	id := b.abrirSesion(t, "")
	if res := b.pedir(t, "POST", "/v1/sesiones/"+id+"/cierre", ""); res.StatusCode != 204 {
		t.Errorf("= %d", res.StatusCode)
	}
}

func TestLimpiezaDeInactivas(t *testing.T) {
	b := nuevoBanco(t, nil)
	reposo := b.abrirSesion(t, "")
	ocupada := b.abrirSesion(t, "")
	b.hub.Sesion(ocupada).Emitir(Evento{Clase: EvEstado, Estado: EstadoTrabajando})

	// Con un límite en el pasado nada es inactivo.
	if n := b.hub.LimpiarInactivas(time.Now().Add(-time.Hour)); n != 0 {
		t.Errorf("cerró %d con actividad reciente", n)
	}
	n := b.hub.LimpiarInactivas(time.Now().Add(time.Hour))
	if n != 1 || b.hub.Sesion(reposo) != nil || b.hub.Sesion(ocupada) == nil {
		t.Errorf("limpieza: cerradas=%d, reposo=%v ocupada=%v", n, b.hub.Sesion(reposo), b.hub.Sesion(ocupada))
	}
}

// ── Límites ─────────────────────────────────────────────────────────────────────────────

func TestLimiteDeAperturasPorMinuto(t *testing.T) {
	b := nuevoBanco(t, func(c *Config) { c.LimiteAbrirMin = 3 })
	for i := 0; i < 3; i++ {
		b.abrirSesion(t, "")
	}
	res := b.pedir(t, "POST", "/v1/sesiones", `{"cwd":"/tmp/p"}`)
	var e map[string]any
	_ = json.NewDecoder(res.Body).Decode(&e)
	if res.StatusCode != 429 || e["code"] != "rate_limited" || res.Header.Get("Retry-After") == "" {
		t.Errorf("cuarta apertura = %d %v", res.StatusCode, e)
	}
}

func TestLimiteDePromptsPorMinuto(t *testing.T) {
	b := nuevoBanco(t, func(c *Config) { c.LimitePromptMin = 2 })
	id := b.abrirSesion(t, "")
	for i := 0; i < 2; i++ {
		if res := b.pedir(t, "POST", "/v1/sesiones/"+id+"/prompt", `{"text":"x"}`); res.StatusCode != 202 {
			t.Fatalf("prompt %d = %d", i, res.StatusCode)
		}
	}
	if res := b.pedir(t, "POST", "/v1/sesiones/"+id+"/prompt", `{"text":"x"}`); res.StatusCode != 429 {
		t.Errorf("tercer prompt = %d, quería 429", res.StatusCode)
	}
	// Las decisiones y las interrupciones no se limitan: bloquearlas dejaría al agente parado.
	if res := b.pedir(t, "POST", "/v1/sesiones/"+id+"/interrupcion", ""); res.StatusCode != 204 {
		t.Errorf("interrupcion = %d", res.StatusCode)
	}
}

func TestLimiteDeConexionesPorToken(t *testing.T) {
	b := nuevoBanco(t, func(c *Config) { c.MaxConexionesToken = 2 })
	id := b.abrirSesion(t, "")
	c1 := b.flujoSSE(t, "/v1/control")
	c2 := b.flujoSSE(t, "/v1/sesiones/"+id+"/flujo")
	c1.siguiente(t)

	res := b.pedir(t, "GET", "/v1/control", "")
	if res.StatusCode != 429 {
		t.Fatalf("tercera conexión = %d, quería 429", res.StatusCode)
	}
	// Las peticiones sueltas no cuentan como conexión.
	if res := b.pedir(t, "GET", "/v1/sesiones", ""); res.StatusCode != 200 {
		t.Errorf("GET /v1/sesiones = %d", res.StatusCode)
	}

	// Al cerrar una, hay hueco otra vez.
	c2.cerrar()
	deadline := time.Now().Add(3 * time.Second)
	for {
		res := b.pedir(t, "GET", "/v1/sesiones/"+id+"/flujo", "")
		if res.StatusCode == 200 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("la conexión no se liberó: %d", res.StatusCode)
		}
		time.Sleep(20 * time.Millisecond)
	}
	c1.cerrar()
}

// ── Reanudar tras reiniciar el nodo ─────────────────────────────────────────────────────

func TestReanudarConElIdDelMotorTrasReiniciar(t *testing.T) {
	estado := filepath.Join(t.TempDir(), "estado.json")
	ajuste := func(c *Config) { c.FicheroEstado = estado }

	b := nuevoBanco(t, ajuste)
	id := b.abrirSesion(t, `{"cwd":"/tmp/proyecto","title":"mi sesión"}`)
	// El motor publica su id al arrancar: la sesión lo persiste sin esperar al cierre.
	b.ultimoAdaptador().ponerMotor("eng-77")
	b.hub.Sesion(id).Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle})
	if datos, _ := os.ReadFile(estado); !strings.Contains(string(datos), "eng-77") {
		t.Fatalf("estado.json = %s: debería guardar el id del motor en cuanto se conoce", datos)
	}

	// Un reinicio: otro hub sobre el mismo fichero.
	b.hub.Cerrar()
	b2 := nuevoBanco(t, ajuste)

	res := b2.pedir(t, "GET", "/v1/sesiones", "")
	var lista struct {
		Sessions  []InfoSesion     `json:"sessions"`
		Resumable []RegistroSesion `json:"resumable"`
	}
	_ = json.NewDecoder(res.Body).Decode(&lista)
	if len(lista.Sessions) != 0 || len(lista.Resumable) != 1 ||
		lista.Resumable[0].SessionID != id || lista.Resumable[0].Titulo != "mi sesión" {
		t.Fatalf("lista = %+v", lista)
	}

	nueva := b2.abrirSesion(t, `{"resume":"`+id+`"}`)
	if nueva == id {
		t.Error("el id de una sesión nueva no debe reutilizar el viejo")
	}
	op := b2.ultimoAdaptador().opciones
	if op.Reanudar != "eng-77" || op.Cwd != "/tmp/proyecto" {
		t.Errorf("opciones del adaptador = %+v, quería Reanudar=eng-77 y el cwd de antes", op)
	}
	if got := b2.hub.Sesion(nueva).Info().Titulo; got != "mi sesión" {
		t.Errorf("título = %q", got)
	}

	// Ya no es reanudable (está viva) y reanudar otra vez da error.
	if res := b2.pedir(t, "POST", "/v1/sesiones", `{"resume":"`+id+`"}`); res.StatusCode != 404 {
		t.Errorf("reanudar dos veces = %d, quería 404", res.StatusCode)
	}
	if res := b2.pedir(t, "POST", "/v1/sesiones", `{"cwd":"/tmp/x","resume":"s_999"}`); res.StatusCode != 404 {
		t.Errorf("resume inexistente = %d, quería 404", res.StatusCode)
	}
}

func TestReanudarUnaSesionViva409YSinRaizPermitida403(t *testing.T) {
	estado := filepath.Join(t.TempDir(), "estado.json")
	b := nuevoBanco(t, func(c *Config) { c.FicheroEstado = estado })
	id := b.abrirSesion(t, "")
	b.ultimoAdaptador().ponerMotor("eng-1")
	b.hub.Sesion(id).Emitir(Evento{Clase: EvEstado, Estado: EstadoIdle})
	b.hub.registros[id] = RegistroSesion{SessionID: id, IdMotor: "eng-1", Cwd: "/etc"}

	if res := b.pedir(t, "POST", "/v1/sesiones", `{"resume":"`+id+`"}`); res.StatusCode != 409 {
		t.Errorf("reanudar viva = %d, quería 409", res.StatusCode)
	}
	b.hub.CerrarSesion(id, "user")
	b.hub.registros[id] = RegistroSesion{SessionID: id, IdMotor: "eng-1", Cwd: "/etc"}
	if res := b.pedir(t, "POST", "/v1/sesiones", `{"resume":"`+id+`"}`); res.StatusCode != 403 {
		t.Errorf("reanudar con cwd fuera de las raíces = %d, quería 403", res.StatusCode)
	}
}
