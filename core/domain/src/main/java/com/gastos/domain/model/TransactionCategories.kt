package com.gastos.domain.model

import java.text.Normalizer
import java.util.Locale

object TransactionCategories {
    const val UNCATEGORIZED_LABEL_ES: String = "Sin categoría"
    const val UNCATEGORIZED_LABEL_EN: String = "Uncategorized"
    const val NO_SUBCATEGORY_LABEL_ES: String = "Sin subcategoría"
    const val NO_SUBCATEGORY_LABEL_EN: String = "No subcategory"
    const val CUSTOM_OPTION_LABEL_ES: String = "Personalizada…"
    const val CUSTOM_OPTION_LABEL_EN: String = "Custom…"

    private val expenseLabelsEn = linkedMapOf(
        "Alimentación" to "Food",
        "Vivienda" to "Housing",
        "Transporte" to "Transport",
        "Servicios" to "Utilities",
        "Salud" to "Health",
        "Educación" to "Education",
        "Ocio" to "Leisure",
        "Viajes" to "Travel",
        "Impuestos" to "Taxes",
        "Negocio" to "Business",
        "Otros" to "Other"
    )

    private val incomeLabelsEn = linkedMapOf(
        "Nómina" to "Salary",
        "Ventas" to "Sales",
        "Honorarios" to "Fees",
        "Alquiler" to "Rent",
        "Intereses" to "Interest",
        "Reembolsos" to "Refunds",
        "Otros" to "Other"
    )

    private val expenseAliasesEn = mapOf(
        "Food" to "Alimentación",
        "Housing" to "Vivienda",
        "Transport" to "Transporte",
        "Utilities" to "Servicios",
        "Health" to "Salud",
        "Education" to "Educación",
        "Leisure" to "Ocio",
        "Travel" to "Viajes",
        "Taxes" to "Impuestos",
        "Business" to "Negocio",
        "Other" to "Otros"
    )

    private val incomeAliasesEn = mapOf(
        "Salary" to "Nómina",
        "Sales" to "Ventas",
        "Fees" to "Honorarios",
        "Rent" to "Alquiler",
        "Interest" to "Intereses",
        "Refunds" to "Reembolsos",
        "Other" to "Otros"
    )

    private val subcategoryLabelsEn = mapOf(
        "Supermercado" to "Grocery store",
        "Restaurantes" to "Restaurants",
        "Cafetería" to "Cafe",
        "Frutería" to "Greengrocer",
        "Carnicería" to "Butcher",
        "Panadería" to "Bakery",
        "Bebidas" to "Drinks",
        "Hipoteca" to "Mortgage",
        "Alquiler" to "Rent",
        "Comunidad" to "Community",
        "Reparaciones" to "Repairs",
        "Mobiliario" to "Furniture",
        "Electrodomésticos" to "Appliances",
        "Combustible" to "Fuel",
        "Transporte público" to "Public transport",
        "Aparcamiento" to "Parking",
        "Peajes" to "Tolls",
        "Mantenimiento" to "Maintenance",
        "Seguro del coche" to "Car insurance",
        "Electricidad" to "Electricity",
        "Agua" to "Water",
        "Teléfono" to "Phone",
        "Farmacia" to "Pharmacy",
        "Médico" to "Doctor",
        "Seguro médico" to "Health insurance",
        "Óptica" to "Optician",
        "Cursos" to "Courses",
        "Matrícula" to "Tuition",
        "Libros" to "Books",
        "Material escolar" to "School supplies",
        "Cine" to "Cinema",
        "Deporte" to "Sports",
        "Videojuegos" to "Video games",
        "Suscripciones" to "Subscriptions",
        "Fiestas" to "Parties",
        "Alojamiento" to "Accommodation",
        "Vuelos" to "Flights",
        "Tren" to "Train",
        "Comidas" to "Meals",
        "Turismo" to "Sightseeing",
        "Impuestos municipales" to "Local taxes",
        "Otros tributos" to "Other taxes",
        "Material" to "Supplies",
        "Publicidad" to "Advertising",
        "Envíos" to "Shipping",
        "Imprenta" to "Printing",
        "Salario base" to "Base salary",
        "Pagas extra" to "Extra pay",
        "Proyectos" to "Projects",
        "Consultoría" to "Consulting",
        "Vivienda" to "Housing",
        "Local" to "Commercial property",
        "Bancarios" to "Banking",
        "Inversiones" to "Investments",
        "Gastos" to "Expenses",
        "Seguros" to "Insurance"
    )

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

    /** Subcategorías sugeridas por categoría de GASTO (el usuario puede crear otras). */
    val suggestedExpenseSubcategories: Map<String, List<String>> = mapOf(
        "Alimentación" to listOf("Supermercado", "Restaurantes", "Cafetería", "Frutería", "Carnicería", "Panadería", "Bebidas"),
        "Vivienda" to listOf("Hipoteca", "Alquiler", "Comunidad", "Reparaciones", "Mobiliario", "Electrodomésticos"),
        "Transporte" to listOf("Combustible", "Transporte público", "Aparcamiento", "Peajes", "Mantenimiento", "Seguro del coche"),
        "Servicios" to listOf("Electricidad", "Agua", "Gas", "Internet", "Teléfono", "Streaming"),
        "Salud" to listOf("Farmacia", "Médico", "Seguro médico", "Óptica", "Dental"),
        "Educación" to listOf("Cursos", "Matrícula", "Libros", "Material escolar"),
        "Ocio" to listOf("Cine", "Deporte", "Videojuegos", "Suscripciones", "Fiestas"),
        "Viajes" to listOf("Alojamiento", "Vuelos", "Tren", "Comidas", "Turismo"),
        "Impuestos" to listOf("IVA", "IRPF", "Impuestos municipales", "Otros tributos"),
        "Negocio" to listOf("Material", "Software", "Publicidad", "Envíos", "Imprenta"),
        "Otros" to emptyList()
    )

