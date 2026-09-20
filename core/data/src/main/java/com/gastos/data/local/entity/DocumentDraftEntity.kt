package com.gastos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kept outside backup snapshots and outside financial totals. */
@Entity(tableName = "document_drafts", indices = [Index(value = ["sourceSha256"], unique = true)])
data class DocumentDraftEntity(
    @PrimaryKey val uuid: String,
    val sourceSha256: String,
    val imageUri: String,
    val evidenceJson: String? = null,
    val status: String = "PENDING",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
