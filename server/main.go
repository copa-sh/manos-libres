// Arranque del nodo.
//
// Escucha en loopback a propósito: el TLS y el HTTP/3 los termina Caddy delante (ver el
// Caddyfile y server/README.md). Exponer esto directamente a internet sin TLS sería publicar
// una shell en tu máquina.
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	if err := ejecutar(); err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
}

func ejecutar() error {
	_ = CargarDotEnv(".env")

	cfg, err := CargarConfig()
	if err != nil {
		return err
	}

	// El aviso vale su ruido: con la clave definida se factura por token contra la API en
	// lugar de consumir la suscripción, que es justo lo que este proyecto existe para evitar.
	if os.Getenv("ANTHROPIC_API_KEY") != "" {
		log.Println("aviso: ANTHROPIC_API_KEY está definida, se facturará por token contra " +
			"la API en lugar de usar la suscripción; quítala si no era lo que querías")
	}

	if aviso := ComprobarVersionCLI(cfg, ejecutarVersion); aviso != "" {
		log.Println("aviso: " + aviso)
	}

	// Este contexto es la vida del nodo. Las sesiones cuelgan de él y no de la petición que
	// las abrió, para que el agente siga trabajando después de responder al POST.
	vida, terminar := context.WithCancel(context.Background())
	defer terminar()

	hub := NuevoHub(vida, cfg)
	hub.IniciarLimpieza(vida)
	servidor := &http.Server{
		Addr:    cfg.Host + ":" + cfg.Port,
		Handler: NuevoServidor(cfg, hub).Rutas(),
		// Sin WriteTimeout a propósito: el flujo SSE es una respuesta que no termina nunca,
		// y cualquier plazo de escritura la cortaría. Lo que sí hace falta es plazo para las
		// cabeceras, que es lo que para a un cliente que abre conexiones y luego calla.
		ReadHeaderTimeout: 20 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	paradas := make(chan os.Signal, 1)
	signal.Notify(paradas, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-paradas
		log.Println("cerrando sesiones…")
		hub.Cerrar()
		terminar()
		ctx, cancelar := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancelar()
		_ = servidor.Shutdown(ctx)
	}()

	log.Printf("nodo escuchando en http://%s (protocolo %s)", servidor.Addr, VersionProtocolo)
	log.Printf("raíces permitidas: %v", cfg.RaicesPermitidas)

	if err := servidor.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
