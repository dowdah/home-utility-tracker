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
    fun forecasts(): ForecastRepository
    fun reminderDevice(): com.dowdah.utilitytracker.reminders.ReminderDeviceStore
    fun reminderController(): com.dowdah.utilitytracker.reminders.ReminderController
    fun reminderScheduler(): com.dowdah.utilitytracker.reminders.ReminderScheduler
}
