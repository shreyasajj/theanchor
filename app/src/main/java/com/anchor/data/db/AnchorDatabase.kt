package com.anchor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.EarlyLock
import com.anchor.data.usage.EarlyLockDao
import com.anchor.data.usage.MeditationSession
import com.anchor.data.usage.MeditationSessionDao

class PhaseConverter {
    @TypeConverter fun toPhase(value: String): Phase = Phase.valueOf(value)
    @TypeConverter fun fromPhase(phase: Phase): String = phase.name
}

@Database(
    entities = [
        DailyLog::class,
        CustomQuestion::class,
        AppLimit::class,
        EarlyLock::class,
        MeditationSession::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(PhaseConverter::class)
abstract class AnchorDatabase : RoomDatabase() {
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun customQuestionDao(): CustomQuestionDao
    abstract fun appLimitDao(): AppLimitDao
    abstract fun earlyLockDao(): EarlyLockDao
    abstract fun meditationSessionDao(): MeditationSessionDao
}
