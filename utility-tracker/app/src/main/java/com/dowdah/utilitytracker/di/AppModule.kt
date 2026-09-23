package com.dowdah.utilitytracker.di

import android.content.Context
import androidx.room.Room
import com.dowdah.utilitytracker.data.UtilityDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): UtilityDatabase = Room.databaseBuilder(
        context, UtilityDatabase::class.java, "utility-tracker.db",
    ).addMigrations(com.dowdah.utilitytracker.data.MIGRATION_1_2).build()
}
