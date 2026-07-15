package app.novelreader.ui

/** 相對時間，例如「3 天前」 */
fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return "尚未閱讀"
    val diff = now - timestamp
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "剛剛"
        diff < hour -> "${diff / minute} 分鐘前"
        diff < day -> "${diff / hour} 小時前"
        diff < 30 * day -> "${diff / day} 天前"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            "%d/%02d/%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }
    }
}

fun formatPercent(fraction: Float): String {
    val pct = (fraction * 100).coerceIn(0f, 100f)
    return if (pct < 0.1f) "0%" else "%.1f%%".format(pct)
}
