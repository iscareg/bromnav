package nl.bromnav

import android.content.Context

/** What you ride: decides which roads are legal (profile variable "vehicle") and the speed. */
enum class Vehicle(val code: Int, val label: String, val short: String, val pathName: String, val icon: String) {
    YELLOW(0, "Bromfiets – yellow plate (45 km/h)", "bromfiets", "moped path", "🛵"),
    BLUE(1, "Snorfiets – blue plate (25 km/h)", "snorfiets", "cycle path", "🛵"),
    EBIKE(2, "E-bike up to 25 km/h (no plate)", "e-bike", "cycle path", "🚲");

    companion object {
        @Volatile var current = YELLOW
        fun load(ctx: Context) {
            val c = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("vehicle", 0)
            current = entries.firstOrNull { it.code == c } ?: YELLOW
        }
        fun set(ctx: Context, v: Vehicle) {
            current = v
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("vehicle", v.code).apply()
        }
    }
}
