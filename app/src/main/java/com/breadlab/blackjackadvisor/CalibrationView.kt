package com.breadlab.blackjackadvisor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay view used during calibration. Three independent draggable +
 * resizable rectangles:
 *   - 🟥 DEALER (red)
 *   - 🟩 YOUR CARDS (green)
 *   - 🟨 BALANCE (yellow)
 *
 * Drag anywhere inside a rect to move it. Drag the bottom-right square handle to resize.
 * Areas outside all rects are dimmed so the user can see what's being cropped to.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dealerRect = RectF()
    private val playerRect = RectF()
    private val balanceRect = RectF()
    private var initialized = false

    // Default fractional positions for first-time setup.
    private var pendingDealerLeft = 0.10f
    private var pendingDealerTop = 0.18f
    private var pendingDealerRight = 0.90f
    private var pendingDealerBottom = 0.34f
    private var pendingPlayerLeft = 0.10f
    private var pendingPlayerTop = 0.40f
    private var pendingPlayerRight = 0.90f
    private var pendingPlayerBottom = 0.56f
    private var pendingBalanceLeft = 0.15f
    private var pendingBalanceTop = 0.06f
    private var pendingBalanceRight = 0.60f
    private var pendingBalanceBottom = 0.11f

    private val dimPaint = Paint().apply {
        color = Color.argb(170, 0, 0, 0)
    }

    private val dealerBorderPaint = strokePaint("#E74C3C")
    private val dealerHandlePaint = fillPaint("#E74C3C")
    private val playerBorderPaint = strokePaint("#27AE60")
    private val playerHandlePaint = fillPaint("#27AE60")
    private val balanceBorderPaint = strokePaint("#F39C12")
    private val balanceHandlePaint = fillPaint("#F39C12")

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val handleSize = 90f
    private val minRectSize = 100f

    private enum class Mode {
        NONE,
        DRAG_DEALER, RESIZE_DEALER,
        DRAG_PLAYER, RESIZE_PLAYER,
        DRAG_BALANCE, RESIZE_BALANCE
    }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun strokePaint(hex: String) = Paint().apply {
        color = Color.parseColor(hex)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private fun fillPaint(hex: String) = Paint().apply {
        color = Color.parseColor(hex)
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            applyPending()
            initialized = true
            invalidate()
        }
    }

    private fun applyPending() {
        dealerRect.set(width * pendingDealerLeft, height * pendingDealerTop,
            width * pendingDealerRight, height * pendingDealerBottom)
        playerRect.set(width * pendingPlayerLeft, height * pendingPlayerTop,
            width * pendingPlayerRight, height * pendingPlayerBottom)
        balanceRect.set(width * pendingBalanceLeft, height * pendingBalanceTop,
            width * pendingBalanceRight, height * pendingBalanceBottom)
    }

    fun setRects(
        dealerLeft: Float, dealerTop: Float, dealerRight: Float, dealerBottom: Float,
        playerLeft: Float, playerTop: Float, playerRight: Float, playerBottom: Float,
        balanceLeft: Float, balanceTop: Float, balanceRight: Float, balanceBottom: Float
    ) {
        pendingDealerLeft = dealerLeft; pendingDealerTop = dealerTop
        pendingDealerRight = dealerRight; pendingDealerBottom = dealerBottom
        pendingPlayerLeft = playerLeft; pendingPlayerTop = playerTop
        pendingPlayerRight = playerRight; pendingPlayerBottom = playerBottom
        pendingBalanceLeft = balanceLeft; pendingBalanceTop = balanceTop
        pendingBalanceRight = balanceRight; pendingBalanceBottom = balanceBottom
        if (width > 0 && height > 0) {
            applyPending()
            initialized = true
            invalidate()
        }
    }

    fun getDealerFractions(): FloatArray = rectAsFractions(dealerRect)
    fun getPlayerFractions(): FloatArray = rectAsFractions(playerRect)
    fun getBalanceFractions(): FloatArray = rectAsFractions(balanceRect)

    private fun rectAsFractions(r: RectF): FloatArray {
        if (width == 0 || height == 0) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(r.left / width, r.top / height, r.right / width, r.bottom / height)
    }

    override fun onDraw(canvas: Canvas) {
        // Dim everything outside all three rects via EVEN_ODD path.
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(dealerRect, Path.Direction.CCW)
            addRect(playerRect, Path.Direction.CCW)
            addRect(balanceRect, Path.Direction.CCW)
        }
        canvas.drawPath(path, dimPaint)

        drawRectWithLabel(canvas, dealerRect, "DEALER", dealerBorderPaint, dealerHandlePaint)
        drawRectWithLabel(canvas, playerRect, "YOUR CARDS", playerBorderPaint, playerHandlePaint)
        drawRectWithLabel(canvas, balanceRect, "BALANCE", balanceBorderPaint, balanceHandlePaint)
    }

    private fun drawRectWithLabel(
        canvas: Canvas, rect: RectF, label: String,
        border: Paint, handle: Paint
    ) {
        canvas.drawRect(rect, border)
        canvas.drawText(
            label,
            (rect.left + rect.right) / 2f,
            rect.top + 40f,
            labelPaint
        )
        canvas.drawRect(
            rect.right - handleSize, rect.bottom - handleSize,
            rect.right, rect.bottom,
            handle
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                mode = when {
                    isInResizeHandle(x, y, dealerRect) -> Mode.RESIZE_DEALER
                    isInResizeHandle(x, y, playerRect) -> Mode.RESIZE_PLAYER
                    isInResizeHandle(x, y, balanceRect) -> Mode.RESIZE_BALANCE
                    dealerRect.contains(x, y) -> Mode.DRAG_DEALER
                    playerRect.contains(x, y) -> Mode.DRAG_PLAYER
                    balanceRect.contains(x, y) -> Mode.DRAG_BALANCE
                    else -> Mode.NONE
                }
                lastX = x; lastY = y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (mode) {
                    Mode.DRAG_DEALER   -> dragRect(dealerRect, dx, dy)
                    Mode.RESIZE_DEALER -> resizeRect(dealerRect, dx, dy)
                    Mode.DRAG_PLAYER   -> dragRect(playerRect, dx, dy)
                    Mode.RESIZE_PLAYER -> resizeRect(playerRect, dx, dy)
                    Mode.DRAG_BALANCE  -> dragRect(balanceRect, dx, dy)
                    Mode.RESIZE_BALANCE-> resizeRect(balanceRect, dx, dy)
                    Mode.NONE -> {}
                }
                if (mode != Mode.NONE) invalidate()
                lastX = event.x; lastY = event.y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return false
    }

    private fun isInResizeHandle(x: Float, y: Float, rect: RectF): Boolean {
        return x in (rect.right - handleSize)..(rect.right + 30f) &&
                y in (rect.bottom - handleSize)..(rect.bottom + 30f)
    }

    private fun dragRect(rect: RectF, dx: Float, dy: Float) {
        val w = rect.width(); val h = rect.height()
        val newLeft = max(0f, min(width - w, rect.left + dx))
        val newTop = max(0f, min(height - h, rect.top + dy))
        rect.set(newLeft, newTop, newLeft + w, newTop + h)
    }

    private fun resizeRect(rect: RectF, dx: Float, dy: Float) {
        val newRight = max(rect.left + minRectSize, min(width.toFloat(), rect.right + dx))
        val newBottom = max(rect.top + minRectSize, min(height.toFloat(), rect.bottom + dy))
        rect.set(rect.left, rect.top, newRight, newBottom)
    }
}
