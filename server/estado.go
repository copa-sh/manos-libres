// Persistencia de las sesiones que se pueden reanudar.
//
// Una sesión de agente vive en memoria y muere con el nodo, pero el motor guarda la suya en
// disco y `--resume <id>` la retoma. Lo único que hace falta persistir es, por tanto, la
// correspondencia sesión del nodo → id de sesión del motor, más el cwd donde se abrió (el CLI
// ata la sesión al directorio). El replay de frames no se persiste, a propósito.
package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
)

// MaxRegistros acota el fichero: se conservan las más recientes.
const MaxRegistros = 100

// RegistroSesion es lo que se guarda y lo que sale en `resumable` de `GET /v1/sesiones`.
type RegistroSesion struct {
	SessionID string `json:"sessionId"`
	Titulo    string `json:"title"`
	Cwd       string `json:"cwd"`
	Motor     string `json:"engine"`
	IdMotor   string `json:"engineSessionId"`
	Editado   int64  `json:"updatedAt"`
	// Si la sesión sigue abierta en el nodo. Una abierta no se reanuda: ya está viva.
	Abierta bool `json:"open"`
}

type Almacen struct{ ruta string }

func NuevoAlmacen(ruta string) *Almacen { return &Almacen{ruta: ruta} }

func (a *Almacen) Cargar() (map[string]RegistroSesion, error) {
	out := map[string]RegistroSesion{}
	if a == nil || a.ruta == "" {
		return out, nil
	}
	datos, err := os.ReadFile(a.ruta)
	if errors.Is(err, os.ErrNotExist) {
		return out, nil
	}
	if err != nil {
		return out, err
	}
	var regs []RegistroSesion
	if err := json.Unmarshal(datos, &regs); err != nil {
		return out, err
	}
	for _, r := range regs {
		// Tras un reinicio nada está abierto, diga lo que diga el fichero.
		r.Abierta = false
		out[r.SessionID] = r
	}
	return out, nil
}

func (a *Almacen) Guardar(regs map[string]RegistroSesion) error {
	if a == nil || a.ruta == "" {
		return nil
	}
	lista := make([]RegistroSesion, 0, len(regs))
	for _, r := range regs {
		lista = append(lista, r)
	}
	sort.Slice(lista, func(i, j int) bool { return lista[i].Editado > lista[j].Editado })
	if len(lista) > MaxRegistros {
		lista = lista[:MaxRegistros]
	}
	datos, err := json.MarshalIndent(lista, "", "  ")
	if err != nil {
		return err
	}
	tmp := a.ruta + ".tmp"
	if err := os.MkdirAll(filepath.Dir(a.ruta), 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(tmp, datos, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, a.ruta)
}
