# Hoja de ruta única — FinAI

**Actualizado:** 25-nov-2026 · sustituye al roadmap de producto de agosto (que queda al final como catálogo de ideas, sin fechas).
**Fuente de la verdad operativa:** este documento. Los planes parciales (`docs/monetizacion/PLAN-INGRESOS.md`) siguen vigentes en lo que no contradigan esto.

---

## 1. Dónde estamos (verificado, no declarado)

- **Play sirve la versión 41** en producción, interna y alpha. No hay 42 en ninguna pista.
- **AAB de vc42 construido** el 23-nov 11:27: 9.712.662 B, sha256 `58c9a983…f991`. Sin subir.
- **vc43 cerrado en código** (pantalla fiscal, tercer estado del cero, base/IVA/retención en el detalle).
- **vc44 en rama de integración** (`feat/vc44-ingresos`): PDF gratis, CSV con candado, precio fuera de la CTA, código de referido, atribución y métricas de ingresos.
- **Backend: 37 tests, 0 fallos** (última pasada 24-nov 14:17).
- **La API de Play responde con la credencial actual**: lectura de pistas y bundles OK; subida autorizada (400, no 403).

---

## 2. Las dos puertas que no se mueven

1. **42 en Play antes del 30-nov-2026.** Es el único compromiso con fecha. Sin él no hay QA sobre binario real, ni capturas válidas, ni ficha que se pueda sostener.
2. **Compra real de 5,99 € verificada de punta a punta** (Play Integrity incluido, `installId` registrado). Sin esto no se anuncia nada: un muro que no concede Premium es peor que no tener muro.

---

## 3. Fases

### F0 · 25-30 nov — poner el 42 en la calle
- Subir vc42 a la pista **interna** y promover a **cerrada** (decisión del dueño sobre producción).
- QA sobre el binario: retención, exentos y «no sujeto» en la app instalada, no en código.
- Ficha: aplicar el parche de tres textos (frase de pago único, cláusula del libro, frontera gratis/pago) **solo cuando la 42 esté servida**.
- *Prueba de cierre:* la API devuelve vc42 `completed` en la pista y QA firma la pasada.

### F1 · 1-14 dic — primer euro
- Congelar alcance de vc44 en **tres cambios** (precio e insignia fuera de la CTA, botón correcto en la tarjeta de IVA, frase del muro en su sitio). El resto a vc45.
- Build de vc44 desde la rama de integración, `versionCode` y sha256 publicados en el hilo.
- Compra de prueba de 5,99 € con la build release y verificación de la concesión de Premium.
- Rollout interno al 100 %.
- *Prueba de cierre:* registro en backend con `installId` + estado Premium en pantalla + primera venta real.

### F2 · 15-31 dic — vc45: la prueba de 10 consultas
- Proxy de IA con cuota, contador visible desde la primera consulta, `QUOTA_EXHAUSTED` sin reintentos y `trialRemaining` en `installs:register`.
- Política de privacidad publicada y Seguridad de Datos declarada (ID de dispositivo, finalidad cuota/abuso).
- **Cero código de suscripción**: el servidor devuelve `upgradeAvailable:false`.
- *Prueba de cierre:* 10 consultas concedidas, agotadas y contadas sin reinstalar.

### F3 · enero 2027 — decidir con datos
- Medir D7/D30 y conversión real con ~50 descargas y 22 testers (los números que existen; D7 de cohorte no es legible todavía).
- Decisión: vc46 = suscripción **solo si** la prueba demuestra demanda. Si no, el pago único sigue siendo el producto.
- DSA: decidir antes de feb-2027 si la cuenta se declara operador (publica dirección y teléfono de forma permanente).

### F4 · producto, sin fecha
Subcategorías · estadísticas interactivas con drill-down · notificaciones de registro diario y de backup. Siguen siendo buenas ideas y no entra ninguna hasta que F2 esté cerrada.

---

## 4. Reglas de corte

- **WIP cerrado:** la línea de suscripción no recibe commits hasta que el 42 esté en Play y la compra esté verificada.
- **Nada se construye desde una rama en movimiento.** Se congela cabeza, se declara hash, y se integra en rama aparte.
- **La retirada del pago único se dispara por métrica, no por fecha**: 10 suscripciones pagadas o 30 días de operación, lo que llegue más tarde — y el anuncio sale el mismo día del SKU, nunca antes.
- **Instrumentación antes que volumen**: sin `installId` + `utm_source` en el verify no hay canal que se pueda facturar.

---

## 5. Quién hace qué

- **@desarrollador-android** — builds, `versionCode`, subida del AAB, prueba de compra.
- **@desarrollador-backend** — proxy de IA, cuota, atribución, métricas de ingresos.
- **@aso-bot** — ficha y textos, con relectura por API tras cada edición.
- **@qa-engineer** — pasada sobre binario real; sin su firma no se cierra fase.
- **@product-owner-po-product-manager** — alcance, cortes y la palabra final en cada puerta.
