package com.gastos.feature.backup

import com.google.api.services.sheets.v4.model.*
import kotlin.math.ceil

/** Appearance is independently versioned and committed atomically with ownership metadata. */
internal object SheetsAppearancePlan {
    const val VERSION = 1
    const val METADATA_KEY = "finaiAppearance"
    private const val RULE_MARKER = "FinAI appearance v1"
    private val purple = color(.38f, .28f, .61f)
    private val ink = color(.17f, .15f, .21f)
    private val white = color(1f, 1f, 1f)
    private val pale = color(.97f, .96f, .99f)
    private val amber = color(1f, .94f, .80f)

    data class Ownership(val version: Int, val chartId: Int)
    fun ownership(book: Spreadsheet): Ownership? {
        val entries = book.developerMetadata.orEmpty().filter { it.metadataKey == METADATA_KEY }
        check(entries.size <= 1) { "SHEETS_APPEARANCE_AMBIGUOUS" }
        val value = entries.singleOrNull()?.metadataValue ?: return null
        val parts = value.split(":")
        check(parts.size in 2..3 && parts.take(2).all { it.toIntOrNull() != null }) { "SHEETS_APPEARANCE_UNKNOWN" }
        return Ownership(parts[0].toInt(), parts[1].toInt())
    }

    fun requests(book: Spreadsheet, locale: SheetsSchema.LocaleCode, force: Boolean = false): List<Request> {
        val old = ownership(book)
        check(old == null || old.version <= VERSION) { "SHEETS_APPEARANCE_NEWER" }
        if (!force && old?.version == VERSION) return emptyList()
        val operationToken = java.util.UUID.randomUUID().toString()
        val out = mutableListOf<Request>()
        val descriptor = SheetsSchema.descriptor(locale)
        val spanish = locale == SheetsSchema.LocaleCode.ES
        val allSheets = book.sheets.orEmpty()
        for (definition in managedSheetDefinitions(locale)) {
            val sheet = allSheets.single { it.properties.title == definition.title }
            val id = sheet.properties.sheetId
            val headers = cells(sheet).filterKeys { it.first == 0 }.let { row ->
                (0..(row.keys.maxOfOrNull { it.second } ?: -1)).map { column -> row[0 to column]?.userEnteredValue?.stringValue.orEmpty() }
            }
            // Reuse exactly the finance engine's physical column mapping, including personal interleaved columns.
            val plan = SheetsWorkbookPlan(definition, SheetSnapshot(id, definition.title, listOf(headers),
                sheet.properties.gridProperties.rowCount, sheet.properties.gridProperties.columnCount))
            check(plan.columns.all { it < headers.size }) { "SHEETS_APPEARANCE_HEADERS_MISSING" }
            removeOwnedRules(sheet, out)
            var ruleIndex = sheet.conditionalFormats.orEmpty().count { !owned(it) }
            val keyColumn = plan.columns[definition.keyIndex]
            val lastDataRow = cells(sheet).filter { (position, cell) -> position.first > 0 && position.second == keyColumn && cell.userEnteredValue != null }
                .keys.maxOfOrNull { it.first + 1 } ?: 2
            definition.headers.forEachIndexed { logical, name ->
                val physical = plan.columns[logical]
                val body = range(id, 1, lastDataRow, physical, physical + 1)
                out += format(body, CellFormat().setTextFormat(TextFormat().setFontSize(11).setForegroundColor(ink))
                    .setVerticalAlignment("MIDDLE").setWrapStrategy("WRAP"), "textFormat.fontSize,textFormat.foregroundColor,verticalAlignment,wrapStrategy")
                out += format(range(id, 0, 1, physical, physical + 1), CellFormat().setBackgroundColor(purple)
                    .setTextFormat(TextFormat().setBold(true).setFontSize(11).setForegroundColor(white)).setWrapStrategy("WRAP"),
                    "backgroundColor,textFormat,wrapStrategy")
                val pattern = when (logical) {
                    in definition.dateColumns -> "yyyy-mm-dd"
                    in definition.percentColumns -> "0.00\" %\""
                    in definition.rateColumns -> "0.000000"
                    in definition.numberColumns -> "#,##0.00"
                    else -> null
                }
                if (pattern != null) out += format(body, CellFormat().setNumberFormat(NumberFormat()
                    .setType(if (logical in definition.dateColumns) "DATE" else "NUMBER").setPattern(pattern))
                    .setHorizontalAlignment(if (logical in definition.dateColumns) "LEFT" else "RIGHT"), "numberFormat,horizontalAlignment")
                val label = name.toString()
                val technical = label.contains("UUID") || label.contains("JSON")
                val width = when {
                    technical -> 150
                    logical in definition.dateColumns -> 112
                    logical in definition.percentColumns || logical in definition.rateColumns -> 115
                    logical in definition.numberColumns -> 135
                    label.contains("Concept") || label.contains("Descripción") || label.contains("Description") || label.contains("Emisor (") || label.contains("Issuer (") || label.contains("Notas") || label == "Notes" -> 280
                    label.contains("Convers") || label.contains("Conversion") -> 180
                    else -> 155
                }
                out += Request().setUpdateDimensionProperties(UpdateDimensionPropertiesRequest().setRange(DimensionRange()
                    .setSheetId(id).setDimension("COLUMNS").setStartIndex(physical).setEndIndex(physical + 1))
                    .setProperties(DimensionProperties().setPixelSize(width).setHiddenByUser(technical)).setFields("pixelSize,hiddenByUser"))
                out += conditional(range(id,1,null,physical,physical+1), "=AND(ISEVEN(ROW()),\$${columnName(keyColumn)}2<>\"\",N(\"$RULE_MARKER\")=0)", CellFormat().setBackgroundColor(pale), ruleIndex++, book.properties?.locale)
                if (label.contains("Estado Convers") || label.contains("Conversion Status")) {
                    val column = columnName(physical)
                    out += conditional(range(id,1,null,physical,physical+1), "=AND(N(\"$RULE_MARKER\")=0,OR($column" + "2=\"Tasa pendiente\",$column" + "2=\"pending rate\"))",
                        CellFormat().setTextFormat(TextFormat().setBold(true).setForegroundColor(color(.55f,.27f,.05f))).setBackgroundColor(amber),
                        0, book.properties?.locale)
                    ruleIndex++
                }
                out += Request().setUpdateBorders(UpdateBordersRequest().setRange(range(id, 0, 1, physical, physical + 1))
                    .setBottom(Border().setStyle("SOLID").setColor(color(.82f,.78f,.89f))))
            }
            // Keep all active criteria and sort specs intact. Never recreate an existing filter.
            if (sheet.basicFilter == null) out += Request().setSetBasicFilter(SetBasicFilterRequest().setFilter(BasicFilter()
                .setRange(range(id, 0, null, 0, plan.columns.max() + 1))))
            if ((sheet.properties.gridProperties.frozenRowCount ?: 0) < 1) out += Request().setUpdateSheetProperties(UpdateSheetPropertiesRequest()
                .setProperties(SheetProperties().setSheetId(id).setGridProperties(GridProperties().setFrozenRowCount(1))).setFields("gridProperties.frozenRowCount"))
        }
        val summary = allSheets.single { it.properties.title == descriptor.resumenTitle }
        summaryFormats(summary, out, book.properties?.locale)
        val allCharts = allSheets.flatMap { it.charts.orEmpty() }
        val ownedChart = old?.chartId?.let { id -> allCharts.firstOrNull { it.chartId == id } }
        val chartId = ownedChart?.chartId ?: ((allCharts.map { it.chartId } + allSheets.flatMap { it.slicers.orEmpty() }.map { it.slicerId } + allSheets.map { it.properties.sheetId }).maxOrNull() ?: 0) + 1
        val spec = chartSpec(summary.properties.sheetId, spanish)
        if (ownedChart != null) out += Request().setUpdateChartSpec(UpdateChartSpecRequest().setChartId(chartId).setSpec(spec))
        else {
            val anchor = chartAnchor(summary, book)
            if (anchor.first + 18 > summary.properties.gridProperties.rowCount) out += Request().setAppendDimension(AppendDimensionRequest()
                .setSheetId(summary.properties.sheetId).setDimension("ROWS").setLength(anchor.first + 18 - summary.properties.gridProperties.rowCount))
            out += Request().setAddChart(AddChartRequest().setChart(EmbeddedChart().setChartId(chartId).setSpec(spec)
                .setPosition(EmbeddedObjectPosition().setOverlayPosition(OverlayPosition().setAnchorCell(GridCoordinate()
                    .setSheetId(summary.properties.sheetId).setRowIndex(anchor.first).setColumnIndex(anchor.second)).setWidthPixels(600).setHeightPixels(330)))))
        }
        val personalTitle = if (spanish) "Análisis personal" else "Personal analysis"
        val personal = allSheets.single { it.properties.title == personalTitle }
        if (cells(personal).values.none { occupied(it) } && personal.charts.isNullOrEmpty() && personal.slicers.isNullOrEmpty() && personal.merges.isNullOrEmpty()) {
            out += Request().setUpdateCells(UpdateCellsRequest().setStart(GridCoordinate().setSheetId(personal.properties.sheetId).setRowIndex(0).setColumnIndex(0))
                .setRows(listOf(RowData().setValues(listOf(CellData().setUserEnteredValue(ExtendedValue().setStringValue(personalTitle)))),
                    RowData().setValues(listOf(CellData().setUserEnteredValue(ExtendedValue().setStringValue(if (spanish)
                        "Tu espacio: añade fórmulas, gráficos y notas. FinAI conserva este contenido."
                        else "Your space: add formulas, charts and notes. FinAI preserves this content.")))))).setFields("userEnteredValue"))
            out += format(range(personal.properties.sheetId,0,1,0,1), CellFormat().setBackgroundColor(purple)
                .setTextFormat(TextFormat().setFontSize(14).setBold(true).setForegroundColor(white)), "backgroundColor,textFormat")
            out += format(range(personal.properties.sheetId,1,2,0,1), CellFormat().setWrapStrategy("WRAP").setTextFormat(TextFormat().setFontSize(11)), "wrapStrategy,textFormat")
            out += Request().setUpdateDimensionProperties(UpdateDimensionPropertiesRequest().setRange(DimensionRange().setSheetId(personal.properties.sheetId)
                .setDimension("COLUMNS").setStartIndex(0).setEndIndex(1)).setProperties(DimensionProperties().setPixelSize(440)).setFields("pixelSize"))
        }
        val metadata = book.developerMetadata.orEmpty().singleOrNull { it.metadataKey == METADATA_KEY }
        if (metadata == null) out += Request().setCreateDeveloperMetadata(CreateDeveloperMetadataRequest().setDeveloperMetadata(DeveloperMetadata()
            .setMetadataKey(METADATA_KEY).setMetadataValue("$VERSION:$chartId:$operationToken").setVisibility("DOCUMENT").setLocation(DeveloperMetadataLocation().setSpreadsheet(true))))
        else out += Request().setUpdateDeveloperMetadata(UpdateDeveloperMetadataRequest().setDataFilters(listOf(DataFilter()
            .setDeveloperMetadataLookup(DeveloperMetadataLookup().setMetadataId(metadata.metadataId))))
            .setDeveloperMetadata(DeveloperMetadata().setMetadataValue("$VERSION:$chartId:$operationToken")).setFields("metadataValue"))
        return out
    }

