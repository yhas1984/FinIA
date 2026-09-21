package com.gastos.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS command_operations (uuid TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, status TEXT NOT NULL, resultKind TEXT, resultUuid TEXT, resultText TEXT, createdAt INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN operationUuid TEXT")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN documentUuid TEXT")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN documentKind TEXT")
    }
}
