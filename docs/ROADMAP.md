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

## Fase 3 — Calendario financiero
- Vista mensual con totales por día (ingresos/gastos/balance).
- Navegación entre meses y resaltado del día seleccionado.
- Integrado en el Dashboard como vista de desglose, no como pestaña nueva.

## Fase 4 — Cuentas y menú Más (solo si aporta valor)
- Modelo opcional de cuentas (efectivo, banco, tarjeta, ahorro).
- Cada movimiento pertenece opcionalmente a una cuenta.
- Hub «Más» únicamente cuando existan suficientes herramientas reales.

## No copiar de la app de referencia
- Cuadrícula 3x3 genérica, PC Manager, calculadora suelta, anuncios.
- Nombres/colores/orden de categorías ajenos.
- El calendario como pantalla principal.
