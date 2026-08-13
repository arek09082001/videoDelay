package de.aweiss.delaycam

import android.content.Context

/**
 * Persistente Nutzer-Einstellungen (Vertrag Abschnitt 10, Agent aux).
 * Gespeichert in den SharedPreferences `"delaycam"`; `apply()` reicht laut
 * Vertrag — die Werte sind unkritisch und schreiben asynchron.
 *
 * Die Default-Werte leben genau an einer Stelle: in den Default-Argumenten
 * der data class. `load()` benutzt sie als Fallback pro Schlüssel — eine
 * frische Installation liefert damit exakt `AppSettings()`.
 */
data class AppSettings(
    val profileMode: String = "auto",       // "auto" | "1080p60" | "1080p30" | "720p60" | "720p30"
    val bufferSeconds: Int = 60,            // 30/60/120
    val sportExposure: Boolean = false,
    val miniPreview: Boolean = true,
    val freezeOnDraw: Boolean = true,
    val clearDrawingOnLive: Boolean = true,
    val delayUs: Long = 5_000_000,          // zuletzt genutzter Delay
    val firstRunDone: Boolean = false,
) {

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILE_MODE, profileMode)
            .putInt(KEY_BUFFER_SECONDS, bufferSeconds)
            .putBoolean(KEY_SPORT_EXPOSURE, sportExposure)
            .putBoolean(KEY_MINI_PREVIEW, miniPreview)
            .putBoolean(KEY_FREEZE_ON_DRAW, freezeOnDraw)
            .putBoolean(KEY_CLEAR_DRAWING_ON_LIVE, clearDrawingOnLive)
            .putLong(KEY_DELAY_US, delayUs)
            .putBoolean(KEY_FIRST_RUN_DONE, firstRunDone)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "delaycam"

        private const val KEY_PROFILE_MODE = "profileMode"
        private const val KEY_BUFFER_SECONDS = "bufferSeconds"
        private const val KEY_SPORT_EXPOSURE = "sportExposure"
        private const val KEY_MINI_PREVIEW = "miniPreview"
        private const val KEY_FREEZE_ON_DRAW = "freezeOnDraw"
        private const val KEY_CLEAR_DRAWING_ON_LIVE = "clearDrawingOnLive"
        private const val KEY_DELAY_US = "delayUs"
        private const val KEY_FIRST_RUN_DONE = "firstRunDone"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val defaults = AppSettings()
            return AppSettings(
                profileMode = prefs.getString(KEY_PROFILE_MODE, defaults.profileMode)
                    ?: defaults.profileMode,
                bufferSeconds = prefs.getInt(KEY_BUFFER_SECONDS, defaults.bufferSeconds),
                sportExposure = prefs.getBoolean(KEY_SPORT_EXPOSURE, defaults.sportExposure),
                miniPreview = prefs.getBoolean(KEY_MINI_PREVIEW, defaults.miniPreview),
                freezeOnDraw = prefs.getBoolean(KEY_FREEZE_ON_DRAW, defaults.freezeOnDraw),
                clearDrawingOnLive = prefs.getBoolean(
                    KEY_CLEAR_DRAWING_ON_LIVE,
                    defaults.clearDrawingOnLive
                ),
                delayUs = prefs.getLong(KEY_DELAY_US, defaults.delayUs),
                firstRunDone = prefs.getBoolean(KEY_FIRST_RUN_DONE, defaults.firstRunDone),
            )
        }
    }
}
