# Contrato del proxy de IA — FinAI (borrador de backend, 24-nov-2026)

Define la superficie que el cliente necesita para dejar de adivinar. Nace de las decisiones del
hilo: unidad **consulta**, tope 10/min por cuenta, prueba de **10 de por vida** por dispositivo,
plan de pago **150 por periodo de facturación**, y la regla «solo consume la respuesta 200 con
contenido».

Nada de esto está desplegado: el endpoint no existe todavía. Este documento es el contrato; el
código va detrás de bandera en la ventana 1-12 dic.

## Endpoint

```
POST /v1/ai:consult
Authorization: Bearer <JWT de entitlement>   (suscripción o pago único)
X-Finai-Trial-Token: <token de Play Integrity>  (solo si es la prueba sin clave)
Content-Type: application/json
```

Cuerpo cerrado (lista cerrada, igual que el resto del backend):

```json
{
  "requestId": "<uuid v4, idempotencia>",
  "mode": "ocr" | "chat",
  "model": "gemini-3.6-flash",       // se valida contra la lista blanca; otro valor -> 400
  "imageBase64": "…",                 // solo ocr; <= 2000 px por lado
  "history": [ {"role":"user|assistant","text":"…"} ],
  "prompt": "…"
}
```

El servidor **no reenvía** el cuerpo: reconstruye la petición a Vertex AI con su propia plantilla,
modelo fijado y topes. Cualquier campo fuera de esta lista devuelve 400.

Topes que impone el servidor (no el cliente):

| Límite | Valor |
|---|---|
| Tokens de entrada | 8.000 |
| Tokens de salida | 1.500 |
| Imagen | 2000 px por lado |
| Consultas por minuto | 10 por cuenta |
| Modelo | lista blanca de uno |

## Respuesta correcta (200)

```json
{
  "text": "…",
  "quota": {
    "window": "trial" | "period",
    "remaining": 7,
    "total": 10,
    "resetsAt": 1767225600000   // epoch ms; null en "trial" (no se renueva)
  }
}
```

`quota` viaja en **cada** respuesta 200: el contador se pinta desde la primera consulta, sin
endpoint aparte.

## Errores y reintentabilidad

Un solo código por motivo, como ya hace el resto del backend (`PURCHASE_NOT_FOUND` vs
`UPSTREAM_AUTH_ERROR`). El cliente decide con `code`, nunca con el status a secas.

| HTTP | `code` | ¿Reintentar? | ¿Consume cuota? | Campos extra |
|---|---|---|---|---|
| 401 | `UNAUTHORIZED` | no | no | — |
| 403 | `ENTITLEMENT_REQUIRED` | no | no | — |
| 403 | `TRIAL_ALREADY_USED` | no | no | — |
| 429 | `QUOTA_EXHAUSTED` | **no** | no | `remaining: 0`, `window`, `resetsAt`, `upgradeAvailable` |
| 429 | `RATE_LIMITED` | sí, tras `Retry-After` | no | `retryAfterSeconds` |
| 400 | `PAYLOAD_LIMIT_EXCEEDED` | no | no | `limit`, `actual` |
| 400 | `INVALID_REQUEST` | no | no | — |
| 503 | `UPSTREAM_UNAVAILABLE` | sí | no | — |
| 504 | `UPSTREAM_TIMEOUT` | sí | no | — |

Cuerpo único:

```json
{
  "error": "Has usado tus 10 consultas de prueba",
  "code": "QUOTA_EXHAUSTED",
  "remaining": 0,
  "window": "trial",
  "resetsAt": null,
  "upgradeAvailable": false
}
```

`upgradeAvailable` es lo que permite que la tarjeta del mensual nazca sólo cuando el SKU exista:
mientras sea `false`, el diálogo de cuota agotada muestra **una** salida («pon tu clave»).

Los 429 llevan además cabecera `Retry-After` (segundos) para clientes que no lean el cuerpo.

## Consumo: reserva y devolución

«Solo consume la respuesta 200 con contenido» se implementa con tres pasos, no con un `if`:

1. **Reserva** en transacción Firestore sobre `quota/{ancla}`: comprueba ventana, decrementa y
   escribe `reservationId = requestId` con `expiresAt = now + 120 s`.
2. **Llamada** a Vertex AI.
3. **Commit** si hay contenido (200) o **devolución** (incrementa) si hubo error, timeout o
   rechazo por tamaño.

Si el proceso muere entre 1 y 3, la reserva caduca a los 120 s y un barrido la devuelve: nadie
pierde una consulta por un despliegue. Dos peticiones simultáneas con la última consulta
disponible no pueden pasar las dos, porque la reserva es atómica.

`requestId` repetido con la misma ancla y dentro de 120 s devuelve la misma reserva (no cobra dos
veces); fuera de esa ventana se trata como nueva.

## Ventanas

- **Pago**: periodo de facturación de Play (`lineItems[0].expiryTime` de `subscriptionsv2`), no
  mes natural. `resetsAt` es esa fecha.
- **Prueba**: de por vida, `resetsAt = null`, `remaining` nunca sube.
- **Gratis con la clave del usuario**: no pasa por el proxy. No hay cuota nuestra que contar.

## Ancla de la prueba

`HMAC-SHA256(ANDROID_ID, pepper)` calculado **en el servidor** con un secreto de Secret Manager.

- Hash simple no vale: `ANDROID_ID` tiene 64 bits y se puede precomputar.
- El pepper impide correlacionar la tabla si algún día se filtra.
- Cadena guardada: `trialAnchor = hex(hmac)`; el `ANDROID_ID` en claro no se guarda ni se registra.
- Junto al ancla se guarda el veredicto de Play Integrity (`PLAY_RECOGNIZED`, `LICENSED`,
  `MEETS_DEVICE_INTEGRITY`) y su fecha; sin veredicto válido no hay prueba.
- Token de integridad: uno por sesión, cacheado por ancla 1 hora.

Consecuencia legal (fuera de mi superficie, pero es contrato): `ANDROID_ID` es «ID de dispositivo»
para Seguridad de Datos. Sin declararlo en el formulario y en la política, la prueba no se publica.

## Estado del contador antes de la primera consulta

El cliente no puede saber `remaining` hasta que llama. Para que una reinstalación no muestre
«10 de 10» cuando quedan 0, `POST /v1/installs:register` (que la app ya llama en el primer
arranque) devolverá también `trialRemaining` y `trialTotal`. Sin endpoint nuevo y sin consulta de
IA gastada.
