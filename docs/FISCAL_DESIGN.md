# Diseño: Sistema Fiscal por País 🇪🇸 → 🌎

## Objetivo
Que FinAI adapte automáticamente IVA, IRPF, formato NIF y moneda según el país del usuario, tanto en el registro de transacciones como en la exportación a Sheets.

---

## 📐 Arquitectura

### Actual (simplificado)
```
Settings → País (ES, MX, AR...) → solo se guarda el código, no tiene efecto real
```

### Futuro
```
Settings → País (ES, MX, AR...) → carga configuración fiscal de la BD → 
  → Aplica IVA/IRPF por defecto al registrar gastos/ingresos
  → Muestra formato NIF correcto en facturas
  → Exporta a Sheets con columnas fiscales del país
  → El chat IA usa los valores por defecto del país
```

---

## 🗂️ Datos a almacenar por país

```kotlin
data class CountryFiscalConfig(
    val paisCodigo: String,        // "ES", "MX", "AR"...
    val nombre: String,            // "España"
    val moneda: String,            // "EUR", "MXN", "ARS"...
    val ivaRates: List<Double>,    // [21.0, 10.0, 4.0]
    val ivaPorDefecto: Double,     // 21.0
    val irpfRate: Double?,         // 15.0 (null si no aplica)
    val irpfPrimerAno: Double?,    // 7.0 (null si no aplica)
    val irpfDescripcion: String,   // "Retención IRPF"
    val nifFormat: String,         // "NNNNNNNNL" (máscara)
    val nifLabel: String,          // "NIF", "RUT", "CUIT"...
    val nombreLeyIva: String,      // "IVA", "IGV", "VAT"...
    val nombreLeyIrpf: String,     // "IRPF", "ISR", "Renta"...
)
```

---

## 👣 Orden de implementación

### Fase 1 — Precargar datos en la BD (ya tenemos la tabla)
Agregar un `RoomDatabase.Callback` que inserte la configuración fiscal de España al crear la DB por primera vez.

### Fase 2 — Aplicar en el registro de gastos/ingresos
- Al crear una factura, el IVA por defecto viene del país seleccionado.
- Si el país tiene IRPF, mostrar campo de retención con el % por defecto.
- El formato NIF se adapta (8 dígitos + letra para España, 11 dígitos para México, etc.)

### Fase 3 — Chat IA consciente del país
El prompt del asistente incluirá: "El usuario tiene configurado España → IVA 21%, IRPF 15%. Usa estos valores."

### Fase 4 — Sheets export con columnas fiscales
Cada país exporta con sus propias cabeceras fiscales.

---

## 📊 Esquema de Sheets por país

```
ESPAÑA:
Gastos: Fecha | Proveedor | Base | IVA% | IVA | IRPF% | IRPF | Total | NIF Emisor
Ingresos: Fecha | Concepto | Devengado | IRPF% | IRPF | Neto | Fuente

MÉXICO:
Gastos: Fecha | Proveedor | Subtotal | IVA% | IVA | Total | RFC
Ingresos: Fecha | Concepto | Subtotal | ISR% | ISR | Neto | Fuente

ARGENTINA:
Gastos: Fecha | Proveedor | Neto | IVA% | IVA | Total | IIBB | CUIT
Ingresos: Fecha | Concepto | Bruto | Retenciones | Neto | Fuente
```

---

## 🚀 Prioridad para producción

| Fase | Descripción | Dependencias |
|---|---|---|
| **0** | 🇪🇸 España completa | Ninguna |
| **1** | Precargar datos al crear DB | Fase 0 |
| **2** | IVA/IRPF por defecto en formularios | Fase 1 |
| **3** | Chat IA consciente del país | Fase 1 |
| **4** | Sheets con columnas fiscales | Fase 1-3 |
| **5** | Siguientes países (MX, AR, CO...) | Fase 0-4 completo |

---

**¿Esto refleja lo que tienes en mente?** Si es así, empezamos por la Fase 0 con España, precargamos los datos, y lo aplicamos en los formularios de gastos/ingresos. ¿Te parece bien este enfoque?
