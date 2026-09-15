package com.anchor.ui.lock

import android.content.Context
import com.anchor.data.usage.AppLimit

/** Human names for packages and limits, for the lock screens. */
object AppLabels {

    fun app(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /** A limit's own name, or its members' names joined, or the app in front. */
    fun limit(context: Context, limit: AppLimit?, packageName: String?): String? {
        if (limit == null) return packageName?.let { app(context, it) }
        limit.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val names = limit.packages.mapNotNull { app(context, it) }.sortedBy { it.lowercase() }
        return when {
            names.isEmpty() -> packageName?.let { app(context, it) }
            names.size <= 2 -> names.joinToString(" & ")
            else -> "${names[0]}, ${names[1]} +${names.size - 2}"
        }
    }
}
