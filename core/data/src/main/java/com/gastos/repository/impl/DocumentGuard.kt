package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import javax.inject.Inject

class DocumentGuard @Inject constructor(private val database: AppDatabase) {
    /** Caller must hold the Room transaction until insertion/update completes. */
    suspend fun check(identity: DocumentIdentity) {
        val matches: List<DuplicateMatch> = DocumentDuplicates.blocking(identity, find(identity))
        if (matches.isNotEmpty()) throw DuplicateDocumentException(matches)
    }

    suspend fun find(identity: DocumentIdentity): List<DuplicateMatch> {
        if (identity.key == null && identity.evidence?.sourceSha256 == null) return emptyList()
        // Number confusables are intentionally only weak candidates. Read indexed candidates
        // plus the alternate O/0 key; legacy rows without an index are compared without guessing.
        val keys: Set<String?> = setOf(identity.key, identity.key?.replace('O', '0'), identity.key?.replace('0', 'O'))
        val records: MutableList<DocumentIdentity> = mutableListOf()
        for (key: String? in keys) {
            records += database.invoiceDao().documentCandidates(key, identity.evidence?.sourceSha256).map { it.toDomain().documentIdentity() }
            records += database.incomeDao().documentCandidates(key, identity.evidence?.sourceSha256).map { it.toDomain().documentIdentity() }
        }
        return DocumentDuplicates.find(identity, records.distinctBy { it.uuid })
    }

    suspend fun findHash(hash: String): List<DocumentIdentity> =
        (database.invoiceDao().documentCandidates(null, hash).filter { it.sourceSha256 == hash }.map { it.toDomain().documentIdentity() } +
            database.incomeDao().documentCandidates(null, hash).filter { it.sourceSha256 == hash }.map { it.toDomain().documentIdentity() })
}
