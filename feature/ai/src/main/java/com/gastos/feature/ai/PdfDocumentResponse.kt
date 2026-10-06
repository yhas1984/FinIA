package com.gastos.feature.ai

import org.json.JSONObject

/** Multiple independent documents cannot be silently merged into one transaction. */
internal object PdfDocumentResponse {
    fun count(raw: String): Int {
        val number = requireNotNull(JSONObject(raw).get("document_count") as? Number) { "PDF_DOCUMENT_COUNT_INVALID" }
        val count = number.toInt()
        require(number.toDouble() == count.toDouble() && count in 1..20) { "PDF_DOCUMENT_COUNT_INVALID" }
        return count
    }
}
