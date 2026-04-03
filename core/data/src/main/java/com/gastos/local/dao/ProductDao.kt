package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.ProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Query("SELECT * FROM products ORDER BY createdAt DESC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE invoiceId = :invoiceId ORDER BY createdAt DESC")
    fun getProductsByInvoiceId(invoiceId: Long): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE categoriaId = :categoriaId ORDER BY createdAt DESC")
    fun getProductsByCategoryEntity(categoriaId: Long): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE descripcion LIKE '%' || :query || '%' ORDER BY descripcion ASC")
    fun searchProducts(query: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: Long): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductEntity(product: ProductEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<ProductEntity>)

    @Update
    suspend fun updateProductEntity(product: ProductEntity)

    @Delete
    suspend fun deleteProductEntity(product: ProductEntity)

    @Query("""
        SELECT SUM(p.subtotal) 
        FROM products p 
        INNER JOIN invoices i ON p.invoiceId = i.id 
        WHERE i.fecha BETWEEN :startDate AND :endDate
    """)
    suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double?

    @Query("""
        SELECT SUM(p.subtotal) 
        FROM products p 
        INNER JOIN invoices i ON p.invoiceId = i.id 
        WHERE p.descripcion LIKE '%' || :descripcion || '%' 
        AND i.fecha BETWEEN :startDate AND :endDate
    """)
    suspend fun getTotalByDescriptionAndDateRange(descripcion: String, startDate: Long, endDate: Long): Double?
}