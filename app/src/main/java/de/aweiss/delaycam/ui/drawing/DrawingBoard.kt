package de.aweiss.delaycam.ui.drawing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Speicher der gezeichneten Striche — bewusst **außerhalb** der View.
 *
 * Grund: Die Zeichenebene ist nur dann eine echte `View` (wegen
 * `requestUnbufferedDispatch()` für die Stift-Latenz), wenn der Stift aktiv ist. Ist er aus,
 * zeichnet Compose dieselben Striche selbst — dann hängt keine View im Baum, die Berührungen
 * abfangen könnte, und die Wischgesten bleiben unangetastet. Beide Darstellungen greifen auf
 * diesen einen Speicher zu, deshalb überlebt die Zeichnung den Wechsel.
 */
class DrawingBoard {

    /** Ein Strichstück: dunkle Kontur zuerst, dann die Farbe darüber. */
    class Stroke(val path: Path, val paint: Paint, val outline: Paint, val group: Int)

    private val strokes = ArrayList<Stroke>(64)
    private val undone = ArrayList<Stroke>(64)
    private var groupCounter = 0

    /**
     * Änderungszähler. Compose liest ihn beim Zeichnen und rendert neu, sobald er sich ändert —
     * `Path`/`Paint` selbst sind keine Snapshot-Objekte und würden keine Neuzeichnung auslösen.
     */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Meldet Änderungen an die Zeichen-`View`. Compose merkt Änderungen von selbst über
     * [version] — eine klassische View nicht: Sie zeichnet nur neu, wenn jemand `invalidate()`
     * ruft. Ohne diesen Rückkanal ändern Rückgängig und Löschen zwar die Daten, auf dem Schirm
     * bliebe aber das alte Bild stehen. Wird von der View beim Anhängen gesetzt.
     */
    var onChanged: (() -> Unit)? = null

    /** Beginnt einen neuen Zug (ein Zug kann aus mehreren Druckstufen-Teilstücken bestehen). */
    fun beginGroup(): Int {
        undone.clear()
        groupCounter++
        return groupCounter
    }

    fun add(stroke: Stroke) {
        strokes.add(stroke)
        bump()
    }

    /** Bricht einen laufenden Zug ab (zweiter Finger, Abbruch durch das System). */
    fun dropGroup(group: Int) {
        var changed = false
        while (strokes.isNotEmpty() && strokes.last().group == group) {
            strokes.removeAt(strokes.size - 1)
            changed = true
        }
        if (changed) bump()
    }

    /** Nimmt immer den ganzen Zug zurück, nie ein einzelnes Teilstück. */
    fun undo() {
        if (strokes.isEmpty()) return
        val group = strokes.last().group
        while (strokes.isNotEmpty() && strokes.last().group == group) {
            undone.add(strokes.removeAt(strokes.size - 1))
        }
        bump()
    }

    fun redo() {
        if (undone.isEmpty()) return
        val group = undone.last().group
        while (undone.isNotEmpty() && undone.last().group == group) {
            strokes.add(undone.removeAt(undone.size - 1))
        }
        bump()
    }

    fun clearAll() {
        if (strokes.isEmpty() && undone.isEmpty()) return
        strokes.clear()
        undone.clear()
        bump()
    }

    fun isEmpty(): Boolean = strokes.isEmpty()

    /** Beide Renderer anstoßen: Compose über den Zähler, die View über ihren Rückkanal. */
    private fun bump() {
        version++
        onChanged?.invoke()
    }

    fun draw(canvas: Canvas) {
        for (i in strokes.indices) {
            val s = strokes[i]
            canvas.drawPath(s.path, s.outline)
            canvas.drawPath(s.path, s.paint)
        }
    }

    companion object {
        /**
         * Farbe und dunkle Kontur als Paar. Die Kontur ersetzt `setShadowLayer` — das wirkt auf
         * einer hardwarebeschleunigten Ebene nur bei Text, bei Pfaden wird es ignoriert. Zwei
         * Striche übereinander sind dagegen überall sichtbar, auch auf hellem Hallenboden.
         */
        fun paints(color: Int, widthPx: Float): Pair<Paint, Paint> {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
                strokeWidth = widthPx
            }
            val outline = Paint(fill).apply {
                this.color = Color.argb(150, 0, 0, 0)
                strokeWidth = widthPx + widthPx * 0.7f + 2f
            }
            return fill to outline
        }
    }
}
