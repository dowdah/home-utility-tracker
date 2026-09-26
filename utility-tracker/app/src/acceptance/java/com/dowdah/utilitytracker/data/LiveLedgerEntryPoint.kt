package com.dowdah.utilitytracker.data

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveLedgerEntryPoint {
    fun repository(): BackendRepository
    fun database(): UtilityDatabase
    fun secrets(): SecretStore
}
