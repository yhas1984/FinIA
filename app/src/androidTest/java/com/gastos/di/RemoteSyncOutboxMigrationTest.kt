package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.feature.backup.RemoteSyncOutboxDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSyncOutboxMigrationTest {
    private lateinit var context: Context
    private val databaseName = "remote-sync-outbox-migration-test"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrates_v1_outbox_to_operation_and_remote_file_columns() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE `remote_sync_outbox` (`targetKey` TEXT NOT NULL, `target` TEXT NOT NULL, `recordId` INTEGER NOT NULL, `action` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`targetKey`))")
            db.execSQL("INSERT INTO `remote_sync_outbox` VALUES ('INCOME_SHEETS:7', 'INCOME_SHEETS', 7, 'UPSERT', 10)")
            db.version = 1
        }

        val database = Room.databaseBuilder(context, RemoteSyncOutboxDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_REMOTE_SYNC_OUTBOX_1_2, MIGRATION_REMOTE_SYNC_OUTBOX_2_3)
            .build()
        database.openHelper.writableDatabase.use { sqlite ->
            sqlite.query("PRAGMA table_info(remote_sync_outbox)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
                assertTrue("operationId" in columns)
                assertTrue("remoteFileId" in columns)
            }
            sqlite.query("SELECT targetKey, operationId, remoteFileId FROM remote_sync_outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("INCOME_SHEETS:7", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        }
        database.close()
    }
}
