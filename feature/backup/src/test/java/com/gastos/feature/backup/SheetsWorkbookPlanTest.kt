package com.gastos.feature.backup

import org.junit.Assert.*
import org.junit.Test

class SheetsWorkbookPlanTest {
    private val definition = ManagedSheet("Expenses", listOf("UUID", "Amount", "Month"), 0, numberColumns = setOf(1))
    @Test fun `personal columns inserted among managed columns are preserved on update and deletion`() {
        val snapshot = SheetSnapshot(1, "Expenses", listOf(listOf("UUID", "Personal note", "Amount", "Month"), listOf("uuid", "do not touch", 20.0, 9)))
        val update = SheetsWorkbookPlan(definition, snapshot)
        update.upsert(ManagedRecord(listOf("uuid", 25.0, 9)))
        assertTrue(update.finish().none { it.updateCells?.start?.columnIndex == 1 })
        assertEquals(1, update.finish().last().updateCells.start.rowIndex)
        val delete = SheetsWorkbookPlan(definition, snapshot)
        delete.clearWhere { it[0] == "uuid" }
        assertTrue(delete.finish().none { it.deleteDimension != null })
        assertTrue(delete.finish().none { it.updateCells?.start?.columnIndex == 1 })
        assertTrue(delete.finish().filter { it.updateCells?.start?.rowIndex == 1 }.all {
            it.updateCells.rows.single().getValues().single().userEnteredValue == null
        })
    }
    @Test fun `retry after applied timeout finds the same UUID without appending another row`() {
        val snapshot = SheetSnapshot(1, "Expenses", listOf(definition.headers, listOf("uuid",20.0,9)))
        val plan = SheetsWorkbookPlan(definition, snapshot)
        plan.upsert(ManagedRecord(listOf("uuid",20.0,9)))
        assertTrue(plan.finish().all { it.updateCells == null || it.updateCells.start.rowIndex <= 1 })
    }
    @Test fun `recycled numeric identity with different content requires review`() {
        val snapshot = SheetSnapshot(1, "Expenses", listOf(listOf("ID","Amount","Month"), listOf(7,20.0,9)))
        val plan = SheetsWorkbookPlan(definition, snapshot)
        assertTrue(runCatching { plan.upsert(ManagedRecord(listOf("uuid",30.0,9),7,mapOf(1 to 30.0))) }.isFailure)
    }
    @Test fun `matching legacy document receives UUID in place without moving personal data`() {
        val snapshot = SheetSnapshot(1, "Expenses", listOf(listOf("ID","Amount","Month","Note"), listOf(7,20.0,9,"personal")))
        val plan = SheetsWorkbookPlan(definition, snapshot)
        plan.upsert(ManagedRecord(listOf("uuid",20.0,9),7,mapOf(1 to 20.0)))
        assertFalse(plan.hasUnmatchedLegacyRows())
        assertTrue(plan.finish().none { (it.updateCells?.start?.columnIndex ?: 0) >= 3 })
    }
    @Test fun `new columns append after personal columns rather than overwriting them`() {
        val plan = SheetsWorkbookPlan(definition, SheetSnapshot(1,"Expenses",listOf(listOf("UUID","Amount","My formula"))))
        assertEquals(listOf(0,1,3),plan.columns)
    }
    @Test fun `untrusted formula-like text is written as a string`() {
        val plan = SheetsWorkbookPlan(definition, SheetSnapshot(1,"Expenses",emptyList()))
        plan.upsert(ManagedRecord(listOf("=IMPORTXML(\"secret\")",20.0,9)))
        assertTrue(plan.finish().flatMap { it.updateCells?.rows.orEmpty() }.flatMap { it.getValues() }.none { it.userEnteredValue?.formulaValue != null })
    }
    @Test fun `duplicate identity or ambiguous headers are not merged`() {
        val duplicate = SheetsWorkbookPlan(definition, SheetSnapshot(1,"Expenses", listOf(definition.headers,listOf("uuid",20,9),listOf("uuid",20,9))))
        assertTrue(runCatching { duplicate.upsert(ManagedRecord(listOf("uuid",20,9))) }.isFailure)
        assertTrue(runCatching { SheetsWorkbookPlan(definition,SheetSnapshot(1,"Expenses",listOf(listOf("UUID","UUID","Amount","Month")))) }.isFailure)
    }
    @Test fun `date money and exchange rate formats follow mapped columns in either language`() {
        for (language in SheetsSchema.LocaleCode.entries) {
            val descriptor = SheetsSchema.descriptor(language)
            val definition = ManagedSheet(descriptor.recibidasTitle,descriptor.recibidasHeaders,14,
                dateColumns=setOf(1),rateColumns=setOf(18),numberColumns=setOf(10))
            val headers = definition.headers.toMutableList().apply { add(1,"Personal") }
            val plan = SheetsWorkbookPlan(definition,SheetSnapshot(1,definition.title,listOf(headers)))
            plan.put(1,1,45000.0)
            plan.put(1,10,100.25)
            plan.put(1,18,0.123456)
            val data = plan.finish().mapNotNull { it.updateCells }.filter { it.start.rowIndex == 1 }
            assertEquals(2,data[0].start.columnIndex)
            assertEquals("DATE",data[0].rows.single().getValues().single().userEnteredFormat.numberFormat.type)
            assertEquals(45000.0,data[0].rows.single().getValues().single().userEnteredValue.numberValue,0.0)
            assertEquals("#,##0.00",data[1].rows.single().getValues().single().userEnteredFormat.numberFormat.pattern)
            assertEquals("0.000000",data[2].rows.single().getValues().single().userEnteredFormat.numberFormat.pattern)
        }
    }

}
