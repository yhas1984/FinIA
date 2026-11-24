# Plan de ingresos FinAI — 24 nov 2026

Fuente única de verdad. Responsable: @project-manager. Cada hito tiene dueño único y prueba verificable.

## Estado verificado en esta máquina (24-nov 10:35 CET)

| Afirmación | Evidencia leída | Veredicto |
|---|---|---|
| Producto `finai_premium` / `premium-one-time` ACTIVE y 5,99 € | API Play (informe @aso-bot) | **CIERTO** |
| Backend de verificación vivo | `/health` 200 (informe @desarrollador-android) | **CIERTO** |
| vc44 entrega insignia de pago único | `feature/settings/src/main/res/values/strings.xml:37` sigue el texto antiguo; 0 cadenas con «pago único» en los 4 clones | **NO ENTREGADO** |
| vc44 invierte el muro | `BackupViewModel.kt:758-760` sigue gateando el resumen fiscal; `ReportProfile.LEDGER` (`FinancialReportWriter.kt:21`) sin gate | **NO ENTREGADO** |
| vc44 envía `installId` en el verify | `BillingEntitlementClient.kt:53-58` no incluye `installId`; el backend sí lo acepta (`InstallRegistry.kt:51`) | **NO ENTREGADO** |
| AAB vc44 verificado con sha256 | No existe: `app/build/outputs/bundle/release/` acaba en `finai-1.9.12-42.aab` (23-nov 11:27); manifiesto fusionado dice `versionCode="42"` | **NO EXISTE** |
| Rama subida para revisión | `git ls-remote --heads origin` no devuelve `feat/vc44-ingresos` | **NO SUBIDA** |

Conclusión: la cadena de cobro está a medias en el extremo del servidor (producto + backend) y entera en el extremo del cliente.

## Hitos

- **H1 · 24-25 nov — cerrar la discrepancia.** @desarrollador-android publica el hash del commit donde vive el cambio o reconoce que es plan. Sin diff en la rama, no hay build que subir. *Prueba: `git show <hash>` con las tres cadenas y el gate invertido.*
- **H2 · 25 nov — ficha y SKU.** @aso-bot aplica sus dos ediciones (frase de pago único en 3 locales + ficha del producto sin promesas falsas) con el «aplica» de @product-owner-po-product-manager. No necesita AAB. *Prueba: relectura por API (`chequea_ficha.py` PASS).*
- **H3 · 25-26 nov — build real + compra de prueba.** vc44 compilado, sha256 y `versionCode` del manifiesto fusionado pegados en el hilo. Compra real de 5,99 € con la build release (decisión del owner) y concesión de Premium verificada de punta a punta, Play Integrity incluido. *Prueba: captura del estado Premium + registro en el backend con `installId`.*
- **H4 · 26-27 nov — subida a pista interna.** Requiere permiso «Lanzar a pruebas» en Usuarios y permisos, o que suba el owner. Rollout interno 100 %. *Prueba: API de Play devuelve la pista con vc44 `completed`.*
- **H5 · 1-15 dic — contenido orgánico.** 25 piezas ya escritas, 0 publicadas. Publicar sin presupuesto: el techo hoy es prueba social (0 valoraciones), no el copy. *Prueba: URLs públicas de las piezas.*
- **H6 · 15 dic — decisión DSA.** Declarar la cuenta como operador o no. Si se declara, Play publica dirección y teléfono de forma permanente. Fecha límite legal feb-2027; se decide con margen. *Decisión del owner.*

## Puertas (nada avanza sin esto)

1. **Sin H3 no hay tráfico de pago.** Con 5,09 € netos por venta, cualquier CAC por encima de ~0,10 €/instalación pierde dinero. Enero es orgánico salvo que la conversión medida lo contradiga.
2. **Sin H4 no se anuncia nada.** Un paywall que no concede Premium es peor que no tener paywall.
3. **Instrumentación antes que volumen.** `installId` + `utm_source` en el verify: sin eso no se sabe qué canal produce un euro y no se puede decidir dónde invertir.

## Decisiones del 24-nov, 11:20 (tras medir la rama)

