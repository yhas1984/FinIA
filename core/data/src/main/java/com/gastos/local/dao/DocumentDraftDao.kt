package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.DocumentDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDraftDao {
    @Query("SELECT * FROM document_drafts ORDER BY createdAt")
    fun observe(): Flow<List<DocumentDraftEntity>>
    @Query("SELECT * FROM document_drafts WHERE uuid = :uuid")
    suspend fun get(uuid: String): DocumentDraftEntity?
    @Query("SELECT * FROM document_drafts WHERE sourceSha256 = :hash")
    suspend fun findHash(hash: String): DocumentDraftEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: DocumentDraftEntity)
    @Update
    suspend fun update(draft: DocumentDraftEntity): Int
    @Query("DELETE FROM document_drafts WHERE uuid = :uuid")
    suspend fun delete(uuid: String)
}
