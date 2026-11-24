# Decisión de monetización — 24 nov 2026

Cierra @product-owner-po-product-manager. **Sustituye** la cifra «20/mes gratis» de
`PLAN-INGRESOS.md` §Escalón suscripción (línea 44). Lo demás del plan sigue vigente.

## 1. Las cinco cifras

| Concepto | Decisión |
|---|---|
| **Unidad** | **consulta** = 1 llamada de IA (un escaneo o un mensaje). Una sola palabra en contador, `429`, ficha, correo y UI. |
| **Cuota de pago** | **150 consultas por periodo de facturación** (`lineItems[0].expiryTime`), nunca por mes natural. |
| **Gratis sin clave** | **10 consultas de prueba, de por vida, por instalación atestiguada** (Play Integrity ON). No se renuevan. |
| **Gratis con clave** | **Ilimitado para nosotros** (Google: ~10/min, 1500/día). Coste 0. Es el plan gratuito real de la app. |
| **Precio** | **4,99 €/mes**, un solo plan mensual. Sin plan anual hasta tener D30 medido. |

## 2. Topes y regla de consumo

- 1 imagen ≤ 2000 px · ≤ 8.000 tokens de entrada · ≤ 1.500 de salida · **10/min por cuenta**.
- El exceso **se rechaza y no consume cuota**.
- **Consume cuota sólo la respuesta 200 con contenido.** Un `429`, un 5xx, un timeout o un
  rechazo por tamaño no consumen: el usuario no paga por un fallo nuestro.
- Pool de prueba: **tope global de 150 consultas/día**. Peor caso ≈ $1,74/día (~$52/mes)
  con los topes actuales; uso esperado con ~50 instalaciones/mes ≈ $2/mes.

## 3. Por qué no 20 al mes

`settings_gemini_note` ya le dice al usuario que la clave propia da ~1500 solicitudes/día.
Un «plan gratis» de 20/mes sobre nuestra clave sería una regresión del 98 % que el usuario
puede leer en nuestra propia pantalla — y, peor, canibaliza al que paga: el autónomo ligero
es exactamente quien paga por no configurar nada, y 20/mes le bastan para no pagar nunca.
**10 de por vida demuestran el producto y obligan a elegir: tu clave (gratis) o 4,99 (sin clave).**
Además el abuso queda acotado a 10 por identidad y una sola vez, no 20 cada mes para siempre.

## 4. Semántica del contador (diseño)

- Pago: «Te quedan 43 consultas · se renuevan el 28 de diciembre». **Nunca «este mes».**
- Prueba: «Te quedan 7 de 10». **Sin fecha**: no se renueva.
- Visible desde la primera consulta, no al agotarse. Al llegar a 0: el diálogo de la clave
  con otro título y dos salidas (poner clave · 4,99).

## 5. Puertas (ninguna se mueve por calendario)

1. `verify` con `subscriptionsv2` y estados reales (`ON_HOLD` no es activo).
2. RTDN vivo. Sin notificaciones servimos 30 días después de la baja.
3. Cuota por cuenta ofuscada (`obfuscatedExternalAccountId`), migración por `linkedPurchaseToken`.
4. Proxy: modelo fijado, topes de tokens, sin herramientas libres, **Vertex `europe-west4`**,
   sin persistir documentos ni importes, sin logs de payload.
5. Play Integrity ON para el pool de prueba + tope global + corte `429` con `resetsAt`.
6. Ficha y Seguridad de Datos se publican **con** la función. La declaración es por app, no
   por plan: en cuanto exista «IA incluida», «tus datos no salen del móvil» deja de ser cierto
   para todos. El bloque de advertencia **se reescribe, no se borra**.
7. JWT de entitlement: **24 h para suscriptores**, 30 días para el pago único.
8. **La tarjeta del mensual no aparece en la app hasta que el SKU verifique en producción.**

## 6. Pago único de 5,99 € — dos cosas distintas

- **Corrección inmediata (hoy, sin AAB):** quitar de la hoja de pago «IA sin límites» y
  «soporte prioritario». Son falsas y son reembolso. No forma parte del parche de vc44.
- **Texto que se queda para vc44:** la cláusula «CSV del libro» y la frase del PDF gratis,
  porque la build servida todavía gatea el resumen fiscal y exporta el libro sin muro.
- **Retirada:** por métrica, no por fecha — SKU mensual verificado + 10 suscripciones pagadas
  o 30 días de operación, lo que llegue más tarde. Antes, prueba con la compra del owner:
  verificar → `INACTIVE` → reverificar el mismo `purchaseToken`. La escasez se anuncia en
  paywall, web y contenido; **nunca en la ficha** (política de metadatos de Play).

## 7. Literal del SKU autorizado hoy (116/200)

> Se paga una vez, no se renueva. Incluye exportación a Google Sheets, copias cifradas en tu
> Drive y chat con memoria.

Al servir vc44 se sustituye por el de 192/200 de diseño (añade CSV del libro + PDF gratis).

## 8. Calendario

- **26 nov:** vc42 en Play. Sin esto se pierde el 30-nov y toda la escalera se desplaza.
- **30 nov:** primer euro probado (5,99 €, `paid=1`, `netCents=509`).
- **1-12 dic:** embudo de la pantalla de la clave instrumentado + motor de cuota detrás de bandera.
- **15 dic:** puerta con dos números — abandono en la pantalla de la clave y primer euro. Sin D7.
- **Enero:** vc45 con SKU 4,99, prueba de 10 sin clave, ficha y Seguridad de Datos.
- vc44 se recorta a tres cambios. **La suscripción no entra en vc44.**

## 9. Bloqueos del dueño

1. `gcloud` / clave de `finai-billing-backend` — sin despliegue no hay ni primer euro ni cuota.
2. Compra de prueba de 5,99 € con su cuenta de licencias.
3. «Lanzar a pruebas» en Usuarios y permisos, o subir el bundle.
4. DSA antes del 15-dic, con dirección y teléfono de negocio (no el domicilio).
5. Borrar el export sintético `exports/3f2504e0-…` de la primera sonda.
