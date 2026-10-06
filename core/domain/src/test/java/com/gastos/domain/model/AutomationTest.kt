package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class AutomationTest {
    @Test fun matchingIsParentScopedAndExactWinsTies() {
        val root = Category("forza", DocumentKind.EXPENSE, "Forza")
        val fuel = Category("fuel", DocumentKind.EXPENSE, "Gasolina", "forza")
        val contains = CategoryRule("a", DocumentKind.EXPENSE, "for", "forza", "fuel", RuleMatch.CONTAINS)
        val exact = contains.copy(id = "z", merchant = "Forza", match = RuleMatch.EXACT)
        assertEquals(exact, AutomationCodec.selectRule(listOf(contains, exact), listOf(root, fuel), DocumentKind.EXPENSE, "  FORZA  "))
        assertNull(AutomationCodec.selectRule(listOf(exact), listOf(root.copy(archived = true), fuel), DocumentKind.EXPENSE, "Forza"))
        assertNull(AutomationCodec.selectRule(listOf(exact), listOf(root, fuel), DocumentKind.INCOME, "Forza"))
        assertEquals("燃料", AutomationCodec.key(" 燃料 "))
        assertNotEquals(AutomationCodec.key("燃料"), AutomationCodec.key("工资"))
    }
    @Test fun walletOnlyAcceptsClearCompletedEventsAndDoesNotInferCurrency() {
        val parser = WalletPaymentParser
        val completed = parser.parse(parser.PACKAGE, "key", 1, "Google Wallet", "Has pagado 20,50 EUR en Test Forza.")!!
        assertEquals(20.5, completed.amount, 0.0)
        assertEquals("Test Forza", completed.merchant)
        assertEquals(completed.eventId, parser.parse(parser.PACKAGE, "key", 1, "Google Wallet", "Has pagado 20,50 EUR en Test Forza.")!!.eventId)
        assertNotEquals(completed.eventId, parser.parse(parser.PACKAGE, "key", 2, "Google Wallet", "Has pagado 20,50 EUR en Test Forza.")!!.eventId)
        assertNull(parser.parse("other.bank", "key", 1, "Wallet", "Has pagado 20 EUR en Test Forza"))
        listOf("Pago pendiente: Has pagado 20 EUR en Test", "Has pagado 20 USD y 10 EUR en Test", "Has pagado 20 $ en Test", "Has pagado 20 EUR", "Código 123456. Has pagado 20 EUR en Test").forEach { assertNull(it, parser.parse(parser.PACKAGE, "key", 1, "Wallet", it)) }
        assertEquals(1234.56, parser.amount("1.234,56")!!, 0.0)
        assertEquals(1234.56, parser.amount("1,234.56")!!, 0.0)
        assertNull(parser.amount("1,234"))
    }
    @Test fun limitCountsExpensesOnlyAndDisclosesMissingRates() {
        val limit = MonthlyLimit("id", "cat", "2026-10", 100.0, "EUR")
        val expense = Invoice(fecha = 5, proveedor = "Test", tipo = InvoiceType.GASTO, total = 20.0, categoryId = "cat", ivaPercent = null)
        val result = MonthlyLimitCalculator.summarize(limit, "Forza", listOf(expense, expense.copy(moneda = "USD"), expense.copy(tipo = InvoiceType.INGRESO), expense.copy(fecha = 10)), 0, 10) { amount, from, _ -> amount.takeIf { from == "EUR" } }
        assertEquals(20.0, result.spent!!, 0.0)
        assertEquals(1, result.excluded)
        assertTrue(result.partial)
        assertEquals(80.0, result.remaining!!, 0.0)
        val unavailable = MonthlyLimitCalculator.summarize(limit, "Forza", listOf(expense.copy(moneda = "USD")), 0, 10) { _, _, _ -> null }
        assertNull(unavailable.spent)
    }
    @Test fun reportBoundariesAndSubcategoryFilterAreStrict() {
        val filter = ReportFilter(10, 20, DocumentKind.EXPENSE, "Forza", "Gasolina")
        assertTrue(filter.includes(10, DocumentKind.EXPENSE, "Forza", "Gasolina"))
        assertFalse(filter.includes(20, DocumentKind.EXPENSE, "Forza", "Gasolina"))
        assertFalse(filter.includes(10, DocumentKind.INCOME, "Forza", "Gasolina"))
        assertFalse(filter.includes(10, DocumentKind.EXPENSE, "Forza", "Peajes"))
    }
    @Test fun backupRejectsOrphanCatalogAndNonFiniteLimits() {
        val category = Category("cat", DocumentKind.EXPENSE, "Forza")
        assertTrue(runCatching { AutomationValidation.validate(AutomationData(listOf(category.copy(parentId = "missing")))) }.isFailure)
        assertTrue(runCatching { AutomationValidation.validate(AutomationData(listOf(category), limits = listOf(MonthlyLimit("id", "cat", "2026-10", Double.NaN, "EUR")))) }.isFailure)
        AutomationValidation.validate(AutomationData(listOf(category), limits = listOf(MonthlyLimit("id", "cat", "2026-10", 100.0, "EUR"))))
    }
}