    private fun summaryFormats(sheet: Sheet, out: MutableList<Request>, locale: String?) {
        val id = sheet.properties.sheetId
        removeOwnedRules(sheet, out)
        out += format(range(id,0,7,0,2),CellFormat().setTextFormat(TextFormat().setFontSize(11).setForegroundColor(ink)).setWrapStrategy("WRAP"),"textFormat,wrapStrategy")
        out += format(range(id,0,1,0,2),CellFormat().setBackgroundColor(purple).setTextFormat(TextFormat().setFontSize(14).setForegroundColor(white).setBold(true)),"backgroundColor,textFormat")
        listOf(3,4,5).forEach { row ->
            out += format(range(id,row,row+1,0,2),CellFormat().setBackgroundColor(pale).setVerticalAlignment("MIDDLE"),"backgroundColor,verticalAlignment")
            out += format(range(id,row,row+1,1,2),CellFormat().setNumberFormat(NumberFormat().setType("NUMBER").setPattern("#,##0.00"))
                .setHorizontalAlignment("RIGHT").setTextFormat(TextFormat().setFontSize(20).setBold(true).setForegroundColor(purple)),"numberFormat,horizontalAlignment,textFormat")
        }
        listOf(0 to 250,1 to 230).forEach { (column,width) -> out += Request().setUpdateDimensionProperties(UpdateDimensionPropertiesRequest()
            .setRange(DimensionRange().setSheetId(id).setDimension("COLUMNS").setStartIndex(column).setEndIndex(column+1))
            .setProperties(DimensionProperties().setPixelSize(width)).setFields("pixelSize")) }
        out += Request().setUpdateDimensionProperties(UpdateDimensionPropertiesRequest().setRange(DimensionRange().setSheetId(id).setDimension("ROWS").setStartIndex(3).setEndIndex(6))
            .setProperties(DimensionProperties().setPixelSize(54)).setFields("pixelSize"))
        out += conditional(range(id,0,1,1,2),"=AND(\$B\$7>0,N(\"$RULE_MARKER\")=0)",CellFormat().setBackgroundColor(amber)
            .setTextFormat(TextFormat().setBold(true).setForegroundColor(color(.55f,.27f,.05f))),0,locale)
        out += conditional(range(id,3,6,1,2),"=AND(N(\"$RULE_MARKER\")=0,OR(\$B\$7>0,NOT(ISNUMBER(B4))))",CellFormat().setBackgroundColor(amber),1,locale)
        out += format(range(id,8,10,0,2),CellFormat().setBackgroundColor(pale).setTextFormat(TextFormat().setFontSize(11)),"backgroundColor,textFormat")
    }

