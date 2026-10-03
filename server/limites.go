// Límites de tasa y de conexiones (docs/05-seguridad.md §Límites de tasa y abuso).
//
// Todo en memoria y con reloj inyectable. Hoy solo hay un token (DEV_TOKEN), así que «por
// token» y «global» coinciden; la clave ya es el token para que el día de los tokens por
// dispositivo (H6) no haya que tocar nada.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// clavePorToken evita guardar el secreto en los mapas (y en un volcado de memoria o un log).
func clavePorToken(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:8])
}

// Limitador es una ventana deslizante: como mucho `max` eventos por `ventana` y clave.
type Limitador struct {
	mu      sync.Mutex
	ahora   func() time.Time
	eventos map[string][]time.Time
}

func NuevoLimitador() *Limitador {
	return &Limitador{ahora: time.Now, eventos: map[string][]time.Time{}}
}

// Permitir registra un evento si cabe. Si no, devuelve cuánto falta para que quepa.
func (l *Limitador) Permitir(clave string, max int, ventana time.Duration) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()

	ahora := l.ahora()
	vivos := l.eventos[clave][:0]
	for _, t := range l.eventos[clave] {
		if ahora.Sub(t) < ventana {
			vivos = append(vivos, t)
		}
	}
	if len(vivos) >= max {
		l.eventos[clave] = vivos
		return false, ventana - ahora.Sub(vivos[0])
	}
	l.eventos[clave] = append(vivos, ahora)
	return true, 0
}

// BloqueoAuth: `max` fallos de autenticación por IP en `ventana` bloquean a esa IP durante
// `ventana`. Mientras dura el bloqueo ni siquiera un token correcto vale.
type BloqueoAuth struct {
	mu      sync.Mutex
	ahora   func() time.Time
	max     int
	ventana time.Duration
	fallos  map[string][]time.Time
	hasta   map[string]time.Time
}

func NuevoBloqueoAuth(max int, ventana time.Duration) *BloqueoAuth {
	return &BloqueoAuth{ahora: time.Now, max: max, ventana: ventana,
		fallos: map[string][]time.Time{}, hasta: map[string]time.Time{}}
}

// Bloqueada dice si la IP está bloqueada y, si lo está, cuánto queda.
func (b *BloqueoAuth) Bloqueada(ip string) (bool, time.Duration) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if h, ok := b.hasta[ip]; ok {
		if resto := h.Sub(b.ahora()); resto > 0 {
			return true, resto
		}
		delete(b.hasta, ip)
		delete(b.fallos, ip)
	}
	return false, 0
}

func (b *BloqueoAuth) Fallo(ip string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	ahora := b.ahora()
	vivos := b.fallos[ip][:0]
	for _, t := range b.fallos[ip] {
		if ahora.Sub(t) < b.ventana {
			vivos = append(vivos, t)
		}
	}
	vivos = append(vivos, ahora)
	b.fallos[ip] = vivos
	if len(vivos) >= b.max {
		b.hasta[ip] = ahora.Add(b.ventana)
	}
}

// Conexiones cuenta los flujos SSE abiertos, por token y en total.
type Conexiones struct {
	mu       sync.Mutex
	porToken map[string]int
	total    int
	maxToken int
	maxNodo  int
}

func NuevasConexiones(maxToken, maxNodo int) *Conexiones {
	return &Conexiones{porToken: map[string]int{}, maxToken: maxToken, maxNodo: maxNodo}
}

// Tomar reserva un hueco. Devuelve la función que lo libera, o nil si no hay.
func (c *Conexiones) Tomar(clave string) func() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.porToken[clave] >= c.maxToken || c.total >= c.maxNodo {
		return nil
	}
	c.porToken[clave]++
	c.total++
	var una sync.Once
	return func() {
		una.Do(func() {
			c.mu.Lock()
			defer c.mu.Unlock()
			c.porToken[clave]--
			c.total--
		})
	}
}

// ipCliente es la IP contra la que se cuentan los fallos de auth. Detrás de Caddy el origen
// de la conexión es siempre el proxy, así que solo si viene de una IP privada o de loopback
// se confía en `X-Forwarded-For`, y se toma la última entrada: la que añadió nuestro proxy,
// no la que haya puesto el cliente.
func ipCliente(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if ip != nil && (ip.IsLoopback() || ip.IsPrivate()) {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			partes := strings.Split(xff, ",")
			if ult := strings.TrimSpace(partes[len(partes)-1]); ult != "" {
				return ult
			}
		}
	}
	return host
}
