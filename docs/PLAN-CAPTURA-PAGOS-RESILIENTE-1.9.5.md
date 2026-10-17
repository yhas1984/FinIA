# Plan — Captura de pagos que no se duerme (FinAI · próximo build 1.9.5)

> **Encargo de Youssef (15-oct-2026):** buscar una solución **cómoda y simple** para que la captura de pagos con Google Wallet no deje de funcionar en el móvil de los usuarios.
> **Caso real que lo motivó:** su POCO 26077PC53G (Android 16) dejó de detectar pagos durante una noche.

## 1) El fallo real (qué pasó, con evidencias)

Diagnóstico del 15-oct-2026 en el POCO personal de Youssef (vía ADB inalámbrico, ya emparejado):

- El **permiso de acceso a notificaciones estaba concedido** (FinAI figuraba en `enabled_notification_listeners`).
- Google Wallet **sí publicó** las notificaciones de pago del día (registradas en el sistema a las **10:39:55**).
- Pero el servicio `WalletNotificationService` **no estaba vivo** a esa hora: el gestor de batería de POCO/MIUI lo había matado en segundo plano durante la noche (móvil sin reiniciar, uptime 27 días) y **no se re-engancha hasta que se abre la app** (`createTime`=10:43, `createdFromFg=true`).
- **Conclusión:** no era un bug del código 1.9.4 ni de los permisos — es el sistema operativo matando el proceso. **Cualquier usuario de Xiaomi/POCO/OPPO/vivo puede sufrir lo mismo y no tiene forma de saberlo.**

## 2) Principio de diseño (lo "cómodo y simple")

1. **Que se arregle sola, sin tocar nada.** Todo lo que pueda resolverse por código, se resuelve en silencio.
2. **Si necesita ayuda del usuario, 1 toque.** Un único botón cuando sea posible; nunca un manual.
3. **Nunca molestar sin motivo.** Avisos solo cuando de verdad hay un problema; sin nagging repetido.
4. **Cero tecnicismos.** Textos humanos: «tus pagos no se están registrando», no «notification listener unboud».

## 3) Piezas del plan (por prioridad)

### P0 — Auto-reparación silenciosa (sin que el usuario haga nada)
- **a) Guardia de captura** (WorkManager diario + al arrancar la app): comprobar si el listener está conectado; si no → `NotificationListenerService.requestRebind()` con reintentos y backoff. Nuevo componente tipo `CaptureSentinel`, apoyado en `WalletCaptureWorker.kt`.
- **b) Re-enganche en el ciclo del servicio:** implementar `onListenerConnected()` / `onListenerDisconnected()`.
  - Al conectarse → **recuperar pagos recientes** de las notificaciones de Wallet aún activas (`activeNotifications`), con una ventana de recuperación acotada (p. ej. ≤15 min) y **reutilizando el dedup existente por `notificationKey`** (regla de los 90 s / republicación) para **no duplicar jamás**.
  - El guard de ≤2 min actual (`occurredAt` vs. ahora) se mantiene para la captura normal; la ventana ampliada se aplica **solo** en el flujo de reconexión.
  - Al desconectarse → programar reintento (WorkManager, backoff).
- **c) Micro-verificación al abrir la app:** si el servicio no está enganchado, forzar rebind en ese momento (abrir la app es el gesto más común del usuario).

