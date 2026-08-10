package com.sclastro.recorder.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** mm:ss, or h:mm:ss once it runs past an hour. */
fun formatDuration(ms: Long): String {
    val total = abs(ms) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Same, plus hundredths — for the running timer and edit handles. */
fun formatDurationPrecise(ms: Long): String {
    val total = abs(ms)
    val hours = total / 3_600_000
    val minutes = (total % 3_600_000) / 60_000
    val seconds = (total % 60_000) / 1000
    val hundredths = (total % 1000) / 10
    return if (hours > 0) {
        "%d:%02d:%02d.%02d".format(hours, minutes, seconds, hundredths)
    } else {
        "%02d:%02d.%02d".format(minutes, seconds, hundredths)
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
private val yearFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

/** "今日 14:32" for recent items, dropping to a full date further back. */
fun formatTimestamp(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val yesterday = sameYear && now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1
    return when {
        sameDay -> "今日 " + timeFormat.format(Date(millis))
        yesterday -> "尋日 " + timeFormat.format(Date(millis))
        sameYear -> dateFormat.format(Date(millis))
        else -> yearFormat.format(Date(millis))
    }
}
