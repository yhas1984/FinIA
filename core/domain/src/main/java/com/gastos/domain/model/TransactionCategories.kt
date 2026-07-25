package com.gastos.domain.model

import java.text.Normalizer
import java.util.Locale

object TransactionCategories {
    const val UNCATEGORIZED_LABEL: String = "Sin categoría"
    const val CUSTOM_OPTION_LABEL: String = "Personalizada…"

    val defaultExpenseCategories: List<String> = listOf(
        "Alimentación",
        "Vivienda",
        "Transporte",
        "Servicios",
        "Salud",
        "Educación",
        "Ocio",
        "Viajes",
        "Impuestos",
        "Negocio",
        "Otros"
    )

    val defaultIncomeCategories: List<String> = listOf(
        "Nómina",
        "Ventas",
        "Honorarios",
        "Alquiler",
        "Intereses",
        "Reembolsos",
        "Otros"
    )

    fun normalizeCategory(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    fun normalizeKey(value: String?): String? = normalizeCategory(value)
        ?.lowercase(Locale.ROOT)
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        ?.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    fun displayCategory(value: String?): String = normalizeCategory(value) ?: UNCATEGORIZED_LABEL

    fun canonicalExpenseCategory(value: String?): String? = canonicalize(value, defaultExpenseCategories)

    fun canonicalIncomeCategory(value: String?): String? = canonicalize(value, defaultIncomeCategories)

    fun matchesCategory(category: String?, filter: String?): Boolean {
        val normalizedFilter: String? = normalizeKey(filter)
        if (normalizedFilter == null) return true
        return normalizeKey(category) == normalizedFilter
    }

    fun availableCategories(defaults: List<String>, existing: List<String?>): List<String> {
        val labelsByKey: LinkedHashMap<String, String> = LinkedHashMap()
        defaults.forEach { category ->
            normalizeKey(category)?.let { key -> labelsByKey[key] = category }
        }
        existing.forEach { category ->
            val normalized: String = normalizeCategory(category) ?: return@forEach
            val key: String = normalizeKey(normalized) ?: return@forEach
            labelsByKey.putIfAbsent(key, normalized)
        }
        return labelsByKey.values.toList()
    }

    private fun canonicalize(value: String?, defaults: List<String>): String? {
        val normalized: String = normalizeCategory(value) ?: return null
        val key: String = normalizeKey(normalized) ?: return normalized
        return defaults.firstOrNull { normalizeKey(it) == key } ?: normalized
    }
}
