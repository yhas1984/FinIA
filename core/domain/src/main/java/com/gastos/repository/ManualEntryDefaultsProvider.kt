package com.gastos.repository

data class ManualEntryDefaults(val currency: String, val country: String)

/** Reads persisted preferences once, without exposing credentials to an editor. */
interface ManualEntryDefaultsProvider {
    suspend fun manualEntryDefaults(): ManualEntryDefaults
}
