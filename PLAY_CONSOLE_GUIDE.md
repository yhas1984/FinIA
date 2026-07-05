# Google Play Console — Documentación requerida

## 1. 🔗 URL de la Política de Privacidad

La política está en el archivo `PRIVACY_POLICY.html`. Para que Google Play la acepte, debe estar alojada en una URL pública. Tienes dos opciones rápidas:

### Opción A — GitHub Gist (más fácil)
1. Ve a https://gist.github.com
2. Crea un Gist nuevo, pega el contenido de `PRIVACY_POLICY.html`
3. Pulsa "Create public gist"
4. Pulsa "Raw" → copia la URL → esa es tu URL de política

### Opción B — GitHub Pages
1. Sube `PRIVACY_POLICY.html` a tu repo FinIA
2. Ve a Settings → Pages → selecciona `main` / `docs` folder
3. Se publicará en `https://yhas1984.github.io/FinIA/PRIVACY_POLICY.html`

---

## 2. 📝 Declaración de permisos

En Play Console, al subir el AAB te pedirá justificar cada permiso. Copia y pega esto:

### CAMERA
```xml
Permiso: android.permission.CAMERA
Propósito: Tomar fotos de facturas/recibos para escanear y extraer datos financieros con IA.
Justificación: La cámara se abre únicamente cuando el usuario pulsa el botón "Hacer foto" en las pantallas de escaneo o chat. No hay grabación ni acceso en segundo plano. El usuario autoriza explícitamente cada vez mediante el diálogo de permiso de Android.
```

### RECORD_AUDIO
```xml
Permiso: android.permission.RECORD_AUDIO
Propósito: Reconocimiento de voz para registrar comandos financieros (gastos, ingresos, consultas).
Justificación: El micrófono solo se activa cuando el usuario pulsa el botón de voz. No hay grabación en segundo plano. El usuario autoriza explícitamente mediante el diálogo de permiso de Android.
```

### READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE (solo hasta API 28)
```xml
Permiso: android.permission.READ_EXTERNAL_STORAGE
Propósito: No se usa activamente. Se declara por compatibilidad con versiones antiguas de Android (pre-Android 10). En Android 10+ se usa el almacenamiento con ámbito (scoped storage).
```

### INTERNET
```xml
Permiso: android.permission.INTERNET
Propósito: Comunicarse con la API de Gemini 3.5 Flash de Google AI para procesar los mensajes del asistente IA, y con la API de Google Sheets para la exportación opcional de datos.
Nota: No se usa para publicidad, analytics ni ningún otro servicio de terceros.
```

---

## 3. ✅ Declaración de API de Accesibilidad

En Play Console te preguntará:

> **"¿Tu app utiliza la API de Accesibilidad (AccessibilityService)?"**

**Respuesta: NO** 🚫

FinAI **no usa ni declara ningún servicio de accesibilidad**. No necesita esta API para su funcionamiento. Marca simplemente "No" en la declaración.

---

## 4. 💳 Compras integradas (si tienes configurado Premium)

En la sección de **"Productos integrados en la aplicación"** de Play Console:

| Campo | Valor |
|---|---|
| ID del producto | `finai_premium` |
| Tipo | Producto de pago único (Managed product) |
| Título | FinAI Premium |
| Descripción | Desbloquea exportación a Google Sheets, backup automático en Drive y chat IA avanzado. |
| Precio | El que tú elijas (ej. 4,99 €) |

Crea este producto ANTES de publicar, porque el AAB lo referencia.

---

## 5. 📋 Lo que necesitas preparar para la ficha de Play Store

| Requisito | Detalle |
|---|---|
| **Título** | FinAI |
| **Descripción corta** (80 chars) | Tu asistente financiero personal con IA. |
| **Descripción larga** | Texto copiable en el README del proyecto. |
| **Icono** | 512x512px PNG. ¿Tienes logo o quieres uno? |
| **Capturas de pantalla** | Mínimo 2, recomendado 6-8. ¿Necesitas que genere mockups? |
| **Categoría** | Finanzas |
| **Clasificación de contenido** | PEGI 3 / Everyone |
| **Email de contacto** | El que tengas registrado como desarrollador |
| **URL de política de privacidad** | La del paso 1 |
