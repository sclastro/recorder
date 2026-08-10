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
        "{date}" to "日期，例如 20260810",
        "{time}" to "時間，例如 143205",
        "{datetime}" to "日期時間，例如 20260810_143205",
        "{seq}" to "流水號，例如 001",
        "{preset}" to "預設名（會議、訪問…）",
        "{folder}" to "資料夾名",
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
