package com.gastos.domain.model

object AutomationValidation {
    fun validate(data: AutomationData) {
        BankBackupValidation.validate(data.records)
        WalletPaymentPolicy.validateBackup(data.records)
        val categories = data.categories.associateBy { it.id }
        require(categories.size == data.categories.size && categories.keys.none(String::isBlank)) { "BACKUP_CATEGORIES_INVALID" }
        require(data.categories.map { Triple(it.kind, it.parentId, AutomationCodec.key(it.name)) }.distinct().size == data.categories.size) { "BACKUP_CATEGORY_DUPLICATE" }
        data.categories.forEach { category ->
            require(category.name.trim().isNotEmpty() && category.name.length <= 100)
            if (category.parentId.isNotEmpty()) require(categories[category.parentId]?.let { it.parentId.isEmpty() && it.kind == category.kind } == true)
        }
        require(data.records.map { it.id }.distinct().size == data.records.size)
        data.records.filter { it.type == "RULE" }.forEach { row ->
            val rule: CategoryRule = AutomationCodec.json.decodeFromString(row.payload)
            require(categories[rule.categoryId]?.let { it.kind == rule.kind && it.parentId.isEmpty() } == true && rule.merchant.isNotBlank())
            require(rule.subcategoryId == null || categories[rule.subcategoryId]?.parentId == rule.categoryId)
        }
        require(data.limits.map { it.categoryId to it.month }.distinct().size == data.limits.size)
        data.limits.forEach { limit ->
            require(categories[limit.categoryId]?.let { it.kind == DocumentKind.EXPENSE && it.parentId.isEmpty() } == true)
            require(limit.amount.isFinite() && limit.amount > 0 && Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(limit.month))
            require(runCatching { java.util.Currency.getInstance(limit.currency) }.isSuccess)
        }
        data.records.filter { it.type == "MUTATION" }.forEach { row ->
            val mutation: FinancialMutation = AutomationCodec.json.decodeFromString(row.payload)
            require((mutation.beforeIncome == null) != (mutation.beforeInvoice == null))
            require((mutation.beforeIncome?.documentUuid ?: mutation.beforeInvoice?.documentUuid) == mutation.documentUuid && mutation.expectedRevision > 0)
        }
    }
}
