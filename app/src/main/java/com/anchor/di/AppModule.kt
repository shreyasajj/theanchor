package com.anchor.di

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.DailyLogDao
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.export.DocumentStoreFactory
import com.anchor.data.export.JoplinApi
import com.anchor.data.export.SafDocumentStore
import com.anchor.data.ha.HomeAssistantApi
import com.anchor.data.settings.SettingsProvider
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AndroidUsageStatsSource
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.EarlyLockDao
import com.anchor.data.usage.UsageStatsSource
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Central Hilt module: the clock, storage, HTTP, and the small seams the
 * domain layer depends on.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The single source of "now" for the whole app. Every piece of time-window
     * logic takes this as a dependency so tests can pin it to a fixed instant.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    // --- Room ---

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AnchorDatabase =
        Room.databaseBuilder(context, AnchorDatabase::class.java, "anchor.db")
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultQuestions.seed(db)
            })
            // Personal, unreleased app: a schema bump recreates the database.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideDailyLogDao(db: AnchorDatabase): DailyLogDao = db.dailyLogDao()

    @Provides fun provideCustomQuestionDao(db: AnchorDatabase): CustomQuestionDao = db.customQuestionDao()

    @Provides fun provideAppLimitDao(db: AnchorDatabase): AppLimitDao = db.appLimitDao()

    @Provides fun provideEarlyLockDao(db: AnchorDatabase): EarlyLockDao = db.earlyLockDao()

    // --- Settings ---

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("anchor_settings") },
        )

    /** A fresh snapshot per call, so no gate ever decides on stale settings. */
    @Provides
    fun provideSettingsProvider(repository: SettingsRepository): SettingsProvider =
        SettingsProvider { repository.current() }

    // --- HTTP ---

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        // A blocking decision must never leave the user staring at a spinner.
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // Placeholder: every call supplies an absolute @Url.
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideHomeAssistantApi(retrofit: Retrofit): HomeAssistantApi =
        retrofit.create(HomeAssistantApi::class.java)

    @Provides
    @Singleton
    fun provideJoplinApi(retrofit: Retrofit): JoplinApi = retrofit.create(JoplinApi::class.java)

    // --- Export ---

    @Provides
    @Singleton
    fun provideDocumentStoreFactory(@ApplicationContext context: Context): DocumentStoreFactory =
        DocumentStoreFactory { treeUri ->
            runCatching { SafDocumentStore(context, Uri.parse(treeUri)) }.getOrNull()
        }

    // --- Usage ---

    @Provides
    @Singleton
    fun provideUsageStatsSource(impl: AndroidUsageStatsSource): UsageStatsSource = impl
}
