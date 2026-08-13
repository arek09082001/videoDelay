package de.aweiss.delaycam.ui.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Zeichenebene über dem Video (Plan §6) — bewusst eine klassische `View` und **kein**
 * Compose-Canvas: Nur hier gibt es `requestUnbufferedDispatch()`, und das ist der Unterschied
 * zwischen „der Strich klebt am Stift" und „der Strich hinkt hinterher".
 *
 * Der S Pen liefert Druckwerte. Da ein `Path` nur eine Strichbreite kennt, wird ein Freihandzug
 * bei deutlicher Druckänderung in Teilstücke zerlegt (jedes mit eigener Breite). Alle Teilstücke
 * eines Zuges teilen sich eine `groupId` — Undo nimmt immer den ganzen Zug zurück, nie ein
 * einzelnes Teilstück.
 */
class DrawingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Tool { FREIHAND, LINIE, PFEIL, KREIS, RECHTECK }

    private class Stroke(val path: Path, val paint: Paint, val groupId: Int)

    private val strokes = ArrayList<Stroke>(64)
    private val undone = ArrayList<Stroke>(64)

    private var tool = Tool.FREIHAND
    private var color = Color.WHITE
    private var strokeWidthPx = dp(6f)

    /**
     * Heißt bewusst nicht `enabled`: `View` besitzt bereits `setEnabled()`, die Signaturen
     * würden sich auf JVM-Ebene überschreiben.
     */
    var drawingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) cancelCurrent()
        }

    /** Wird beim ersten Punkt eines Zuges gerufen — die UI friert daraufhin das Bild ein. */
    var onStrokeStarted: (() -> Unit)? = null

    /** Zwei-Finger-Tipp auch bei aktivem Stift (Plan §5: „LIVE ist überall erreichbar"). */
    var onTwoFingerTap: (() -> Unit)? = null

    // ─── Zustand des laufenden Zuges ───
    private var activePath: Path? = null
    private var activePaint: Paint? = null
    private var groupCounter = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var segmentPressure = 1f
    private var multiTouch = false
    private var moved = false
    private var downTimeMs = 0L

    init {
        // Eigene Hardware-Ebene: Das Neuzeichnen der bisherigen Striche kostet damit nichts.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusable = false
    }

    fun setTool(t: Tool) {
        tool = t
    }

    fun setColor(c: Int) {
        color = c
    }

    fun setStrokeWidthDp(widthDp: Float) {
        strokeWidthPx = dp(widthDp)
    }

    fun undo() {
        if (strokes.isEmpty()) return
        val group = strokes.last().groupId
        while (strokes.isNotEmpty() && strokes.last().groupId == group) {
            undone.add(strokes.removeAt(strokes.size - 1))
        }
        invalidate()
    }

    fun redo() {
        if (undone.isEmpty()) return
        val group = undone.last().groupId
        while (undone.isNotEmpty() && undone.last().groupId == group) {
            strokes.add(undone.removeAt(undone.size - 1))
        }
        invalidate()
    }

    fun clearAll() {
        if (strokes.isEmpty() && undone.isEmpty() && activePath == null) return
        strokes.clear()
        undone.clear()
        cancelCurrent()
        invalidate()
    }

    fun hasContent(): Boolean = strokes.isNotEmpty()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Der eigentliche Latenz-Trick: ungepufferte Events statt einmal pro Frame.
                requestUnbufferedDispatch(event)
                multiTouch = false
                moved = false
                downTimeMs = event.eventTime
                beginStroke(event)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Zweiter Finger: der Zug war nicht gewollt — verwerfen.
                multiTouch = true
                cancelCurrent()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) {
                    if (hypot(event.x - startX, event.y - startY) > dp(8f)) moved = true
                    return true
                }
                continueStroke(event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (multiTouch) {
                    val quick = event.eventTime - downTimeMs < 600
                    if (!moved && quick) onTwoFingerTap?.invoke()
                    multiTouch = false
                    return true
                }
                finishStroke(event)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelCurrent()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun beginStroke(event: MotionEvent) {
        onStrokeStarted?.invoke()
        undone.clear()
        groupCounter++
        startX = event.x
        startY = event.y
        lastX = startX
        lastY = startY
        segmentPressure = pressureOf(event)
        val path = Path().apply { moveTo(startX, startY) }
        activePath = path
        activePaint = newPaint(segmentPressure)
        invalidate()
    }

    private fun continueStroke(event: MotionEvent) {
        val path = activePath ?: return
        val paint = activePaint ?: return

        if (tool == Tool.FREIHAND) {
            // Zwischenpunkte mitnehmen (der Stift liefert mehr Punkte als Frames) und weich
            // durch die Punkte legen: Kontrollpunkt = alter Punkt, Ziel = Mitte zum neuen Punkt.
            for (i in 0 until event.historySize) {
                addFreehandPoint(path, event.getHistoricalX(i), event.getHistoricalY(i))
            }
            addFreehandPoint(path, event.x, event.y)

            val pressure = pressureOf(event)
            if (kotlin.math.abs(pressure - segmentPressure) > PRESSURE_STEP) {
                // Neues Teilstück mit anderer Breite; es beginnt exakt am letzten Punkt,
                // damit keine Lücke entsteht.
                strokes.add(Stroke(path, paint, groupCounter))
                segmentPressure = pressure
                activePath = Path().apply { moveTo(lastX, lastY) }
                activePaint = newPaint(pressure)
            }
        } else {
            rebuildShape(path, event.x, event.y)
            lastX = event.x
            lastY = event.y
        }
        invalidate()
    }

    private fun finishStroke(event: MotionEvent) {
        val path = activePath ?: return
        val paint = activePaint ?: return
        if (tool == Tool.FREIHAND) {
            addFreehandPoint(path, event.x, event.y)
            // Ein reiner Tipp ohne Bewegung wäre unsichtbar — einen Punkt setzen.
            if (hypot(event.x - startX, event.y - startY) < 1f) {
                path.lineTo(event.x + 0.5f, event.y + 0.5f)
            }
        } else {
            rebuildShape(path, event.x, event.y)
        }
        strokes.add(Stroke(path, paint, groupCounter))
        activePath = null
        activePaint = null
        invalidate()
    }

    private fun cancelCurrent() {
        // Auch bereits abgelegte Teilstücke dieses Zuges wieder entfernen.
        while (strokes.isNotEmpty() && strokes.last().groupId == groupCounter && activePath != null) {
            strokes.removeAt(strokes.size - 1)
        }
        activePath = null
        activePaint = null
    }

    private fun addFreehandPoint(path: Path, x: Float, y: Float) {
        path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
        lastX = x
        lastY = y
    }

    /** Formen werden bei jeder Bewegung komplett neu aufgebaut — sie bestehen aus wenigen Segmenten. */
    private fun rebuildShape(path: Path, x: Float, y: Float) {
        path.reset()
        when (tool) {
            Tool.LINIE -> {
                path.moveTo(startX, startY)
                path.lineTo(x, y)
            }

            Tool.PFEIL -> {
                path.moveTo(startX, startY)
                path.lineTo(x, y)
                addArrowHead(path, x, y)
            }

            Tool.KREIS -> path.addOval(
                minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y),
                Path.Direction.CW,
            )

            Tool.RECHTECK -> path.addRect(
                minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y),
                Path.Direction.CW,
            )

            Tool.FREIHAND -> Unit
        }
    }

    /** Zwei Schenkel im 30°-Winkel, Länge 4 × Strichbreite + 12 dp (Vertrag §9). */
    private fun addArrowHead(path: Path, tipX: Float, tipY: Float) {
        val angle = atan2(tipY - startY, tipX - startX)
        val length = 4f * strokeWidthPx + dp(12f)
        val spread = Math.toRadians(30.0).toFloat()
        for (side in intArrayOf(-1, 1)) {
            val a = angle + side * spread
            path.moveTo(tipX, tipY)
            path.lineTo(tipX - length * cos(a), tipY - length * sin(a))
        }
    }

    private fun newPaint(pressure: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = this@DrawingOverlayView.color
        strokeWidth = strokeWidthPx * pressure
        // Dunkler Rand macht die Linie auch auf hellem Hallenboden sichtbar.
        setShadowLayer(strokeWidthPx * 0.6f, 0f, 0f, Color.argb(160, 0, 0, 0))
    }

    /**
     * Druck nur beim Stift auswerten (Finger melden konstant 1,0): 0..1 → Faktor 0,6..1,6.
     */
    private fun pressureOf(event: MotionEvent): Float {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return 1f
        if (tool != Tool.FREIHAND) return 1f
        return (0.6f + event.pressure.coerceIn(0f, 1f)).coerceIn(0.6f, 1.6f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in strokes.indices) {
            val s = strokes[i]
            canvas.drawPath(s.path, s.paint)
        }
        val path = activePath
        val paint = activePaint
        if (path != null && paint != null) canvas.drawPath(path, paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Ab dieser Druckänderung beginnt ein neues Teilstück. */
        const val PRESSURE_STEP = 0.15f
    }
}