    /** Subcategorías sugeridas por categoría de INGRESO (el usuario puede crear otras). */
    val suggestedIncomeSubcategories: Map<String, List<String>> = mapOf(
        "Nómina" to listOf("Salario base", "Extras", "Pagas extra"),
        "Ventas" to listOf("Productos", "Servicios"),
        "Honorarios" to listOf("Proyectos", "Consultoría"),
        "Alquiler" to listOf("Vivienda", "Local"),
        "Intereses" to listOf("Bancarios", "Inversiones"),
        "Reembolsos" to listOf("Gastos", "Seguros"),
        "Otros" to emptyList()
    )

    fun suggestedSubcategories(category: String?, isIncome: Boolean): List<String> {
        val normalized = normalizeCategory(category) ?: return emptyList()
        val map = if (isIncome) suggestedIncomeSubcategories else suggestedExpenseSubcategories
        val canonical = if (isIncome) {
            canonicalIncomeCategory(normalized)
        } else {
            canonicalExpenseCategory(normalized)
        }
        return map[canonical].orEmpty()
    }

    fun availableSubcategories(
        defaults: List<String>,
        existing: List<String?>
    ): List<String> = availableCategories(defaults, existing)

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

    fun displayCategory(value: String?): String = displayCategory(value, Locale.getDefault().language)

    fun displayCategory(value: String?, language: String): String = normalizeCategory(value)?.let { normalized ->
        if (normalizeKey(normalized) == normalizeKey(UNCATEGORIZED_LABEL_ES) ||
            normalizeKey(normalized) == normalizeKey(UNCATEGORIZED_LABEL_EN)
        ) {
            if (isEnglishLanguage(language)) UNCATEGORIZED_LABEL_EN else UNCATEGORIZED_LABEL_ES
        } else if (normalizeKey(normalized) == normalizeKey(NO_SUBCATEGORY_LABEL_ES) ||
            normalizeKey(normalized) == normalizeKey(NO_SUBCATEGORY_LABEL_EN)
        ) {
            if (isEnglishLanguage(language)) NO_SUBCATEGORY_LABEL_EN else NO_SUBCATEGORY_LABEL_ES
        } else {
            localizedCategoryLabel(normalized, language)
        }
    } ?: if (isEnglishLanguage(language)) UNCATEGORIZED_LABEL_EN else UNCATEGORIZED_LABEL_ES

    fun canonicalExpenseCategory(value: String?): String? = canonicalize(value, defaultExpenseCategories, expenseAliasesEn)

    fun canonicalIncomeCategory(value: String?): String? = canonicalize(value, defaultIncomeCategories, incomeAliasesEn)

    fun matchesCategory(category: String?, filter: String?): Boolean {
        val normalizedFilter: String? = normalizeKey(filter)
        if (normalizedFilter == null) return true
        val canonicalFilter = canonicalExpenseCategory(filter)
            ?: canonicalIncomeCategory(filter)
            ?: filter
        val canonicalCategory = canonicalExpenseCategory(category)
            ?: canonicalIncomeCategory(category)
            ?: category
        return normalizeKey(canonicalCategory) == normalizeKey(canonicalFilter)
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

    fun currentUncategorizedLabel(language: String = Locale.getDefault().language): String =
        if (isEnglishLanguage(language)) UNCATEGORIZED_LABEL_EN else UNCATEGORIZED_LABEL_ES

    fun currentCustomOptionLabel(language: String = Locale.getDefault().language): String =
        if (isEnglishLanguage(language)) CUSTOM_OPTION_LABEL_EN else CUSTOM_OPTION_LABEL_ES

    fun isUncategorized(value: String?): Boolean =
        normalizeKey(value) == normalizeKey(UNCATEGORIZED_LABEL_ES) ||
            normalizeKey(value) == normalizeKey(UNCATEGORIZED_LABEL_EN)

    private fun canonicalize(value: String?, defaults: List<String>, aliases: Map<String, String>): String? {
        val normalized: String = normalizeCategory(value) ?: return null
        val key: String = normalizeKey(normalized) ?: return normalized
        return defaults.firstOrNull { normalizeKey(it) == key }
            ?: aliases[normalized]
            ?: aliases.entries.firstOrNull { normalizeKey(it.key) == key }?.value
            ?: normalized
    }

    private fun localizedCategoryLabel(value: String, language: String): String {
        if (!isEnglishLanguage(language)) return value
        return expenseLabelsEn[value] ?: incomeLabelsEn[value] ?: subcategoryLabelsEn[value] ?: value
    }

    private fun isEnglishLocale(): Boolean = Locale.getDefault().language == "en"

    private fun isEnglishLanguage(language: String): Boolean = language.lowercase(Locale.ROOT) == "en"
}