1. **Congelar la rama candidata.** `feat/vc44-ingresos` ha cambiado de cabeza tres veces en 45 minutos (`affdb8a` 10:30 → `ba2b9e0` 10:56 → `6a9d659` 11:15) y está abierta en el clon compartido. No se construye nada desde una rama en movimiento.
2. **Integración en rama aparte:** `feat/vc44-ingresos-integracion`, creada desde la cabeza congelada. Ahí mergea @desarrollador-backend `finkit-attribution` + `metrics-ingresos`, y de ahí construye @desarrollador-android. Esa rama es el único artefacto que se sube y la única que lleva el `versionCode`.
3. **Nada de rebase sobre la candidata.** Si hay que reordenar, se hace en la rama de integración y se declara la nueva cabeza con hash.
4. **Tren de releases: vc42 primero.** El AAB de vc42 existe desde el 23-nov 11:27 (`58c9a983…f991`, manifiesto `versionCode="42"`) y es el compromiso con fecha (30-nov). Subirlo no depende de ninguna línea nueva y desbloquea la pasada de QA. vc44 va después, en la misma credencial.
5. **Texto publicado del SKU: se queda.** La cláusula «CSV del libro» no se publica mientras `BackupViewModel.kt:637-643` exporte el libro sin gate. Los tres textos pendientes (literal de 192/200 de diseño, cláusula del libro, frontera gratis/pago) forman **un solo parche** que se aplica el día que Play sirva vc44, con @aso-bot como dueño y relanzando la relectura.

## Escalón suscripción (24-nov, 11:40) — construye en diciembre, no vende

Decisión del owner: IA incluida por 4,99 €/mes. Fijado por @product-owner-po-product-manager: unidad **«consulta»**, 150/mes de pago, 20/mes gratis, un solo plan mensual. Packs de operaciones descartados.

1. **Alcance de vc44 recortado a tres cambios** de los seis de diseño: precio e insignia fuera de la CTA, botón correcto en la tarjeta de IVA y frase del muro en su sitio. Los otros dos (estado «Reintentar» y barra fija) pasan a vc45, donde la pantalla Premium se rehace con dos tarjetas. El pago único es el puente al primer euro, no el destino.
2. **La retirada del 5,99 € se dispara por métrica, no por fecha:** solo cuando el SKU mensual verifique en producción y haya al menos 10 suscripciones pagadas o 30 días de operación, lo que llegue más tarde. Y el anuncio de retirada sale el mismo día que el SKU, nunca antes: una escasez anunciada y no cumplida es una promesa falsa.
3. **Precondición de la retirada:** el mismo `purchaseToken` de 5,99 € debe seguir verificando después de pasar el producto a `INACTIVE`. Se prueba con la compra del owner en enero, antes de anunciar nada.
4. **Puerta del 15-dic corregida:** con ~50 descargas y 22 testers no hay D7 legible. La puerta usa dos números que sí existen —abandono en la pantalla de la clave y primer euro— y D7/D30 se miden en febrero.
5. **El plan gratis sin clave necesita el mismo proxy que el de pago.** Si las 20 consultas no exigen clave, la ficha no puede reescribirse antes de que exista el proxy; si la exigen, el bloque de advertencia se queda. Respuesta del PO antes del viernes 27.

## Límite de trabajo y corte de releases (24-nov, 13:00)

- **WIP cerrado:** la línea de suscripción no recibe ni un commit más hasta que el 42 esté en Play y la compra de 5,99 € esté verificada. Lo especificado basta para construir; cada día extra de diseño antes del primer euro es trabajo no cobrado.
- **vc45 = solo la prueba de 10.** Proxy, cuota, contador y diálogo de agotado. Cero código de suscripción, cero tarjeta de mensual: el servidor devuelve `upgradeAvailable:false`, así que el cliente no puede ofrecer lo que no cobra.
- **vc46 = suscripción.** SKU, tarjeta del mensual y retirada del vitalicio. Se abre solo si la prueba demuestra demanda.
- **vc45 no sale sin:** política de privacidad publicada, Seguridad de Datos declarada (ID de dispositivo con finalidad cuota/abuso), contador visible desde la primera consulta, `QUOTA_EXHAUSTED` sin reintentos (una sola petición en el log) y `trialRemaining` en la respuesta de `installs:register` (reinstalar no devuelve consultas).
- **Cadena de privacidad con fechas:** @desarrollador-backend da plazos de conservación el 27; @aso-bot redacta el 30; el titular publica en `finai.leadpilot.es` antes del 11-dic. Sin política viva no hay vc45.
- **Documento canónico:** antes de vc45, una sola rama con código y los tres documentos (`PLAN-INGRESOS`, `DECISION-MONETIZACION`, `CONTRATO-PROXY-IA`) subida a `origin`. Hoy el contrato vive en `fd14cfa`, que no está en `origin`.

## Métrica norte

Primera venta real verificada de punta a punta a 5,99 €. Todo lo demás es preparatorio. Después: instalación → paywall visto → clic → compra verificada, semanal.

## Decisiones del owner (3)

1. **Compra de prueba de 5,99 €** con la build release (reembolsable). Sin ella se empuja tráfico a ciegas.
2. **Permiso de publicación** («Lanzar a pruebas») o subida manual de vc44.
3. **DSA**: declarar como operador o no, con la consecuencia de exponer dirección y teléfono.

Sin estas tres, el equipo puede llegar hasta H3 pero no cobrar.
