package com.gastos.feature.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ReportCsvStreamTest {
    @Test fun `streamed rows quote delimiters and lines and neutralize formulas including whitespace prefixes`() {
        val output = StringWriter()
        output.appendCsvRow("shop, branch", "line\n\"two\"", " \t=SUM(A1:A3)", -2.5, "-merchant")
        assertEquals("\"shop, branch\",\"line\n\"\"two\"\"\",\"' \t=SUM(A1:A3)\",\"-2.5\",\"'-merchant\"\n", output.toString())
    }
}
