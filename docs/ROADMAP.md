# Roadmap FinAI

## Objetivo
Crecimiento incremental sin romper: Dashboard · Gastos · Ingresos.

## Fase 1 — Subcategorías (sugeridas y personalizadas)
- Campo opcional `subcategoria` en gastos e ingresos.
- Taxonomía sugerida por defecto (ej. Alimentación → Supermercado).
- Selección con opción de crear personalizada.
- La IA sugiere categoría y subcategoría al registrar.
- Migración Room v9 (columna aditiva en invoices/incomes).
- Compatibilidad: registros antiguos con `subcategoria = null`.
- Actualizar: filtros, formularios de edición, backup/restauración, CSV y Sheets.

## Fase 2 — Estadísticas interactivas
- Selector de mes.
- Alternar Gastos/Ingresos.
- Gráfico circular con porcentajes e importes.
- Drill-down: categoría → subcategoría → movimientos.
- Reutiliza agregaciones ya existentes del Dashboard.

## Fase 3 — Calendario financiero (implementada)
- Vista mensual con totales por día (ingresos/gastos/balance).
- Navegación entre meses y resaltado del día seleccionado.
- Integrado en el Dashboard como widget configurable, no como pestaña nueva.
- Detalle inferior con movimientos del día y acceso a su edición.
- Compatible con layouts personalizados y backups anteriores: el widget nuevo se añade al final de layouts existentes.

## Fase 4 — Notificaciones
- Recordatorio configurable para registrar los gastos del día (hora y frecuencia).
- Avisos de copia de seguridad y sincronización: completada, fallida o pendiente (Drive/Sheets).
- Avisos de suscripción Premium: renovación próxima, caducidad o revocación.
- Permiso `POST_NOTIFICATIONS` (API 33+) y un canal de notificación por tipo.
- Basado en WorkManager (ya presente) y respetuoso con el modo no molestar.
- Opciones en Ajustes para activar o desactivar cada tipo de notificación.

## No copiar de la app de referencia
- Cuadrícula 3x3 genérica, PC Manager, calculadora suelta, anuncios.
- Nombres/colores/orden de categorías ajenos.
- El calendario como pantalla principal.
