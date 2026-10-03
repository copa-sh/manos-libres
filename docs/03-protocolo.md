# 3. Protocolo `manos-libres/1`

HTTP/3 con TLS 1.3, cayendo a HTTP/2 sobre TCP donde UDP no pasa
([D1](02-arquitectura.md#d1--el-transporte-es-http3-terminado-por-caddy)). Los frames
del nodo bajan por un `GET` en SSE; lo que manda la app son `POST` sueltos. Un frame JSON por
evento, sin saltos de línea internos. El campo `t` discrimina el tipo. Los tipos ejecutables viven en
[`server/protocolo.go`](../server/protocolo.go) y son la fuente de verdad; este
documento explica el *porqué* de cada frame.

## Reglas generales

- **Todo frame servidor→app que pertenezca a una sesión lleva `seq`**, un entero monótono
  creciente por sesión que empieza en 1. Los frames de conexión (`hello`, `pong`, `error`
  sin sesión) no lo llevan.
- **El nodo conserva los últimos `REPLAY_BUFFER` frames de cada sesión** (por defecto 500).
  Reconectar reenvía todo lo posterior, en orden — y no hace falta pedirlo: el `seq` viaja
  como `id:` del evento SSE, así que el cliente devuelve el último visto en `Last-Event-ID`
  sin llevar la cuenta por su cuenta. Si
  `sinceSeq` es más viejo que el buffer, el nodo responde con un `message` de resumen y
  `seq` actual, y marca el hueco.
- **Los tamaños se controlan en el nodo.** `text` de un `message` se recorta a 16 KiB; el
  texto íntegro se recupera a petición (fase 2, `message.full`).
- **El cliente ignora frames con `t` desconocido.** Añadir frames no rompe compatibilidad;
  quitarlos o cambiar su forma exige subir a `manos-libres/2`.

## App → nodo

| `t` | Campos | Para qué |
|---|---|---|
| `auth` | `token`, `device`, `protocol` | Primer frame de la conexión. Sin él, el nodo cierra a los 5 s. |
| `sessions.list` | — | Pedir el inventario de sesiones vivas y recientes. |
| `session.open` | `cwd`, `engine?`, `title?` | Arrancar una sesión de agente en un directorio. |
| `session.attach` | `sessionId`, `sinceSeq` | Suscribirse a una sesión y recuperar lo perdido. |
| `session.detach` | `sessionId` | Dejar de recibir sus frames sin cerrarla. |
| `prompt` | `sessionId`, `text` | Mandar texto del usuario al agente. |
| `decision.answer` | `sessionId`, `decisionId`, `optionIds[]`, `always?` | Contestar una decisión pendiente. |
| `interrupt` | `sessionId` | Parar el turno en curso. |
| `narration` | `sessionId`, `at:{messageId, sentence}` | Dónde va la narración. |
| `ping` | — | Latido; el cliente lo manda cada 20 s. |
| `chat` | `text` | Mandar un mensaje directo a tus otros dispositivos, fuera de cualquier sesión. |

`narration` no es telemetría decorativa: es lo que permite que, tras reconectar o tras un
reconectar, el nodo sepa qué ha oído ya el usuario y qué debe volver a narrar.

## Nodo → app

| `t` | Campos | Para qué |
|---|---|---|
| `hello` | `protocol`, `node`, `engines[]`, `sessions[]`, `chatHistory[]` | Respuesta a `auth`. Incluye qué motores hay disponibles y los últimos mensajes de chat. |
| `session.list` | `sessions[]` | Inventario. |
| `session.state` | `state`, `detail?` | `idle` · `thinking` · `working` · `waiting` · `error`. Único frame que la app usa para decidir si «está pasando algo». |
| `text.delta` | `messageId`, `text` | Texto en streaming, **solo para la pantalla**. Nunca se narra. |
| `message` | `messageId`, `role`, `kind`, `text`, `narratable` | Mensaje completo. `narratable.sentences[]` es lo que se narra. |
| `tool` | `toolUseId`, `phase`, `label`, `ok?` | Actividad de herramientas, ya resumida a una etiqueta corta y narrable. |
| `decision.request` | `decisionId`, `prompt`, `spoken`, `options[]`, `multiSelect`, `source`, `deadlineMs?` | Hay que decidir. El agente está bloqueado. |
| `decision.resolved` | `decisionId`, `resolution`, `by?` | La decisión ya no está pendiente (contestada aquí, en otro dispositivo, o cancelada). |
| `alert` | `pattern`, `message?`, `spoken?`, `sound?`, `source` | Llamar la atención. `source: 'agent'` cuando lo pidió el agente con `avisar`. |
| `task.done` | `summary`, `spoken`, `costUsd?`, `durationMs?` | Fin de turno. Genera aviso siempre. |
| `error` | `code`, `message` | Fallo recuperable o fatal. |
| `session.closed` | `reason` | La sesión se cerró (`user`, `idle` o `shutdown`). Es el último frame de su flujo, que el nodo termina justo después. |
| `pong` | — | Latido. |
| `chat.message` | `chatId`, `from`, `text`, `sentAt` | Un mensaje directo entre tus dispositivos. No lleva `sessionId`: viaja por el canal de control. |

## Los tres frames que definen la app

### `message` — el texto, en dos formas

```json
{
  "t": "message", "sessionId": "s_7", "seq": 41,
  "messageId": "m_13", "role": "assistant", "kind": "text",
  "text": "He añadido el segmentador.\n\n```ts\nexport function split(...)\n```\n\nQueda cablearlo en el hub.",
  "narratable": {
    "sentences": [
      "He añadido el segmentador.",
      "Sigue un bloque de código: cuatro líneas de TypeScript.",
      "Queda cablearlo en el hub."
    ],
    "elided": 1
  }
}
```

Dos representaciones del mismo mensaje: `text` es lo que se ve si miras la pantalla,
`narratable.sentences` es lo que se oye. El bloque de código se ha convertido en un anuncio
y `elided` cuenta cuántos fragmentos se sustituyeron, para que la interfaz pueda ofrecer
«leer el código» si alguna vez hace falta.

El índice de un elemento de `sentences` es la coordenada de narración. `(messageId,
sentence)` identifica de forma estable dónde va la voz, y es lo que viaja en el frame
`narration` y lo que el doble toque decrementa.

### `decision.request` — la botonera

```json
{
  "t": "decision.request", "sessionId": "s_7", "seq": 42,
  "decisionId": "d_3", "source": "ask_user_question", "multiSelect": false,
  "prompt": "¿Cómo quieres manejar la reconexión cuando el buffer ya no alcanza?",
  "spoken": "Pregunta: cómo manejar la reconexión. Opción uno, resumen. Opción dos, recargar todo.",
  "options": [
    { "id": "o1", "label": "Resumen", "spoken": "resumen",
      "description": "El nodo manda un mensaje con lo que se perdió.", "tone": "go" },
    { "id": "o2", "label": "Recargar todo", "spoken": "recargar todo",
      "description": "La app descarta su estado y pide la sesión completa.", "tone": "neutral" }
  ]
}
```

Cada opción trae **tres textos distintos a propósito**: `label` es lo que se pinta en la
franja (corto, un verbo o un sustantivo, legible de reojo), `spoken` es lo que se pronuncia
(sin puntuación rara ni identificadores en `snake_case`), y `description` es el detalle que
solo se muestra o se lee si el usuario lo pide. `tone` (`neutral` · `go` · `stop` ·
`danger`) determina color y patrón háptico, y hace que «denegar» se sienta distinto a
«permitir» sin necesidad de leerlo.

`source` distingue de dónde viene la pregunta: `permission` (el flujo de permisos del
agente), `ask_user_question` (el agente preguntando cómo proceder), o `node` (algo nuestro,
como confirmar cerrar una sesión). La app lo usa para el encabezado y para decidir si un
`deadlineMs` tiene sentido.

### `alert` — la vibración

```json
{
  "t": "alert", "sessionId": "s_7", "seq": 43, "source": "agent",
  "pattern": "doble", "sound": "listo",
  "message": "Tests en verde, 41 pasan",
  "spoken": "Tests en verde. Cuarenta y uno pasan."
}
```

Cuatro patrones, y solo cuatro, para que sean distinguibles en el bolsillo: `corto` (algo
pasó), `doble` (tarea terminada), `largo` (necesito que decidas), `urgente` (algo va mal).
El mapeo a milisegundos es del cliente, no del protocolo.

Si la app no está conectada no hay a quién avisar: el frame queda en el buffer de replay y
se entrega al reconectar, con la háptica correspondiente
(ver [04-ux-manos-libres.md](04-ux-manos-libres.md#cuando-la-app-está-en-segundo-plano)).

### `chat.message` — entre tus dispositivos, no con el agente

```json
{
  "t": "chat.message", "seq": 7,
  "chatId": "c_7", "from": "escritorio", "text": "ya lo he revisado, sigue tú",
  "sentAt": 1790158767209
}
```

No pertenece a ninguna sesión —no lleva `sessionId`— porque no es del agente: es un mensaje
tuyo a tus otros dispositivos, para coordinarte contigo mismo («ya cogí yo esta», «déjalo, lo
sigo desde el portátil»). Viaja por el canal de control (`/v1/control`), el mismo que ya
lleva `session.list` y los despertares, y el nodo guarda los últimos `MaxChatBuffer` (200) en
un buffer en anillo que viene incluido en `chatHistory` dentro del `hello`: quien se conecta
no empieza a ciegas, y el historial se pierde al reiniciar el nodo, a propósito — igual que el
resto de lo que el nodo mantiene en memoria (ver [07 §11](07-decisiones.md#11--chat-entre-tus-dispositivos-por-el-canal-de-control)).

## Reconexión, paso a paso

```
1. La app pierde el canal. El narrador sigue leyendo lo que tiene en cola.
2. Reintento con retroceso exponencial (1s, 2s, 4s… tope 30s) mientras haya red.
3. auth → hello.
4. session.attach {sessionId, sinceSeq: <último seq visto>}.
5. El nodo reenvía frames > sinceSeq en orden.
6. La app reconstruye: estado, decisiones aún pendientes (las resueltas llegan con su
   decision.resolved), y encola para narrar solo lo que quede por detrás de su
   última posición de narración conocida.
```

El caso importante es el 6: si el usuario iba oyendo el mensaje 13 y mientras estaba sin
red llegaron los mensajes 14 y 15, al reconectar **no** se repite el 13 desde el principio
ni se salta al 15. La coordenada `(messageId, sentence)` hace que esto sea aritmética, no
heurística.

## Errores

| `code` | Significado | Reacción del cliente |
|---|---|---|
| `auth_failed` | Token inválido o revocado | Volver a emparejar; no reintentar. |
| `protocol_mismatch` | Versión no soportada | Avisar de que hay que actualizar. |
| `session_gone` | La sesión ya no existe | Quitar de la lista, ofrecer abrir otra. |
| `replay_gap` | `sinceSeq` fuera del buffer | Aceptar el resumen y continuar. |
| `engine_error` | El motor de agente falló | Mostrar y ofrecer reintentar el turno. |
| `busy` | Turno en curso, prompt rechazado | Encolar o interrumpir primero. |
