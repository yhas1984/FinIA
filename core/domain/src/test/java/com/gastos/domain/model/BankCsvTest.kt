package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class BankCsvTest {
    private val account = BankAccount("bank:account:test", "Principal", "EUR")
    private fun read(text: String) = BankCsv.read(text.toByteArray())
    private fun preview(text: String, mapping: BankMapping? = null): List<BankPreview> {
        val table = read(text)
        return BankCsv.preview(table, account, mapping ?: BankCsv.detect(table.headers))
    }
    @Test fun spanishAmountsAndQuotedMultilineFields() {
        val rows = preview("\uFEFFFecha;Concepto;Importe\r\n04/10/2026;\"Compra; pan\nTienda \"\"A\"\"\";-1.234,56\r\n05/10/2026;Nomina;2500,00")
        assertEquals(2, rows.size)
        assertEquals("-1234.56", rows[0].transaction!!.amount)
        assertEquals("Compra; pan\nTienda \"A\"", rows[0].transaction!!.description)
        assertEquals("2500", rows[1].transaction!!.amount)
    }
    @Test fun windows1252AndTabs() {
        val table = BankCsv.read("Fecha\tDescripción\tImporte\n04/10/2026\tCafé\t-2,30".toByteArray(charset("windows-1252")))
        assertEquals("Café", BankCsv.preview(table, account, BankCsv.detect(table.headers)).single().transaction!!.description)
    }
    @Test fun englishNumbersAndStrictDates() {
        val result = preview("Date,Description,Amount\n10/04/2026,Shop,\"-1,234.56\"",
            BankMapping(0, 1, 2, datePattern = "MM/dd/yyyy", decimalComma = false))
        assertEquals("-1234.56", result.single().transaction!!.amount)
        assertEquals("BANK_DATE", preview("Fecha;Concepto;Importe\n31/02/2026;Shop;-2,30").single().error)
        assertEquals("BANK_DATE", preview("Fecha;Concepto;Importe\n04/10/2026 basura;Shop;-2,30").single().error)
    }
    @Test fun invalidGroupingPrecisionAndNonfiniteRejected() {
        for (amount in listOf("1.23,40", "NaN", "Infinity", "12 euros", "1,234", "0", "1e3")) {
            assertEquals(amount, "BANK_AMOUNT", preview("Fecha;Concepto;Importe\n04/10/2026;Shop;$amount").single().error)
        }
    }
    @Test fun separateDebitCreditAndSigns() {
        val table = read("Fecha;Concepto;Cargo;Abono\n04/10/2026;Shop;12,50;\n04/10/2026;Salario;;2300,00\n04/10/2026;Mal;1;1")
        val rows = BankCsv.preview(table, account, BankCsv.detect(table.headers))
        assertEquals("-12.5", rows[0].transaction!!.amount)
        assertEquals("2300", rows[1].transaction!!.amount)
        assertEquals("BANK_AMOUNT", rows[2].error)
        assertEquals("-25", preview("Fecha;Concepto;Importe\n04/10/2026;Shop;25", BankMapping(0,1,2, reverseSign = true)).single().transaction!!.amount)
    }
    @Test fun identicalRowsHaveDifferentIdentityButReimportIsStable() {
        val csv = "Fecha;Concepto;Importe\n04/10/2026;Cafe;-2\n04/10/2026;Cafe;-2"
        val rows = preview(csv).map { it.transaction!! }
        assertNotEquals(rows[0].id, rows[1].id)
        assertEquals(rows, preview(csv).map { it.transaction!! })
        val table = read(csv)
        assertNotEquals(rows[0].batchId, BankCsv.preview(table, account.copy(id = "other"), BankCsv.detect(table.headers))[0].transaction!!.batchId)
    }
    @Test fun referenceIsNotAutomaticallyUniqueId() {
        assertEquals(-1, BankCsv.detect(listOf("Fecha", "Concepto", "Importe", "Referencia")).transactionId)
    }
    @Test fun currencyAndFieldCountValidated() {
        assertEquals("BANK_COLUMNS", preview("Fecha;Concepto;Importe\n04/10/2026;Shop;-2;extra").single().error)
        assertEquals("BANK_CURRENCY", preview("Fecha;Concepto;Importe;Moneda\n04/10/2026;Shop;-2;XYZ").single().error)
    }
    @Test fun brokenQuotesAndDuplicateMappingFail() {
        assertThrows(IllegalStateException::class.java) { read("Fecha;Concepto;Importe\n04/10/2026;\"Shop;-2") }
        assertThrows(IllegalArgumentException::class.java) { preview("Fecha;Concepto;Importe\n04/10/2026;Shop;-2", BankMapping(0,0,2)) }
    }
    @Test fun candidatesRespectDirectionCurrencyAndDate() {
        val row = preview("Fecha;Concepto;Importe\n04/10/2026;Shop;-20").single().transaction!!
        val match = BankMovement("expense", "INVOICE", true, 20.0, "EUR", row.date, "Shop")
        assertEquals(listOf(match), BankMatching.candidates(row, listOf(match, match.copy(uuid = "income", expense = false),
            match.copy(uuid = "usd", currency = "USD"), match.copy(uuid = "old", date = row.date - 9L * 86400000))))
    }
    @Test fun specialOperationsRequireClassification() {
        listOf("Transferencia recibida", "Devolución tienda", "Liquidación tarjeta", "Card settlement", "cuota 3").forEach {
            assertTrue(it, BankMatching.needsClassification(it))
        }
        assertFalse(BankMatching.needsClassification("Supermercado"))
    }
    @Test fun bankMetadataRoundTripsAndRejectsBrokenReferences() {
        val row = preview("Fecha;Concepto;Importe\n04/10/2026;Shop;-20").single().transaction!!
        val batch = BankBatch(row.batchId, account.id, read("Fecha;Concepto;Importe\n04/10/2026;Shop;-20").hash, "test.csv", BankMapping(0,1,2), 1, 1)
        val records = listOf(AutomationRecord(account.id,"BANK_ACCOUNT",AutomationCodec.json.encodeToString(BankAccount.serializer(), account)),
            AutomationRecord(batch.id,"BANK_BATCH",AutomationCodec.json.encodeToString(BankBatch.serializer(), batch)),
            AutomationRecord(row.id,"BANK_ROW",AutomationCodec.json.encodeToString(BankTransaction.serializer(), row)))
        AutomationValidation.validate(AutomationData(records = records))
        assertThrows(IllegalArgumentException::class.java) { AutomationValidation.validate(AutomationData(records = records.drop(1))) }
    }

    @Test fun automaticLinkRequiresAnExplicitTrustedReferenceAndCompatibleMerchant() {
        val row = preview("Fecha;Concepto;Importe;Referencia\n04/10/2026;Compra Tienda Norte;-20;F-2026-19").single().transaction!!
        val movement = BankMovement("expense", "INVOICE", true, 20.0, "EUR", row.date, "Tienda Norte", number = "F-2026-19")
        assertEquals(movement, BankMatching.automatic(row, listOf(movement), true))
        assertNull(BankMatching.automatic(row, listOf(movement), false))
        assertNull(BankMatching.automatic(row.copy(reference = ""), listOf(movement), true))
        assertNull(BankMatching.automatic(row, listOf(movement.copy(number = "different")), true))
        assertNull(BankMatching.automatic(row, listOf(movement.copy(description = "Other store")), true))
    }

    @Test fun equalAmountOrAmbiguousReferencesNeverTriggerAutomaticLink() {
        val row = preview("Fecha;Concepto;Importe;Referencia\n04/10/2026;Tienda Norte;-20;F-19").single().transaction!!
        val match = BankMovement("one", "INVOICE", true, 20.0, "EUR", row.date, "Tienda Norte", number = "F-19")
        assertNull(BankMatching.automatic(row, listOf(match.copy(number = null)), true))
        assertNull(BankMatching.automatic(row, listOf(match, match.copy(uuid = "two")), true))
        assertNull(BankMatching.automatic(row, listOf(match.copy(expense = false)), true))
        assertNull(BankMatching.automatic(row, listOf(match.copy(currency = "USD")), true))
        assertNull(BankMatching.automatic(row, listOf(match.copy(date = row.date - 8L * 86400000)), true))
        assertNull(BankMatching.automatic(row.copy(description = "Devolución Tienda Norte"), listOf(match), true))
    }

    @Test fun referenceComparisonPreservesPunctuationAndMerchantWordBoundaries() {
        val row = preview("Fecha;Concepto;Importe;Referencia\n04/10/2026;Compra CAFÉ SUR;-20;A-001").single().transaction!!
        val match = BankMovement("one", "INVOICE", true, 20.0, "EUR", row.date, "Café Sur", number = "a-001")
        assertEquals(match, BankMatching.automatic(row, listOf(match), true))
        assertNull(BankMatching.automatic(row.copy(reference = "A001"), listOf(match), true))
        assertNull(BankMatching.automatic(row.copy(description = "Compra Café Surplus"), listOf(match), true))
    }

    @Test fun explicitHeaderAndDelimiterPreserveSourceLinesAndImportIdentity() {
        val bytes = "Extracto de pruebas\nCuenta principal\nFecha|Concepto|Importe\n04/10/2026|Compra; pan|-12,50\n31/02/2026|Error|-2".toByteArray()
        val table = BankCsv.read(bytes, delimiter = '|', headerLine = 2)
        assertEquals(listOf("Fecha", "Concepto", "Importe"), table.headers)
        assertEquals("|", table.delimiter)
        assertEquals(2, table.headerLine)
        assertEquals(BankCsv.hash(bytes), table.hash)
        val rows = BankCsv.preview(table, account, BankCsv.detect(table.headers))
        assertEquals(4, rows[0].line)
        assertEquals("Compra; pan", rows[0].transaction!!.description)
        assertEquals("-12.5", rows[0].transaction!!.amount)
        assertEquals(5, rows[1].line)
        assertEquals("BANK_DATE", rows[1].error)
        assertEquals(rows, BankCsv.preview(BankCsv.read(bytes, '|', 2), account, BankCsv.detect(table.headers)))
    }

    @Test fun headerSelectionDoesNotGuessRowsWhenExplicitSeparatorIsWrong() {
        val bytes = "Export\nFecha;Concepto;Importe\n04/10/2026;Tienda;-2,30".toByteArray()
        assertThrows(IllegalStateException::class.java) { BankCsv.read(bytes, '\t', 1) }
        assertThrows(IllegalStateException::class.java) { BankCsv.read(bytes, ';', 20) }
        assertEquals(3, BankCsv.preview(BankCsv.read(bytes, ';', 1), account, BankMapping(0, 1, 2)).single().line)
    }

    @Test fun optionalColumnOutsideTheTableIsRejectedInsteadOfInventingADefault() {
        val table = read("Fecha;Concepto;Importe\n04/10/2026;Tienda;-2,30")
        val mapping = BankCsv.detect(table.headers)
        for (invalid in listOf(mapping.copy(currency = 8), mapping.copy(transactionId = 8), mapping.copy(reference = 8))) {
            assertThrows(IllegalArgumentException::class.java) { BankCsv.preview(table, account, invalid) }
        }
    }
    @Test fun blankRowsPreserveHeaderAndErrorLineNumbers() {
        val bytes = "\nReport\n\nFecha;Concepto;Importe\n04/10/2026;Shop;-20\n\n31/02/2026;Bad;-4\n".toByteArray()
        val table = BankCsv.read(bytes, ';', 3)
        val account = BankAccount("bank:account:synthetic", "Synthetic", "EUR")
        val preview = BankCsv.preview(table, account, BankCsv.detect(table.headers))
        assertEquals(listOf(5, 7), preview.map { it.line })
        assertNull(preview.first().error)
        assertEquals("BANK_DATE", preview.last().error)
    }

    @Test fun ordinaryCardPurchaseIsNotMistakenForCardSettlement() {
        assertFalse(BankMatching.needsClassification("Compra tarjeta Tienda Norte"))
        assertTrue(BankMatching.needsClassification("Liquidación tarjeta septiembre"))
        assertTrue(BankMatching.needsClassification("Card settlement"))
    }

}