    private fun chartSpec(id: Int, spanish: Boolean): ChartSpec = ChartSpec().setTitle(if (spanish) "Gastos e ingresos · periodo seleccionado" else "Expenses and income · selected period")
        .setSubtitle(if (spanish) "Consulta «Total parcial» y conversiones pendientes en el resumen. Los datos no disponibles se omiten."
            else "Check Partial total and pending conversions in the summary. Unavailable values are omitted.")
        .setBackgroundColor(white).setBasicChart(BasicChartSpec().setChartType("COLUMN").setLegendPosition("NO_LEGEND").setHeaderCount(0)
            .setDomains(listOf(BasicChartDomain().setDomain(ChartData().setSourceRange(ChartSourceRange().setSources(listOf(range(id,3,5,0,1)))))))
            .setSeries(listOf(BasicChartSeries().setSeries(ChartData().setSourceRange(ChartSourceRange().setSources(listOf(range(id,3,5,1,2)))))
                .setTargetAxis("LEFT_AXIS").setColor(purple).setStyleOverrides(listOf(BasicSeriesDataPointStyleOverride().setIndex(1).setColor(color(.22f,.57f,.43f))))
                .setDataLabel(DataLabel().setType("DATA").setPlacement("OUTSIDE_END")))))

    internal fun chartAnchor(sheet: Sheet, book: Spreadsheet): Pair<Int,Int> {
        val personalCell = cells(sheet).any { (position, cell) -> position.first in 1..18 && position.second in 3..12 && occupied(cell) }
        val objects = sheet.charts.orEmpty().mapNotNull { it.position?.overlayPosition } + sheet.slicers.orEmpty().mapNotNull { it.position?.overlayPosition }
        val hasPersonalRange = (sheet.merges.orEmpty() + book.namedRanges.orEmpty().mapNotNull { it.range }.filter { it.sheetId == sheet.properties.sheetId })
            .any { (it.endColumnIndex ?: Int.MAX_VALUE) > 3 && (it.startColumnIndex ?: 0) < 13 && (it.endRowIndex ?: Int.MAX_VALUE) > 1 && (it.startRowIndex ?: 0) < 19 }
        if (!personalCell && objects.isEmpty() && !hasPersonalRange) return 1 to 3
        val usedCells = cells(sheet).filterValues(::occupied).keys.maxOfOrNull { it.first + 1 } ?: 10
        val heights = buildMap<Int,Int> {
            sheet.data.orEmpty().forEach { grid -> grid.rowMetadata.orEmpty().forEachIndexed { index, value ->
                value.pixelSize?.let { put(index + (grid.startRow ?: 0),it.coerceAtLeast(1)) }
            } }
        }
        val usedObjects = objects.maxOfOrNull { overlay ->
            var row = overlay.anchorCell?.rowIndex ?: 0
            var pixels = (overlay.heightPixels ?: 330) + (overlay.offsetYPixels ?: 0)
            while (pixels > 0) { pixels -= heights[row] ?: 20; row++ }
            row
        } ?: 0
        val usedRanges = (sheet.merges.orEmpty() + book.namedRanges.orEmpty().mapNotNull { it.range }.filter { it.sheetId == sheet.properties.sheetId }).maxOfOrNull { it.endRowIndex ?: sheet.properties.gridProperties.rowCount } ?: 0
        return (maxOf(10,usedCells,usedObjects,usedRanges) + 2) to 0
    }

