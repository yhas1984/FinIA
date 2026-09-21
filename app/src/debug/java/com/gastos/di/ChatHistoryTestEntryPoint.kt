package com.gastos.di

import com.gastos.local.database.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets instrumented tests write through the same Room instance observed by the chat. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatHistoryTestEntryPoint {
    fun database(): AppDatabase
}
