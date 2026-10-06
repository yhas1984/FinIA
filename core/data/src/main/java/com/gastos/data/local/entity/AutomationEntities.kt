package com.gastos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gastos.domain.model.*

@Entity(tableName = "categories", indices = [Index(value = ["kind", "parentId", "nameKey"], unique = true)])
data class CategoryEntity(@PrimaryKey val id: String, val kind: String, val name: String, val parentId: String,
    val archived: Boolean, val nameKey: String)

@Entity(tableName = "automation_records", indices = [Index("type")])
data class AutomationRecordEntity(@PrimaryKey val id: String, val type: String, val payload: String)

@Entity(tableName = "monthly_limits", indices = [Index(value = ["categoryId", "month"], unique = true)])
data class MonthlyLimitEntity(@PrimaryKey val id: String, val categoryId: String, val month: String, val payload: String)

fun CategoryEntity.toDomain(): Category = Category(id, DocumentKind.valueOf(kind), name, parentId, archived)
fun Category.toEntity(): CategoryEntity = CategoryEntity(id, kind.name, name, parentId, archived, AutomationCodec.key(name))
fun AutomationRecordEntity.toDomain(): AutomationRecord = AutomationRecord(id, type, payload)
fun AutomationRecord.toEntity(): AutomationRecordEntity = AutomationRecordEntity(id, type, payload)