    private fun occupied(cell: CellData) = cell.userEnteredValue != null || !cell.note.isNullOrEmpty() || cell.userEnteredFormat != null || cell.dataValidation != null
    private fun cells(sheet: Sheet): Map<Pair<Int,Int>,CellData> = buildMap {
        sheet.data.orEmpty().forEach { grid -> grid.rowData.orEmpty().forEachIndexed { row, data -> data.getValues().orEmpty().forEachIndexed { column, cell ->
            put((row + (grid.startRow ?: 0)) to (column + (grid.startColumn ?: 0)), cell)
        } } }
    }
    private fun owned(rule: ConditionalFormatRule) = rule.booleanRule?.condition?.getValues().orEmpty().any { it.userEnteredValue?.contains(RULE_MARKER) == true }
    private fun removeOwnedRules(sheet: Sheet, out: MutableList<Request>) {
        sheet.conditionalFormats.orEmpty().indices.reversed().filter { owned(sheet.conditionalFormats[it]) }.forEach {
            out += Request().setDeleteConditionalFormatRule(DeleteConditionalFormatRuleRequest().setSheetId(sheet.properties.sheetId).setIndex(it))
        }
    }
    private fun conditional(range: GridRange, formula: String, style: CellFormat, index: Int, locale: String?) = Request().setAddConditionalFormatRule(AddConditionalFormatRuleRequest()
        .setIndex(index).setRule(ConditionalFormatRule().setRanges(listOf(range)).setBooleanRule(BooleanRule().setCondition(BooleanCondition()
            .setType("CUSTOM_FORMULA").setValues(listOf(ConditionValue().setUserEnteredValue(SheetsFormulaLocale.adapt(formula,locale))))).setFormat(style))))
    private fun format(range: GridRange, style: CellFormat, fields: String) = Request().setRepeatCell(RepeatCellRequest().setRange(range).setCell(CellData().setUserEnteredFormat(style)).setFields("userEnteredFormat("+fields+")"))
    private fun range(id: Int, firstRow: Int, lastRow: Int?, firstColumn: Int, lastColumn: Int) = GridRange().setSheetId(id).setStartRowIndex(firstRow).setEndRowIndex(lastRow).setStartColumnIndex(firstColumn).setEndColumnIndex(lastColumn)
    private fun color(red: Float, green: Float, blue: Float) = Color().setRed(red).setGreen(green).setBlue(blue)
    private fun columnName(index: Int): String { var value = index + 1; var text = ""; while (value > 0) { value--; text = ('A'.code + value % 26).toChar() + text; value /= 26 }; return text }
}
