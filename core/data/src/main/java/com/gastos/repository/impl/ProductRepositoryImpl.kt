package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.ProductDao
import com.gastos.domain.model.Product
import com.gastos.repository.ProductRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepositoryImpl @Inject constructor(
    private val productDao: ProductDao
) : ProductRepository {

    override fun getAllProducts(): Flow<List<Product>> =
        productDao.getAllProducts().map { list -> list.map { it.toDomain() } }

    override fun getProductsByInvoiceId(invoiceId: Long): Flow<List<Product>> =
        productDao.getProductsByInvoiceId(invoiceId).map { list -> list.map { it.toDomain() } }

    override fun getProductsByCategory(categoriaId: Long): Flow<List<Product>> =
        productDao.getProductsByCategoryEntity(categoriaId).map { list -> list.map { it.toDomain() } }

    override fun searchProducts(query: String): Flow<List<Product>> =
        productDao.searchProducts(query).map { list -> list.map { it.toDomain() } }

    override suspend fun getProductById(id: Long): Product? =
        productDao.getProductById(id)?.toDomain()

    override suspend fun insertProduct(product: Product): Long =
        productDao.insertProductEntity(product.toEntity())

    override suspend fun insertProducts(products: List<Product>) =
        productDao.insertProducts(products.map { it.toEntity() })

    override suspend fun updateProduct(product: Product) =
        productDao.updateProductEntity(product.toEntity())

    override suspend fun deleteProduct(product: Product) =
        productDao.deleteProductEntity(product.toEntity())

    override suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double? =
        productDao.getTotalByDateRange(startDate, endDate)

    override suspend fun getTotalByDescriptionAndDateRange(
        descripcion: String,
        startDate: Long,
        endDate: Long
    ): Double? = productDao.getTotalByDescriptionAndDateRange(descripcion, startDate, endDate)
}