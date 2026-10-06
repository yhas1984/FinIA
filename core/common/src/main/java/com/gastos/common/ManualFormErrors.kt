package com.gastos.common

enum class ManualSection { MAIN, DOCUMENT, TAXES, PAYROLL, NOTES }

enum class ManualField(val section: ManualSection) {
    AMOUNT(ManualSection.MAIN), CURRENCY(ManualSection.MAIN), CONCEPT(ManualSection.MAIN),
    VAT(ManualSection.TAXES), WITHHOLDING(ManualSection.TAXES), BASE(ManualSection.TAXES),
    TAX_AMOUNT(ManualSection.TAXES), TAX_BREAKDOWN(ManualSection.TAXES),
    GROSS(ManualSection.PAYROLL), NET(ManualSection.PAYROLL)
}
