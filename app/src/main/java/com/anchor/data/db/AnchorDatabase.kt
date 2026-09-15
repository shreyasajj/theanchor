package com.anchor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.EarlyLock
import com.anchor.data.usage.EarlyLockDao
import com.anchor.data.usage.LimitMode
import com.anchor.data.usage.MeditationSession
import com.anchor.data.usage.MeditationSessionDao

class PhaseConverter {
    @TypeConverter fun toPhase(value: String): Phase = Phase.valueOf(value)
    @TypeConverter fun fromPhase(phase: Phase): String = phase.name
}

class LimitModeConverter {
    @TypeConverter fun toMode(value: String): LimitMode =
        runCatching { LimitMode.valueOf(value) }.getOrDefault(LimitMode.OPENS)
    @TypeConverter fun fromMode(mode: LimitMode): String = mode.name
}

/** A package set as one newline-joined column; package names never contain newlines. */
class PackageSetConverter {
    @TypeConverter fun toSet(value: String): Set<String> =
        value.split('\n').filter { it.isNotBlank() }.toSet()
    @TypeConverter fun fromSet(packages: Set<String>): String = packages.sorted().joinToString("\n")
}

@Database(
    entities = [
        DailyLog::class,
        CustomQuestion::class,
        AppLimit::class,
        EarlyLock::class,
        MeditationSession::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(PhaseConverter::class, LimitModeConverter::class, PackageSetConverter::class)
abstract class AnchorDatabase : RoomDatabase() {
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun customQuestionDao(): CustomQuestionDao
    abstract fun appLimitDao(): AppLimitDao
    abstract fun earlyLockDao(): EarlyLockDao
    abstract fun meditationSessionDao(): MeditationSessionDao
}
