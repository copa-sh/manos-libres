# 8. Tareas

La versión granular del [roadmap](06-roadmap.md). Aquel dice qué hace posible cada hito;
esta dice qué hay que escribir. Los hitos son los mismos, así que una tarea que cambia de
hito cambia en los dos sitios.

Estado al escribirla: el nodo en Go sirve el protocolo de punta a punta contra un agente
real, y la app no se ha compilado nunca.

---

## Bloqueante

Sin esto no hay producto, y lo demás se construye encima.

- [ ] **Verificar el MCP de permisos** contra la versión fijada del CLI. Hoy el CLI marca el
      servidor como `failed` y sigue adelante, así que nunca llega a pedir un permiso — y
      pedir permisos *es* la tercera función del producto. Tres puntos, los tres marcados con
      `VERIFICAR` en [`server/mcp.go`](../server/mcp.go): el `protocolVersion` del
      `initialize`, el transporte HTTP de MCP, y el contrato de respuesta del
      permission-prompt-tool (`behavior` allow/deny más el `updatedInput`).
- [ ] **Fijar la versión del CLI** en el despliegue, y avisar al arrancar si la instalada no
      es esa. No es celo: la documentación anuncia que `--bare` pasará a ser el defecto de
      `-p`, y en modo bare el CLI no lee las credenciales OAuth. El día que ocurra, un CLI
      más nuevo dejaría de usar la suscripción y empezaría a facturar por token en silencio,
      sin fallar. Ver la cabecera de [`server/claudecode.go`](../server/claudecode.go).
- [ ] **Test de contrato del `stream-json`**: la forma del mensaje de usuario que se escribe
      por stdin, y las de `system`, `stream_event`, `assistant` y `result`. Funciona hoy; el
      test existe para enterarnos el día que cambie, que es la deriva que ya tenía el
      adaptador en TypeScript.

## Nodo

