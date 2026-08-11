package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.feature.backup.RemoteSyncOutboxDatabase
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSyncOutboxDatabaseTest {
    private lateinit var context: Context
    private val databaseName = "remote-sync-outbox-test"

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
    fun opensDaoAndDeletesTestDatabase() {
        val database = Room.databaseBuilder(context, RemoteSyncOutboxDatabase::class.java, databaseName)
            .build()

        database.openHelper.writableDatabase
        assertNotNull(database.dao())
        database.close()

        context.deleteDatabase(databaseName)
    }
}
