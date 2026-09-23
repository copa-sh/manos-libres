# nodo

Servidor de Manos Libres, en Go y sin dependencias fuera de la biblioteca estándar. Mantiene
las sesiones de agente, las traduce al protocolo `manos-libres/1` y las sirve por un canal
siempre abierto que hace también de canal de aviso: no hay push ni proveedor externo
(ver [docs/07-decisiones.md](../docs/07-decisiones.md#3--canal-de-despertar-quic-sin-terceros)).

## Requisitos

- Go 1.27 o superior.
- **Claude Code instalado y con sesión iniciada** en la máquina: `claude login`. Sin eso el
  CLI no tiene credenciales y el adaptador falla al arrancar. Es también lo que hace que se
  consuma la suscripción en lugar de la API — ver
  [docs/02-arquitectura.md](../docs/02-arquitectura.md#por-qué-el-nodo-envuelve-un-agente-y-no-llama-a-un-modelo).

## Arranque

```bash
cp .env.example .env      # ajusta ALLOWED_ROOTS y DEV_TOKEN
go run .
go test ./...
```

Comprobación rápida sin la app. El canal descendente es SSE, así que `curl` vale de cliente:

```bash
T=$(grep '^DEV_TOKEN=' .env | cut -d= -f2)

# En una terminal: el canal de control, que queda abierto
curl -N -H "Authorization: Bearer $T" http://127.0.0.1:8787/v1/control

# En otra: abrir una sesión, seguirla y mandarle algo
curl -s -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"cwd":"/home/tu-usuario/proyectos/algo"}' http://127.0.0.1:8787/v1/sesiones

curl -N -H "Authorization: Bearer $T" http://127.0.0.1:8787/v1/sesiones/s_1/flujo

curl -s -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"text":"¿qué hay en este directorio?"}' http://127.0.0.1:8787/v1/sesiones/s_1/prompt
```

## El transporte

**El nodo no habla QUIC.** Caddy termina HTTP/3 delante y hace de proxy en claro contra el
nodo, igual que en `echo-server`. Eso decide la forma del protocolo:

| Sentido | Forma | Por qué |
|---|---|---|
| nodo → app | `GET` largo en SSE | El `id:` de cada evento es el `seq`, así que `Last-Event-ID` *es* la reanudación: la app no tiene que llevar la cuenta aparte. |
| app → nodo | `POST` sueltos | Un prompt o una respuesta a una decisión son pequeños y raros. No necesitan canal. |

Hay dos flujos descendentes y no uno, porque `seq` es monótono **por sesión** y
`Last-Event-ID` es uno por flujo: `/v1/control` lleva la lista de sesiones y los despertares
de las que no se están siguiendo, y `/v1/sesiones/{id}/flujo` lleva los frames de una. Sobre
HTTP/3 son streams de la misma conexión QUIC, que es para lo que sirve.

En la app, el cliente es `android.net.http.HttpEngine` con `setEnableQuic(true)`: es Cronet
como módulo de plataforma desde Android 14, sin Play Services y sin dependencia añadida. Por
debajo de API 34 se cae a HTTP/2 sobre TCP, que sirve igual para un GET en streaming.

| Endpoint | Qué hace |
|---|---|
| `GET /salud` | Sin autenticar. Lo único que no la exige. |
| `GET /v1/control` | SSE: `hello` (con el historial de chat), `session.list` y los despertares. |
| `POST /v1/chat` | Manda un mensaje directo a tus otros dispositivos: `{text}`. |
| `GET /v1/sesiones` | Lista de sesiones. |
| `POST /v1/sesiones` | Abre una: `{cwd, title?}`. |
| `GET /v1/sesiones/{id}/flujo` | SSE de la sesión. Reanuda con `Last-Event-ID` o `?desde=`. |
| `POST /v1/sesiones/{id}/prompt` | `{text}` |
| `POST /v1/sesiones/{id}/decision` | `{decisionId, optionIds, always}` |
| `POST /v1/sesiones/{id}/interrupcion` | Termina el turno en curso, no la sesión. |
| `POST /v1/sesiones/{id}/narracion` | Dónde va la voz. |

La autenticación es `Authorization: Bearer`, en cabecera y no en la URL. En `echo-server` va
en la URL porque Echo no deja configurar cabeceras, y el precio es que el token acaba en los
registros de acceso de Caddy; la app de manos-libres es nuestra y no paga ese precio.

## Mapa del código

| Fichero | Responsabilidad |
|---|---|
| `protocolo.go` | Los frames de `manos-libres/1`. Fuente de verdad; la app los replica en Kotlin. |
| `config.go` | Entorno y `.env`, más la comprobación de `ALLOWED_ROOTS`. |
| `main.go` | Arranque y cierre ordenado. |
| `transporte.go` | HTTP: rutas, autenticación y los dos flujos SSE. |
| `hub.go` | Inventario de sesiones y canal de control. |
| `sesion.go` | Una sesión: `seq`, buffer de replay, decisiones pendientes. |
| `narrable.go` | El filtro narrable y el segmentado en frases. |
| `adaptador.go` | El contrato que cumple cualquier motor de agente. |
| `claudecode.go` | El CLI de Claude Code como subproceso, en `stream-json`. |
| `mcp.go` | El servidor MCP del nodo: `permiso` (el antiguo `canUseTool`) y `avisar`. |

## Por qué el CLI y no un SDK

**No hay Agent SDK para Go.** Existe para Python y TypeScript, y la vía oficial desde
cualquier otro lenguaje es ejecutar el CLI como subproceso — que es lo que el SDK hace por
dentro. La correspondencia está en la cabecera de `claudecode.go`; lo que cambia de verdad es
que el servidor MCP deja de ser «en proceso» y pasa a ser un servidor HTTP en loopback al que
el CLI se conecta, para que un permiso pueda esperar a que alguien conteste en el teléfono.

**`--bare` está prohibido**, aunque la documentación lo recomiende para llamadas
programáticas: en modo bare el CLI no lee las credenciales OAuth y exige `ANTHROPIC_API_KEY`,
o sea que dejaría de consumir la suscripción. Como la documentación anuncia que pasará a ser
el defecto de `-p`, la versión del binario se fija en el despliegue y se revisa al subirla.

El precio de no usarlo es que la sesión carga el `CLAUDE.md`, las skills, los MCP y **los
hooks** del directorio de trabajo. Lo primero es deseable —el agente de la calle debe
portarse como el de tu terminal— y lo último es superficie de ejecución que `ALLOWED_ROOTS`
no cubre: un repo bajo una raíz permitida puede traer hooks propios. Se cierra contenerizando
el agente, en H6.

## Despliegue

```bash
cp .env.example .env      # define NODO_DOMINIO, DEV_TOKEN y ALLOWED_ROOTS
docker compose up -d --build
```

El 443 se publica también en UDP: HTTP/3 es QUIC y QUIC es UDP. Sin ese puerto todo sigue
funcionando, pero por TCP y sin h3. El puerto en claro del nodo queda en
`127.0.0.1:$PUERTO_LOCAL`, para que el token no viaje sin cifrar desde otra máquina.

`~/.claude` se monta dentro del contenedor: son las credenciales de la suscripción y no
pertenecen a ninguna imagen.

## Estado

Andamiaje (H0): `go vet` y `go test` pasan limpios, y el nodo arranca y sirve el protocolo,
pero **no se ha probado contra un agente real**. Los `TODO` del código marcan lo que falta;
los `VERIFICAR` marcan sitios donde la forma exacta depende de la versión del CLI —el
mensaje de usuario en `stream-json`, el transporte HTTP de MCP y el contrato de respuesta del
permission-prompt-tool— y hay que comprobarla antes de confiar en ella.