### P0 — Exención de batería **en 1 toque**
- Diálogo nativo `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (un botón «Permitir» del sistema) — en onboarding y/o tarjeta de salud. Comprobar con `PowerManager.isIgnoringBatteryOptimizations()`.
- Esto es lo que faltaba en el caso de hoy: se resuelve con un toque, sin abrir Ajustes a mano.

### P1 — Tarjeta «Salud de la captura» (una pantalla, solo si hay problema)
- Semáforo simple: permiso ✓ / servicio enganchado ✓ / batería sin restricciones ✓ / (si aplica) autostart.
- **Un solo botón: «Reparar»** → ejecuta lo que se pueda por código (rebind, pedir exención…).
- Para lo que no se puede por código (autostart MIUI): 2-3 pasos ilustrados con «Lo hago luego» visible.

### P1 — Xiaomi/POCO/OPPO: guía «2 toques» (una vez, sin repetir)
- Detección por `Build.MANUFACTURER` → UNA pantalla con:
  1. **Inicio automático** → botón que intenta abrir la pantalla del fabricante (Intent; si falla, pasos ilustrados).
  2. **Ahorro de batería → «Sin restricciones».**
- Mostrar como máximo 1 vez/semana y solo si el diagnóstico detectó problema real.

### P2 — Aviso discreto + recuperación del día
- Si el guardia detecta que la captura estuvo caída y hay pagos «probablemente perdidos»: **UN** aviso local («Puede que falten pagos de hoy — toca para revisar»), sin repetir.

### P2 — Log local para soporte
- Guardar los últimos ~50 eventos de captura (post / parse / skip / motivo) en local, visible en «Acerca de» — diagnóstico en 1 mirada sin ADB (como se hizo hoy, pero sin cables ni comandos).

## 4) Lo que NO haremos (para que siga siendo simple)

- ❌ Foreground service permanente con notificación fija (consume batería y molesta).
- ❌ Pedir permisos raros, root o «modo desarrollador» al usuario.
- ❌ Manuales largos o pantallas de ajustes incomprensibles.
- ❌ Prometer el 100 %: Android no lo permite (ni Fintonic ni Monefy lo logran). Lo correcto: **defensa en capas + aviso honesto**.

## 5) Criterios de aceptación (probar en el POCO real de Youssef)

1. La exención de batería se pide desde la app en **1 toque**.
2. Simular muerte del proceso (`adb shell am kill com.gastos.ingresos`) con el permiso puesto → a la siguiente notificación de Wallet la app **recupera** (o el guardia revive en ≤24 h) y el pago aparece **sin duplicar**.
3. Noche completa del POCO sin abrir la app → el pago de la mañana se captura.
4. Tests: los existentes del wallet siguen verdes (21/21) + nuevos de: rebind, recuperación con dedup, tarjeta de salud.
5. UX: en todo el flujo, como máximo **una tarjeta y un botón**.

## 6) Referencias de código (dónde tocar)

- `app/src/main/java/com/gastos/WalletNotificationService.kt` — `onNotificationPosted`, guard de 120 s, `sbn.isOngoing`.
- `app/src/main/java/com/gastos/WalletCaptureWorker.kt` — TAG `finai-wallet-capture`; existe ya recuperación parcial.
- `app/src/androidTest/java/com/gastos/di/WalletCaptureRecoveryTest.kt` — test de recuperación existente.
- `core/domain/src/main/java/com/gastos/domain/model/WalletPaymentParser.kt` — `PACKAGE`, patrones, `notificationKey` (dedup 90 s).
- `app/src/main/java/com/gastos/storage/WalletPaymentStore.kt` y `WalletCaptureNotifier.kt` — store y aviso «Gasto incluido».
- Permiso/diálogo: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `PowerManager.isIgnoringBatteryOptimizations`.
- MIUI intent (verificar en dispositivo): `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`.

## 7) Estimación y siguiente paso

- **P0:** ~1-2 días de trabajo (rama `feat/captura-resiliente` desde main 1.9.4 + PR + tests).
- **P1:** ~1 día. **P2:** ~0,5 día.
- Verificación final: checklist del punto 5 en el POCO de Youssef (ya emparejado por ADB inalámbrico, listo para pruebas instrumentadas).

---
*Documento generado por Hermes Agent el 15-oct-2026, a partir de un fallo real diagnosticado en el móvil del dueño (evidencias en `dumpsys` del POCO). Pendiente de tu OK para convertirlo en rama + PR.*
