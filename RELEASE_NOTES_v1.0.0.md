## Versión 1.0.0 — Lanzamiento Inicial

### 🆕 Novedades

- **Asistente IA con Gemini 3.5 Flash** — Chat conversacional con memoria, streaming de respuestas e instrucciones personalizables. Registra gastos e ingresos hablando o escribiendo.
- **Escaneo de facturas por foto** — Toma una foto o selecciona una imagen de la galería; la IA extrae proveedor, total, IVA y productos automáticamente.
- **Comandos por voz** — Registra transacciones y consulta tu balance hablando.
- **Dashboard financiero** — Resumen de ingresos, gastos y balance del mes.
- **Gestión de facturas, productos e ingresos** — CRUD completo con categorías y datos fiscales.
- **Exportación a Google Sheets** — Crea un spreadsheet organizado con 4 hojas: Gastos, Ingresos, Productos y Resumen.
- **Sincronización automática con Sheets** — Cada nuevo gasto o ingreso se refleja automáticamente en el sheet vinculado.
- **Backup en Google Drive** — Copia de seguridad de la base de datos en tu Drive personal.
- **Tema claro/oscuro/sistema** y configuración por país (IVA, IRPF, moneda).
- **Premium (próximamente)** — Desbloquea Sheets export, backup automático y chat IA avanzado.

### 🐛 Correcciones incluidas

- Estabilidad general y rendimiento en el chat con streaming.
- Manejo correcto de ingresos registrados por chat/voz/OCR.
- Validación de API key de Gemini con feedback inmediato.
- Memoria conversacional limitada para evitar uso excesivo de tokens.

### ⚙️ Requisitos

- Android 8.0 (API 26) o superior
- API key gratuita de Gemini (configurable en Ajustes > IA)

---
