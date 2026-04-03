package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.CategoryDao
import com.gastos.domain.model.Category
import com.gastos.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao
) : CategoryRepository {

    override fun getAllCategories(): Flow<List<Category>> =
        categoryDao.getAllCategories().map { list -> list.map { it.toDomain() } }

    override suspend fun getCategoryById(id: Long): Category? =
        categoryDao.getCategoryById(id)?.toDomain()

    override suspend fun insertCategory(category: Category): Long =
        categoryDao.insertCategoryEntity(category.toEntity())

    override suspend fun updateCategory(category: Category) =
        categoryDao.updateCategoryEntity(category.toEntity())

    override suspend fun deleteCategory(category: Category) =
        categoryDao.deleteCategoryEntity(category.toEntity())

    override suspend fun insertDefaultCategories() {
        if (categoryDao.getCategoryCount() == 0) {
            val defaultCategories = listOf(
                Category(nombre = "Alimentación", icono = "restaurant", color = 0xFF4CAF50, esDefault = true),
                Category(nombre = "Transporte", icono = "directions_car", color = 0xFF2196F3, esDefault = true),
                Category(nombre = "Vivienda", icono = "home", color = 0xFFFF9800, esDefault = true),
                Category(nombre = "Salud", icono = "local_hospital", color = 0xFFF44336, esDefault = true),
                Category(nombre = "Ocio", icono = "sports_esports", color = 0xFF9C27B0, esDefault = true),
                Category(nombre = "Educación", icono = "school", color = 0xFF00BCD4, esDefault = true),
                Category(nombre = "Ropa", icono = "checkroom", color = 0xFFE91E63, esDefault = true),
                Category(nombre = "Servicios", icono = "electrical_services", color = 0xFF607D8B, esDefault = true),
                Category(nombre = "Impuestos", icono = "account_balance", color = 0xFF795548, esDefault = true),
                Category(nombre = "Otros", icono = "more_horiz", color = 0xFF9E9E9E, esDefault = true)
            )
            categoryDao.insertCategories(defaultCategories.map { it.toEntity() })
        }
    }
}