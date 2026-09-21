package com.gastos.feature.backup

import org.junit.Assert.*
import org.junit.Test

class SheetsFormulaLocaleTest {
    @Test fun `Spanish German and French workbooks use semicolons`() {
        for(locale in listOf("es_ES","de_DE","fr_FR")) assertEquals("=IF(A1=1;2;3)",SheetsFormulaLocale.adapt("=IF(A1=1,2,3)",locale))
    }
    @Test fun `English and Mexican workbooks retain comma separators`() {
        for(locale in listOf("en_US","en_GB","es_MX",null)) assertEquals("=IF(A1=1,2,3)",SheetsFormulaLocale.adapt("=IF(A1=1,2,3)",locale))
    }
    @Test fun `text and sheet names are preserved even with escaped quotes`() {
        assertEquals("=IF('A,B'!A1=1;\"a,b\";\"a\"\"b,c\")", SheetsFormulaLocale.adapt("=IF('A,B'!A1=1,\"a,b\",\"a\"\"b,c\")","es_ES"))
    }
}
