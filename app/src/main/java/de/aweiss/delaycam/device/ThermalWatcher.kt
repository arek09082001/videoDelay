package de.aweiss.delaycam.device

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Überwacht den Thermal-Status des Geräts (Plan Abschnitt 11): Die Engine
 * senkt ab THERMAL_STATUS_MODERATE die Encoder-Bitrate und wechselt ab
 * SEVERE auf ein kleineres Profil. Dieser Watcher liefert nur die Statuswerte
 * (`PowerManager.THERMAL_STATUS_*`) — die Policy liegt beim Aufrufer.
 *
 * Garantien:
 * - `onStatus` läuft immer auf dem Main-Thread (Main-Executor).
 * - `start()` meldet den aktuellen Status sofort, nicht erst bei der ersten Änderung.
 * - `start()` und `stop()` sind idempotent.
 */
class ThermalWatcher(
    private val context: Context,
    private val onStatus: (Int) -> Unit,
) {

    /** Aktuell registrierter Listener; null = gestoppt. */
    @Volatile
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /**
     * Zuletzt gemeldeter Status; wird nur auf dem Main-Thread angefasst.
     * Das System ruft den Listener direkt bei der Registrierung häufig selbst
     * noch einmal mit dem aktuellen Status auf — ohne dieses Dedupe käme der
     * Anfangsstatus doppelt beim Aufrufer an.
     */
    private var lastReported = Int.MIN_VALUE

    fun start() {
        if (listener != null) return // idempotent: läuft bereits
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val l = object : PowerManager.OnThermalStatusChangedListener {
            override fun onThermalStatusChanged(status: Int) = report(this, status)
        }
        listener = l
        lastReported = Int.MIN_VALUE
        // Initialen Status sofort melden — über denselben Main-Executor wie alle
        // späteren Meldungen, damit die Callback-Reihenfolge konsistent bleibt.
        val initial = pm.currentThermalStatus
        context.mainExecutor.execute { report(l, initial) }
        try {
            pm.addThermalStatusListener(context.mainExecutor, l)
        } catch (e: Exception) {
            // Kein Grund für einen Crash: Der Anfangsstatus wurde gemeldet,
            // es fehlen dann lediglich Folge-Updates.
            Log.w(TAG, "addThermalStatusListener fehlgeschlagen: ${e.message}")
        }
    }

    fun stop() {
        val l = listener ?: return // idempotent: läuft nicht
        listener = null
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        runCatching { pm.removeThermalStatusListener(l) }
    }

    /** Läuft auf dem Main-Thread (Main-Executor). */
    private fun report(source: PowerManager.OnThermalStatusChangedListener, status: Int) {
        if (source !== listener) return // veralteter Callback nach stop()/Neustart
        if (status == lastReported) return
        lastReported = status
        onStatus(status)
    }

    private companion object {
        const val TAG = "ThermalWatcher"
    }
}
