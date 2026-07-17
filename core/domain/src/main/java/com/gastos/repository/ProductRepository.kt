package com.gastos.repository

import com.gastos.domain.model.Product
import kotlinx.coroutines.flow.Flow

interface ProductRepository {
    fun getAllProducts(): Flow<List<Product>>
    fun getProductsByInvoiceId(invoiceId: Long): Flow<List<Product>>
    fun searchProducts(query: String): Flow<List<Product>>
    suspend fun getProductById(id: Long): Product?
    suspend fun insertProduct(product: Product): Long
    suspend fun insertProducts(products: List<Product>)
    suspend fun updateProduct(product: Product)
    suspend fun deleteProduct(product: Product)
    suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double?
    suspend fun getTotalByDescriptionAndDateRange(descripcion: String, startDate: Long, endDate: Long): Double?
}
