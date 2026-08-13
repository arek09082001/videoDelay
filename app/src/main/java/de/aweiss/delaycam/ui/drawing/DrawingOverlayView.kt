package de.aweiss.delaycam.ui.drawing

import android.content.Context
import android.graphics.Canvas
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
 * Die View hängt nur im Baum, **solange der Stift aktiv ist** — dann liegt sie zuoberst über dem
 * Bild und bekommt die Berührungen zuverlässig. Ist der Stift aus, zeichnet Compose die Striche
 * aus demselben [DrawingBoard], und im Baum steht nichts, was den Wischgesten dazwischenfunken
 * könnte. Deshalb liegen die Striche im Board und nicht in dieser View.
 *
 * Der S Pen liefert Druckwerte. Da ein `Path` nur eine Strichbreite kennt, wird ein Freihandzug
 * bei deutlicher Druckänderung in Teilstücke zerlegt (jedes mit eigener Breite). Alle Teilstücke
 * eines Zuges teilen sich eine Gruppen-Nummer — Undo nimmt immer den ganzen Zug zurück.
 */
class DrawingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Tool { FREIHAND, LINIE, PFEIL, KREIS, RECHTECK }

    /**
     * Gemeinsamer Strich-Speicher; muss vor der ersten Berührung gesetzt sein.
     *
     * Beim Setzen wird der Rückkanal umgehängt: Ändert jemand von außen etwas am Board
     * (Rückgängig, Wiederholen, Alles löschen aus der Werkzeugleiste), muss diese View neu
     * zeichnen — von allein merkt sie davon nichts.
     */
    var board: DrawingBoard? = null
        set(value) {
            if (field === value) return
            field?.onChanged = null
            field = value
            value?.onChanged = { invalidate() }
            invalidate()
        }

    private var tool = Tool.FREIHAND
    private var color = android.graphics.Color.WHITE
    private var strokeWidthPx = dp(6f)

    /** Wird beim ersten Punkt eines Zuges gerufen — die UI friert daraufhin das Bild ein. */
    var onStrokeStarted: (() -> Unit)? = null

    /** Zwei-Finger-Tipp: „zurück zu LIVE" muss auch bei aktivem Stift erreichbar sein (Plan §5). */
    var onTwoFingerTap: (() -> Unit)? = null

    // ─── Zustand des laufenden Zuges ───
    private var activePath: Path? = null
    private var activePaint: Paint? = null
    private var activeOutline: Paint? = null
    private var activeGroup = 0
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        board?.onChanged = { invalidate() }
    }

    override fun onDetachedFromWindow() {
        // Stift aus: Ab jetzt zeichnet Compose. Der Rückkanal auf diese tote View muss weg,
        // sonst zeigt ein späteres „Rückgängig" ins Leere.
        board?.onChanged = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        val b = board ?: return
        onStrokeStarted?.invoke()
        activeGroup = b.beginGroup()
        startX = event.x
        startY = event.y
        lastX = startX
        lastY = startY
        segmentPressure = pressureOf(event)
        activePath = Path().apply { moveTo(startX, startY) }
        newPaints(segmentPressure)
        invalidate()
    }

    private fun continueStroke(event: MotionEvent) {
        val b = board ?: return
        val path = activePath ?: return
        val paint = activePaint ?: return
        val outline = activeOutline ?: return

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
                b.add(DrawingBoard.Stroke(path, paint, outline, activeGroup))
                segmentPressure = pressure
                activePath = Path().apply { moveTo(lastX, lastY) }
                newPaints(pressure)
            }
        } else {
            rebuildShape(path, event.x, event.y)
            lastX = event.x
            lastY = event.y
        }
        invalidate()
    }

    private fun finishStroke(event: MotionEvent) {
        val b = board ?: return
        val path = activePath ?: return
        val paint = activePaint ?: return
        val outline = activeOutline ?: return
        if (tool == Tool.FREIHAND) {
            addFreehandPoint(path, event.x, event.y)
            // Ein reiner Tipp ohne Bewegung wäre unsichtbar — einen Punkt setzen.
            if (hypot(event.x - startX, event.y - startY) < 1f) {
                path.lineTo(event.x + 0.5f, event.y + 0.5f)
            }
        } else {
            rebuildShape(path, event.x, event.y)
        }
        b.add(DrawingBoard.Stroke(path, paint, outline, activeGroup))
        activePath = null
        activePaint = null
        activeOutline = null
        invalidate()
    }

    private fun cancelCurrent() {
        if (activePath != null) board?.dropGroup(activeGroup)
        activePath = null
        activePaint = null
        activeOutline = null
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

    private fun newPaints(pressure: Float) {
        val (fill, outline) = DrawingBoard.paints(color, strokeWidthPx * pressure)
        activePaint = fill
        activeOutline = outline
    }

    /** Druck nur beim Stift auswerten (Finger melden konstant 1,0): 0..1 → Faktor 0,6..1,6. */
    private fun pressureOf(event: MotionEvent): Float {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return 1f
        if (tool != Tool.FREIHAND) return 1f
        return (0.6f + event.pressure.coerceIn(0f, 1f)).coerceIn(0.6f, 1.6f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        board?.draw(canvas)
        val path = activePath
        val paint = activePaint
        val outline = activeOutline
        if (path != null && paint != null && outline != null) {
            canvas.drawPath(path, outline)
            canvas.drawPath(path, paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Ab dieser Druckänderung beginnt ein neues Teilstück. */
        const val PRESSURE_STEP = 0.15f
    }
}