- [x] **Chat entre dispositivos.** `POST /v1/chat` y el frame `chat.message` por el canal de
      control, con historial en `hello` ([decisión 11](07-decisiones.md#11--chat-entre-tus-dispositivos-por-el-canal-de-control)).
      Falta la pantalla en la app — ver H1 más abajo.
- [ ] Cerrar sesiones: no hay endpoint para cerrar una, ni limpieza de las inactivas.
- [ ] Comprobar que `Interrumpir` (SIGINT) termina el turno y **no** la sesión. Si la mata,
      usar la capacidad `interrupt_receipt_v1` que el CLI anuncia en su `system/init`.
- [ ] Emitir la fase `end` de las herramientas y su `ok`. Hoy solo sale `start`, porque no se
      procesan los `tool_result` que llegan en los mensajes `user`.
- [ ] Deduplicar `session.state`: en una traza real llegan tres `idle` seguidos, gastando
      `seq` y un frame cada uno.
- [ ] Reanudar con `--resume` al reabrir una sesión tras reiniciar el nodo. El
      `engineSessionId` ya se guarda; no lo usa nadie.
- [ ] Límite de tasa y de conexiones por token. [05-seguridad.md](05-seguridad.md) lo promete
      y el código no lo cumple.
- [ ] Tests del transporte: SSE, reanudación por `Last-Event-ID`, autenticación. Hoy solo hay
      de sesión y de narrable.
- [ ] CI: `gofmt -l`, `go vet` y `go test`. Y añadir `go.sum` en cuanto entre la primera
      dependencia — el `Dockerfile` lo tiene comentado a la espera.

## H1 · App mínima

El hito que está en blanco: la app tiene estructura y tipos, pero nunca se ha compilado.

- [ ] Sustituir `AgentClient` (hoy OkHttp y WebSocket) por `android.net.http.HttpEngine` con
      `setEnableQuic(true)`, y `HttpURLConnection` por debajo de API 34.
- [ ] Parser de SSE en Kotlin: líneas `id:` y `data:`, reensamblado, y reconexión mandando el
      último `id` visto en `Last-Event-ID`.
- [ ] Wrapper de Gradle y primera compilación.
- [ ] Pantalla de emparejamiento: URL del nodo y token, en almacenamiento cifrado.
- [ ] `SessionService` con `START_STICKY`, notificación persistente y petición de exención de
      optimización de batería.
- [ ] Pantalla de chat entre dispositivos: lista de `chat.message`, campo de texto que manda
      `ClientFrame.Chat`, y pintar `Hello.chatHistory` al conectar. El protocolo ya está
      cableado en `net/Protocol.kt`; falta la UI y el `TODO(chat)` en `SessionService.kt`.

## H2 · Que el teléfono te busque

- [ ] Los cuatro patrones hápticos con `VibrationEffect.Composition`, cayendo a
      `createWaveform` donde no haya primitivas.
- [ ] Canal de notificaciones y notificación de alta prioridad.
- [ ] Desplegar con Caddy y **probar HTTP/3 desde un móvil de verdad**: que negocie h3, que
      `flush_interval -1` entregue sin retener, y que el canal sobreviva al salto de wifi a
      datos sin cortarse.
- [ ] **Medir cuánto aguanta el foreground service** en Doze y en un fabricante con matarratas
      agresivo. Es la apuesta que hace la [decisión 3](07-decisiones.md#3--canal-de-despertar-quic-sin-terceros)
      y es lo único que puede invalidarla: sin push, un proceso muerto no lo despierta nadie.

## H3 · Decisiones

- [ ] Botonera de franjas a pantalla completa, con tonos y háptica por tono.
- [ ] Acciones en la notificación, para contestar desde la pantalla de bloqueo.
- [ ] Cablear `AskUserQuestion` por el mismo camino que el permiso, y admitir varias preguntas
      por llamada — el adaptador en TypeScript tomaba solo la primera.
- [ ] Reinsistir con el aviso a los 30 s y a los 5 min si la decisión sigue pendiente.

## H4 · Narración

- [ ] Narrador con cola propia sobre `TextToSpeech`, `MediaSession` y foco de audio con
      ducking.
- [ ] Modo A: pantalla negra con los siete gestos.
- [ ] Guardar la posición de narración por dispositivo, para reanudar en la frase exacta.
- [ ] Filtro narrable: tablas, listas numeradas, y marcar los fragmentos de código para que la
      app los deletree ([decisión 6](07-decisiones.md#6--idioma-voz-fija-sin-autodetección)).
- [ ] Etiquetas para las herramientas MCP, que llegan como `mcp__servidor__accion`.
- [ ] **Latido hablado**, y elegir el valor de N midiendo en uso real. Es lo único que quedó
      sin fijar de las diez decisiones
      ([decisión 10](07-decisiones.md#10--tareas-largas-latido-hablado)).

## H5 · Manos libres de verdad

- [ ] Botones del auricular y teclas de volumen por `MediaSession`.
- [ ] `PROXIMITY_SCREEN_OFF_WAKE_LOCK` para el modo bolsillo.
- [ ] Contestar decisiones por N pulsaciones del auricular.
- [ ] Entrada por voz, con confirmación hablada de lo transcrito antes de enviar.

## H6 · Uso diario

- [ ] **Contenerizar el agente.** Ya no es higiene: es lo que cierra el hueco de que una
      sesión ejecute los hooks del repo en el que se abre, que `ALLOWED_ROOTS` no cubre
      ([05 §Superficie del agente](05-seguridad.md#superficie-del-agente)).
- [ ] Emparejamiento por QR, claves Ed25519 en el Keystore y revocación de dispositivos.
- [ ] Varias sesiones en paralelo, con conmutación por voz.
- [ ] `--disallowedTools` con lo que no tiene sentido en remoto.
- [ ] Un segundo motor (Codex CLI), para validar que el contrato de adaptadores aguanta.
