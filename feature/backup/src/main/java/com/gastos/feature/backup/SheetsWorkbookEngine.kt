package com.gastos.feature.backup

import com.gastos.domain.model.*
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import kotlinx.coroutines.runInterruptible

/** A document and its dependent rows are committed in the same atomic Sheets request. */
internal class SheetsWorkbookEngine(private val sheets: Sheets, private val id: String,
    private val conversion: SheetsSchema.ConversionSnapshot,
    private val beforeCommit: suspend () -> Unit = {}) {
    private val descriptor: SheetsSchema.Descriptor = SheetsSchema.descriptor(conversion.locale)
    private val spanish: Boolean = conversion.locale == SheetsSchema.LocaleCode.ES
    private val taxTitle: String = if (spanish) "Impuestos" else "Taxes"
    private val personalTitle: String = if (spanish) "Análisis personal" else "Personal analysis"
    private val definitions = managedSheetDefinitions(conversion.locale)

    suspend fun write(invoices: List<Invoice>, incomes: List<Income>, products: List<Product>, full: Boolean = false,
        initializeFilters: Boolean = false, deletions: Set<SheetsDocumentKey> = emptySet(), initializeWorkbook: Boolean = false) {
        val metadata: Spreadsheet = runInterruptible { sheets.spreadsheets().get(id).setIncludeGridData(false).execute() }
        val requests: MutableList<Request> = mutableListOf()
        if (initializeWorkbook && metadata.sheets.size == 1 && metadata.sheets.single().properties.title != descriptor.recibidasTitle) {
            val properties = metadata.sheets.single().properties
            // Drive creates one empty tab. Reuse it instead of leaving a redundant seventh tab.
            requests += Request().setUpdateSheetProperties(UpdateSheetPropertiesRequest()
                .setProperties(SheetProperties().setSheetId(properties.sheetId).setTitle(descriptor.recibidasTitle)).setFields("title"))
            // Values must still be read by the old title until the atomic rename is committed.
            check(sheets.spreadsheets().values().get(id, "'${properties.title.replace("'", "''")}'").execute().getValues().isNullOrEmpty()) { "SHEETS_LEGACY_IDENTITY_REVIEW" }
            properties.title = descriptor.recibidasTitle
        }
        var nextSheetId: Int = (metadata.sheets.maxOfOrNull { it.properties.sheetId } ?: 0) + 1
        val byTitle: MutableMap<String, SheetProperties> = metadata.sheets.associate { it.properties.title to it.properties }.toMutableMap()
        for (title: String in definitions.map { it.title } + listOf(descriptor.resumenTitle, personalTitle)) {
            if (title !in byTitle) {
                val properties: SheetProperties = SheetProperties().setTitle(title).setSheetId(nextSheetId++)
                    .setGridProperties(GridProperties().setRowCount(1000).setColumnCount(26).setFrozenRowCount(1))
                byTitle[title] = properties
                requests += Request().setAddSheet(AddSheetRequest().setProperties(properties))
            }
        }
        val existingDefinitions: List<ManagedSheet> = definitions.filter { definition -> metadata.sheets.any { it.properties.title == definition.title } &&
            requests.none { it.updateSheetProperties?.properties?.title == definition.title } }
        val valuesByTitle: Map<String, List<List<Any>>> = if (existingDefinitions.isEmpty()) emptyMap() else runInterruptible {
            val result = sheets.spreadsheets().values().batchGet(id).setRanges(existingDefinitions.map { "'${it.title}'" })
                .setValueRenderOption("UNFORMATTED_VALUE").setDateTimeRenderOption("SERIAL_NUMBER").execute()
            existingDefinitions.mapIndexed { index, definition -> definition.title to result.valueRanges[index].getValues().orEmpty() }.toMap()
        }
        val plans: List<SheetsWorkbookPlan> = definitions.map { definition ->
            val properties: SheetProperties = byTitle.getValue(definition.title)
            SheetsWorkbookPlan(definition, SheetSnapshot(properties.sheetId, definition.title, valuesByTitle[definition.title].orEmpty(),
                properties.gridProperties.rowCount, properties.gridProperties.columnCount))
        }
        val expensePlan: SheetsWorkbookPlan = plans[0]
        val incomePlan: SheetsWorkbookPlan = plans[1]
        val productPlan: SheetsWorkbookPlan = plans[2]
        val taxPlan: SheetsWorkbookPlan = plans[3]
        val allIncomes: List<Income> = mergeIncomes(invoices, incomes)
        val changedExpenses = invoices.filter { it.tipo == InvoiceType.GASTO }.map { it.documentUuid }.toSet() +
            deletions.filter { it.target == RemoteSyncTarget.EXPENSE_SHEETS }.map { it.uuid }
        val changedIncomes = allIncomes.map { it.documentUuid }.toSet() +
            deletions.filter { it.target == RemoteSyncTarget.INCOME_SHEETS }.map { it.uuid }
        fun presentParents(plan: SheetsWorkbookPlan): Set<String> = plan.snapshot.rows.drop(1)
            .mapNotNull { it.getOrNull(plan.columns[plan.definition.keyIndex])?.toString()?.takeIf(String::isNotBlank) }.toSet()
        val remoteExpenses = presentParents(expensePlan)
        val remoteIncomes = presentParents(incomePlan)
        val originalInvoices: Map<Long, Invoice> = invoices.associateBy { it.id }
        invoices.filter { it.tipo == InvoiceType.GASTO }.forEach { invoice ->
            expensePlan.upsert(ManagedRecord(SheetsSchema.expenseRow(invoice, conversion), invoice.id,
                mapOf(0 to SheetsSchema.expenseRow(invoice, conversion)[0], 1 to LegacySheetDate(invoice.fecha), 4 to invoice.proveedor, 16 to invoice.total, 17 to invoice.moneda)))
            taxes(taxPlan, invoice.documentUuid, "EXPENSE", "DOCUMENT", invoice.taxes, invoice.moneda, invoice.fecha)
        }
        allIncomes.forEach { income ->
            incomePlan.upsert(ManagedRecord(SheetsSchema.incomeRow(income, conversion), income.id.takeIf { it > 0 },
                mapOf(1 to LegacySheetDate(income.fecha), 0 to income.concepto, 11 to income.monto, 14 to income.moneda)))
            taxes(taxPlan, income.documentUuid, "INCOME", "DOCUMENT", income.taxes, income.moneda, income.fecha)
        }
        products.forEach { product ->
            val invoice: Invoice? = originalInvoices[product.invoiceId]
            val legacy: Income? = allIncomes.firstOrNull { it.id == -product.invoiceId }
            val uuid: String = invoice?.documentUuid ?: legacy?.documentUuid ?: return@forEach
            val provider: String = invoice?.proveedor ?: requireNotNull(legacy).concepto
            val currency: String = invoice?.moneda ?: requireNotNull(legacy).moneda
            val date: Long = invoice?.fecha ?: requireNotNull(legacy).fecha
            productPlan.upsert(ManagedRecord(SheetsSchema.productRow(product, provider, currency, conversion, uuid, date), product.id,
                mapOf(7 to product.invoiceId, 0 to product.descripcion, 1 to product.cantidad, 10 to product.subtotal, 12 to currency)))
            taxes(taxPlan, uuid, if (legacy != null || invoice?.tipo == InvoiceType.INGRESO) "INCOME" else "EXPENSE",
                "PRODUCT:${product.id}", product.taxes, currency, date)
        }
        for (plan: SheetsWorkbookPlan in plans) {
            val parentColumn: Int = when (plan) { productPlan -> 7; taxPlan -> 1; else -> plan.definition.keyIndex }
            plan.clearWhere { row -> row[parentColumn].toString().let { key ->
                val changed = when (plan) {
                    expensePlan -> key in changedExpenses
                    incomePlan -> key in changedIncomes
                    taxPlan -> when (row[2].toString()) {
                        "EXPENSE" -> key in changedExpenses
                        "INCOME" -> key in changedIncomes
                        else -> false
                    }
                    // Legacy product rows have no movement kind. Preserve ambiguous shared parents.
                    else -> (key in changedExpenses && key !in remoteIncomes && key !in changedIncomes) ||
                        (key in changedIncomes && key !in remoteExpenses && key !in changedExpenses) ||
                        (key in changedExpenses && key in changedIncomes)
                }
                key.isNotBlank() && (changed || full && runCatching { java.util.UUID.fromString(key) }.isSuccess)
            } }
            // An old row that cannot be matched must be reviewed, never associated only by a recycled local ID.
            if (full) check(!plan.hasUnmatchedLegacyRows()) { "SHEETS_LEGACY_IDENTITY_REVIEW" }

            requests += plan.finish()
            // Keep the user's active filter criteria and sort order across background syncs.
            if (metadata.sheets.none { it.properties.title == plan.definition.title } || initializeFilters) {
                val filter: BasicFilter = metadata.sheets.firstOrNull { it.properties.title == plan.definition.title }
                    ?.basicFilter?.clone() ?: BasicFilter()
                requests += Request().setSetBasicFilter(SetBasicFilterRequest().setFilter(filter.setRange(GridRange()
                    .setSheetId(plan.snapshot.id).setStartRowIndex(0).setStartColumnIndex(0)
                    .setEndColumnIndex(plan.columns.maxOrNull()!! + 1))))
            }
        }
        requests += summary(byTitle.getValue(descriptor.resumenTitle).sheetId, expensePlan, incomePlan,
            initializeFilters || metadata.sheets.none { it.properties.title == descriptor.resumenTitle }, metadata.properties?.locale)
        val commitToken = java.util.UUID.randomUUID().toString()
        val commitKey = "finaiFinancialCommit"
        if (metadata.developerMetadata.orEmpty().any { it.metadataKey == commitKey }) {
            requests += Request().setUpdateDeveloperMetadata(UpdateDeveloperMetadataRequest()
                .setDataFilters(listOf(DataFilter().setDeveloperMetadataLookup(DeveloperMetadataLookup().setMetadataKey(commitKey))))
                .setDeveloperMetadata(DeveloperMetadata().setMetadataValue(commitToken)).setFields("metadataValue"))
        } else {
            requests += Request().setCreateDeveloperMetadata(CreateDeveloperMetadataRequest().setDeveloperMetadata(
                DeveloperMetadata().setMetadataKey(commitKey).setMetadataValue(commitToken).setVisibility("DOCUMENT")
                    .setLocation(DeveloperMetadataLocation().setSpreadsheet(true))))
        }
        beforeCommit()
        try { runInterruptible { sheets.spreadsheets().batchUpdate(id, BatchUpdateSpreadsheetRequest().setRequests(requests)).execute() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val applied = try { runInterruptible { sheets.spreadsheets().get(id).setIncludeGridData(false).execute() }
                .developerMetadata.orEmpty().any { it.metadataKey == commitKey && it.metadataValue == commitToken } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!applied) throw failure
        }
    }

    private fun taxes(plan: SheetsWorkbookPlan, uuid: String, kind: String, scope: String, taxes: List<DocumentTax>, currency: String, date: Long) {
        taxes.forEachIndexed { index, tax -> plan.upsert(ManagedRecord(listOf("$uuid:$kind:$scope:tax:$index", uuid, kind, scope,
            tax.name ?: "", tax.rate ?: "", tax.base ?: "", tax.amount ?: "", currency, tax.treatment.name, tax.effect.name,
            SheetsSchema.month(date), SheetsSchema.year(date)))) }
    }

    private fun summary(sheetId: Int, expenses: SheetsWorkbookPlan, incomes: SheetsWorkbookPlan, isNew: Boolean, workbookLocale: String?): List<Request> {
        val unavailable: String = if (spanish) "No disponible" else "Unavailable"
        fun expression(plan: SheetsWorkbookPlan, amount: Int, currency: Int, month: Int, year: Int, count: Boolean): String {
            val conditions: String = "(${plan.range(plan.definition.keyIndex)}<>\"\")*IF(\$B\$9=0,1,${plan.range(year)}=\$B\$9)*IF(\$B\$10=0,1,${plan.range(month)}=\$B\$10)"
            val available: String = "($conditions)*ISNUMBER(${plan.range(amount)})*(${plan.range(currency)}=\$B\$3)"
            return if (count) "SUMPRODUCT($conditions)-SUMPRODUCT($available)" else
                "IF(SUMPRODUCT($conditions)=0,0,IF(SUMPRODUCT($available)=0,\"$unavailable\",SUMPRODUCT($available,IFERROR(${plan.range(amount)}*1,0))))"
        }
        fun countRows(plan: SheetsWorkbookPlan, month: Int, year: Int): String =
            "SUMPRODUCT((${plan.range(plan.definition.keyIndex)}<>\"\")*IF(\$B\$9=0,1,${plan.range(year)}=\$B\$9)*IF(\$B\$10=0,1,${plan.range(month)}=\$B\$10))"
        val totalRows: String = countRows(expenses,22,23) + "+" + countRows(incomes,19,20)
        val pending: String = expression(expenses,10,11,22,23,true)+"+"+expression(incomes,2,6,19,20,true)
        val values: List<Pair<Int, List<Any>>> = listOf(
            0 to listOf(descriptor.summaryTitle, SheetFormula("=IF(B7>0,\"${if(spanish) "Total parcial" else "Partial total"}\",\"\")")),
            1 to listOf(descriptor.summaryUpdatedLabel, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())),
            2 to listOf(descriptor.summaryCurrencyLabel, conversion.targetCurrency),
            3 to listOf(descriptor.summaryExpensesLabel, SheetFormula("="+expression(expenses,10,11,22,23,false))),
            4 to listOf(descriptor.summaryIncomeLabel, SheetFormula("="+expression(incomes,2,6,19,20,false))),
            5 to listOf(descriptor.summaryBalanceLabel, SheetFormula("=IF(($totalRows)=0,0,IF(($totalRows)=B7,\"$unavailable\",N(B5)-N(B4)))")),
            6 to listOf(descriptor.summaryPendingLabel, SheetFormula("=$pending")),
            8 to listOf(if (spanish) "Año (0 = todos)" else "Year (0 = all)"),
            9 to listOf(if (spanish) "Mes (0 = todos)" else "Month (0 = all)"))
        val output: MutableList<Request> = values.map { (row, cells) ->
            Request().setUpdateCells(UpdateCellsRequest().setStart(GridCoordinate().setSheetId(sheetId).setRowIndex(row).setColumnIndex(0))
                .setRows(listOf(RowData().setValues(cells.map { value -> CellData().setUserEnteredValue(
                    if (value is SheetFormula) ExtendedValue().setFormulaValue(SheetsFormulaLocale.adapt(value.value, workbookLocale)) else ExtendedValue().setStringValue(value.toString())) })))
                .setFields("userEnteredValue"))
        }.toMutableList()
        if (isNew) for (row in listOf(8,9)) output += Request().setUpdateCells(UpdateCellsRequest().setStart(GridCoordinate()
            .setSheetId(sheetId).setRowIndex(row).setColumnIndex(1)).setRows(listOf(RowData().setValues(listOf(CellData()
            .setUserEnteredValue(ExtendedValue().setNumberValue(0.0)))))).setFields("userEnteredValue"))
        return output
    }
}
