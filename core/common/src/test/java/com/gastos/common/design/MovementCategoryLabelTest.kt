package com.gastos.common.design

import org.junit.Assert.assertEquals
import org.junit.Test

class MovementCategoryLabelTest {
    @Test fun legacyNullTextAndUncategorizedSubcategoriesDoNotAddAnEmptyLevel() {
        for (subcategory: String? in listOf(null, "", "  null  ", "NULL", "Sin categoría", "Uncategorized", "Sin subcategoría", "No subcategory")) {
            assertEquals("Transporte", formatMovementCategory("Transporte", subcategory, "es"))
            assertEquals("Transport", formatMovementCategory("Transporte", subcategory, "en"))
        }
        assertEquals("Forza / Gasolina", formatMovementCategory("Forza", "Gasolina", "es"))
        assertEquals("Sin categoría", formatMovementCategory(null, null, "es"))
    }
}
