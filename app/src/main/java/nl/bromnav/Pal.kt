package nl.bromnav

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Light / dark palette for the hand-built UI and the map. Theme choice: auto (follow the phone),
 * light or dark; stored in prefs and applied through AppCompat's night mode.
 */
object Pal {
    var dark = false; private set

    /** "auto" | "light" | "dark" */
    fun choice(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", "auto")!!

    fun setChoice(ctx: Context, c: String) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", c).apply()
        apply(c)
    }

    fun apply(c: String) = AppCompatDelegate.setDefaultNightMode(when (c) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    })

    /** Call in onCreate after the night mode was applied. */
    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun c(light: String, dark: String) = if (this.dark) dark else light
    val card get() = android.graphics.Color.parseColor(c("#FFFFFF", "#1E2228"))
    val chip get() = android.graphics.Color.parseColor(c("#FFFFFF", "#2A2F37"))
    val chip2 get() = android.graphics.Color.parseColor(c("#E9ECF1", "#323842"))
    val page get() = android.graphics.Color.parseColor(c("#FFFFFF", "#121417"))
    val text get() = c("#1B1B1B", "#ECEFF4")
    val sub get() = c("#666666", "#A0A8B4")
    val accent get() = c("#14335E", "#8AB4FF")
    val note get() = c("#5B2A86", "#C9A6F0")
    val faint get() = c("#BBBBBB", "#5C636E")
    val good get() = c("#1B5E20", "#7BD88F")
}
