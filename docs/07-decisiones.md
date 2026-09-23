# 7. Decisiones

Las diez cuestiones que quedaban abiertas, ya resueltas, más las que se han ido añadiendo
después. Cada punto dice qué se decidió y qué queda por hacer para que la decisión sea real.

---

### 1 · Una sesión narrando, todas avisando

Trabajar con tres repos a la vez es normal en el escritorio. En el móvil, con narración,
hacía falta decidir qué significa «la sesión activa».

**Decisión:** una sesión narra, todas avisan. El protocolo ya está diseñado para varias
—todo va con `sessionId`—, pero la app trata una como *en foco* y las demás mandan `alert`
y `decision.request` con el nombre del proyecto por delante. Se implementa en H6, no antes.

---

### 2 · Un usuario por nodo

Las suscripciones de Claude y ChatGPT son personales
([05 §La suscripción](05-seguridad.md#la-suscripción)), así que la decisión no es técnica.

**Decisión:** un nodo por persona. Si algún día aparece la necesidad de equipo, será con
claves de API propias y facturación por token, nunca compartiendo una suscripción.

---

### 3 · Canal de despertar: QUIC, sin terceros

**Decisión: no hay push y no hay proveedor externo.** Ni FCM ni UnifiedPush. El aviso viaja
por una **única conexión QUIC siempre abierta** entre la app y el nodo, la misma que lleva
el resto del protocolo. No es un canal aparte: `alert`, `decision.request` y `task.done`
son frames normales, y cuando llegan la app vibra en el acto.

Esto sustituye también al WebSocket como transporte principal
([D1](02-arquitectura.md#d1--el-transporte-es-http3-terminado-por-caddy)). Mantener dos
conexiones largas del mismo proceso al mismo servidor no aporta nada.

**Por qué QUIC y no el WebSocket que ya había:**

- **Migración de conexión.** QUIC identifica la conexión por *connection ID*, no por la
  tupla IP/puerto, así que el salto de wifi a datos no la corta. Ahí se va buena parte de
  las reconexiones de un móvil que anda por la calle.
- **Reanudación en 0-RTT** cuando sí se corta de verdad.
- **Sin bloqueo de cabecera de línea.** Un stream de control más uno por sesión: el volcado
  de texto de un repo no atasca la narración de otro.
- **Keepalive más barato** que el ping/pong del WebSocket, que es lo que se paga en batería
  por tener el canal siempre abierto.

**Lo que se pierde, y se asume a sabiendas:** si el sistema mata el proceso, **no hay quien
lo despierte**. Un push de FCM resucita una app muerta; una conexión propia no, porque
muere con el proceso. Se mitiga con el foreground service y su notificación persistente
—que conserva acceso a red en Doze—, pidiendo `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
explícitamente al usuario, y con `START_STICKY`. En fabricantes con matarratas agresivo
(Xiaomi, Huawei, Samsung) hará falta documentar la excepción manual. Es el precio de no
depender de Google Play Services.

**Repliegue.** Hay redes móviles y corporativas que capan UDP/443. Ahí la misma petición se
sirve por HTTP/2 sobre TCP: no es un segundo transporte que mantener, es el mismo protocolo
por una conexión peor.

**La pila, resuelta** — y más barata de lo que parecía cuando se tomó la decisión. La
referencia es `echo-server` y la app de Echo, que ya hacen esto en producción:

- **El servidor no habla QUIC.** Caddy termina HTTP/3 delante y hace de proxy en claro contra
  el nodo. No hace falta ni `node:quic` ni una librería de QUIC: hace falta publicar el 443
  también en UDP y poner `flush_interval -1` en el `reverse_proxy`, o Caddy retiene los
  frames del flujo SSE y el canal no entrega nada hasta cerrarse.
- **En Android, `android.net.http.HttpEngine` con `setEnableQuic(true)`.** Es Cronet como
  módulo de plataforma desde Android 14: sin Play Services, sin dependencia añadida y sin los
  8 MB de `cronet-embedded`. Por debajo de API 34 se cae a HTTP/2 sobre TCP, que sirve igual
  para un `GET` en streaming. Echo lo hace exactamente así.
- **El protocolo toma forma de HTTP**, no de flujo bidireccional: SSE hacia abajo y `POST`
  hacia arriba ([D1](02-arquitectura.md#d1--el-transporte-es-http3-terminado-por-caddy)).
  Es lo que hace que Caddy pueda ir en medio, y de propina la reanudación sale gratis:
  `Last-Event-ID` *es* `sinceSeq`.

---

### 4 · Calidad de voz: el TTS del sistema

**Decisión:** empezar con el TTS del sistema y medirlo en uso real antes de gastar. Si
cansa, la salida intermedia es un TTS neuronal **local en el servidor** (Piper o similar):
mejor voz, sin terceros, a cambio de CPU. El protocolo no cambia — el nodo mandaría audio
en vez de texto, así que `narratable` no debe asumir que se sintetiza en el móvil.

---

### 5 · Entrada por voz: dictado del sistema

**Decisión:** dictado del sistema en H5, con confirmación hablada de lo transcrito antes de
enviar. Whisper en el nodo solo si el dictado falla demasiado con nombres de símbolos y
rutas — que es exactamente lo que uno acaba dictando.

---

### 6 · Idioma: voz fija, sin autodetección

El agente puede responder en español y citar identificadores en inglés en la misma frase.

**Decisión:** voz fija configurada por el usuario, y el filtro narrable del nodo marca los
fragmentos de código y las rutas para que la app los deletree o los pronuncie con voz
inglesa. Nada de detectar idioma automáticamente: cambia de voz a media frase y suena peor
que equivocarse consistentemente.

---

### 7 · Histórico en el móvil: no en la v1

**Decisión:** la app mantiene en memoria la sesión en curso y nada más. Un histórico local
es un sitio más del que se filtra el contenido de tu trabajo, y el nodo ya tiene las
transcripciones de Claude Code para cuando de verdad haga falta.

---

### 8 · Distribución: APK en las releases

**Decisión:** APK firmado en las releases de este repo, más F-Droid si algún día interesa a
alguien de fuera. Play Store no aporta nada a una app que necesita un servidor propio, y sí
aporta un proceso de revisión hostil a los permisos que usamos (`FOREGROUND_SERVICE`,
notificaciones a pantalla completa, botones de medios).

---

### 9 · El reloj: puerta abierta, no ahora

La botonera de decisión en un smartwatch es un encaje casi perfecto: dos opciones, pantalla
entera, vibración en la muñeca, sin sacar el móvil.

**Decisión:** no distraerse con esto hasta H6, pero no cerrar la puerta. Mantener la lógica
de decisión en el nodo y la app como pura presentación hace que una superficie de Wear OS
sea trabajo de interfaz, no rediseño.

---

### 10 · Tareas largas: latido hablado

Si el agente trabaja 40 minutos sin decir nada, la narración calla y el usuario no sabe si
sigue vivo. Narrar la actividad de herramientas sería ruido constante en el oído.

**Decisión:** un latido hablado configurable — tras N minutos de silencio, una frase de
estado corta («sigo, llevo doce minutos, tres ficheros tocados»). El nodo ya tiene la
información en los frames `tool`; es cuestión de resumirla en vez de narrarla en bruto.
**Sigue sin fijar el valor por defecto de N** (¿tres minutos? ¿cinco?); se elige midiendo
en H4, con el intervalo configurable desde el primer día.

---

### 11 · Chat entre tus dispositivos, por el canal de control

Con varios dispositivos emparejados al mismo nodo (H6), hace falta coordinarse entre ellos
sin pasar por el agente: «ya cogí yo esta sesión», «sigue tú desde el portátil». No es chat
de propósito general con otras personas — eso sigue fuera de alcance
([01-vision.md](01-vision.md#lo-que-esta-app-no-es)) — es una nota entre tú y tú mismo.

**Decisión: un frame más en el canal que ya existe, no un sistema aparte.**

- **Sin cifrado extremo a extremo por encima de TLS.** El nodo ya ve en claro el resto del
  protocolo —lo que dice el agente, los prompts, las decisiones— porque es quien lanza el
  agente y no puede evitarlo. Añadir E2E solo al chat protegería un mensaje («recuérdame
  revisar el PR») mientras el nodo sigue viendo cosas bastante más sensibles al lado. La
  superficie que hay que proteger es el transporte, y eso ya lo hace TLS 1.3
  ([05-seguridad.md](05-seguridad.md#transporte)).
- **Un solo nodo, un solo destinatario: todos tus dispositivos a la vez.** No hay concepto de
  «para quién» el mensaje, porque no hay más gente en el nodo — sigue siendo
  [un usuario por nodo](#2--un-usuario-por-nodo). Un mensaje de chat es una difusión a todo
  el que esté escuchando el canal de control, igual que `session.list` o un `alert`.
- **Buffer en anillo en memoria, como el replay de una sesión.** No hay tabla de mensajes ni
  fichero: los últimos `MaxChatBuffer` viajan dentro del `hello` para que reconectar no
  pierda lo que se dijo mientras estabas desconectado, y se pierden al reiniciar el nodo, a
  propósito — el mismo criterio que ya se aplicó al [histórico de sesiones](#7--histórico-en-el-móvil-no-en-la-v1).
- **Sin verificación de identidad del dispositivo, todavía.** `from` es el `X-Dispositivo` que
  ya viaja en cada petición; no está firmado. Es la misma confianza que ya existe para el
  `by` de una decisión resuelta, y se cierra el día que llegue el emparejamiento con
  Ed25519 de H6 ([05-seguridad.md](05-seguridad.md#emparejamiento)).

Detalle en [`server/hub.go`](../server/hub.go) (`EnviarChat`, `HistorialChat`) y
[03-protocolo.md](03-protocolo.md#chatmessage--entre-tus-dispositivos-no-con-el-agente).
