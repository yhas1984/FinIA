package com.gastos.domain.usecase

import com.gastos.domain.model.Income
import com.gastos.repository.IncomeRepository
import javax.inject.Inject

/**
 * Caso de uso para guardar un ingreso en la base de datos local.
 *
 * Encapsula la lógica que estaba duplicada en `ChatbotViewModel`,
 * `VoiceCommandViewModel` y `ScanInvoiceViewModel`:
 *   1. Insertar el income
 *   2. Devolver el ID guardado
 */
class SaveIncomeUseCase @Inject constructor(
    private val incomeRepository: IncomeRepository
) {
    suspend operator fun invoke(income: Income): Long {
        return incomeRepository.insertIncome(income)
    }
}
