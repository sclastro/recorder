package com.sclastro.recorder.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Expands a user-supplied filename template. Unknown tokens are left as-is so a
 * typo shows up in the result instead of silently vanishing.
 */
object FileNaming {

    const val DEFAULT_TEMPLATE = "{date}_{time}"

    val TOKENS = listOf(
        "{date}" to "Date, e.g. 20260810",
        "{time}" to "Time, e.g. 143205",
        "{datetime}" to "Date and time, e.g. 20260810_143205",
        "{seq}" to "Sequence number, e.g. 001",
        "{preset}" to "Preset name (Meeting, Interview…)",
        "{folder}" to "Folder name",
    )

    fun expand(
        template: String,
        now: Date = Date(),
        sequence: Int = 1,
        preset: String = "",
        folder: String = "",
    ): String {
        val locale = Locale.US
        val date = SimpleDateFormat("yyyyMMdd", locale).format(now)
        val time = SimpleDateFormat("HHmmss", locale).format(now)
        val expanded = template
            .replace("{date}", date)
            .replace("{time}", time)
            .replace("{datetime}", date + "_" + time)
            .replace("{seq}", "%03d".format(sequence))
            .replace("{preset}", preset)
            .replace("{folder}", folder)
            .replace(Regex("_{2,}"), "_")
            .trim('_', ' ')
        return RecordingStorage.sanitiseName(expanded.ifBlank { date + "_" + time })
    }
}
