package com.breadlab.blackjackadvisor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var calibrationView: View? = null
    private var isExpanded = false
    private var isAutoDetect = false

    private val playerCards = mutableListOf<Int>()
    private var dealerCard: Int = 0

    private val cardDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Always update the diagnostic readout so we can see whether captures are
            // arriving and what OCR is reading — regardless of mode.
            val frame = intent?.getIntExtra(ScreenCaptureService.EXTRA_FRAME_COUNT, 0) ?: 0
            val total = intent?.getIntExtra(ScreenCaptureService.EXTRA_TOTAL_TEXT, 0) ?: 0
            val raw = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_TEXT)
                ?: arrayListOf()
            val rawSummary = if (raw.isEmpty()) "(no text in zone)"
                             else raw.take(8).joinToString(",")
            updateScanStatus("f$frame • $total seen • [$rawSummary]")

            // Card-state updates: same gating as before.
            if (isExpanded && !isAutoDetect) return

            val detected = intent?.getIntegerArrayListExtra(ScreenCaptureService.EXTRA_PLAYER_CARDS)
            val dealer = intent?.getIntExtra(ScreenCaptureService.EXTRA_DEALER_CARD, 0) ?: 0

            if (!detected.isNullOrEmpty() || dealer > 0) {
                if (!detected.isNullOrEmpty()) {
                    playerCards.clear()
                    playerCards.addAll(detected)
                }
                if (dealer > 0) dealerCard = dealer
                updateAdvice()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlay()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cardDetectionReceiver,
            IntentFilter(ScreenCaptureService.ACTION_CARDS_DETECTED)
        )
    }

    private fun createOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        setupDragBehavior(params)
        setupButtonListeners()
        windowManager.addView(overlayView, params)
        updateCollapsedView()
    }

    private fun setupDragBehavior(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView.findViewById<View>(R.id.fab_toggle).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) toggleExpanded()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtonListeners() {
        val cardButtons = mapOf(
            R.id.btn_ace to 1, R.id.btn_2 to 2, R.id.btn_3 to 3,
            R.id.btn_4 to 4, R.id.btn_5 to 5, R.id.btn_6 to 6,
            R.id.btn_7 to 7, R.id.btn_8 to 8, R.id.btn_9 to 9, R.id.btn_10 to 10
        )
        cardButtons.forEach { (btnId, value) ->
            overlayView.findViewById<Button>(btnId)?.setOnClickListener {
                if (!isAutoDetect && playerCards.size < 8) {
                    playerCards.add(value)
                    updateAdvice()
                }
            }
        }

        val dealerButtons = mapOf(
            R.id.dealer_ace to 1, R.id.dealer_2 to 2, R.id.dealer_3 to 3,
            R.id.dealer_4 to 4, R.id.dealer_5 to 5, R.id.dealer_6 to 6,
            R.id.dealer_7 to 7, R.id.dealer_8 to 8, R.id.dealer_9 to 9, R.id.dealer_10 to 10
        )
        dealerButtons.forEach { (btnId, value) ->
            overlayView.findViewById<Button>(btnId)?.setOnClickListener {
                if (!isAutoDetect) {
                    dealerCard = value
                    updateAdvice()
                }
            }
        }

        overlayView.findViewById<Button>(R.id.btn_clear)?.setOnClickListener {
            playerCards.clear()
            dealerCard = 0
            updateAdvice()
            updatePlayerCardsDisplay()
        }

        overlayView.findViewById<Button>(R.id.btn_undo)?.setOnClickListener {
            if (!isAutoDetect && playerCards.isNotEmpty()) {
                playerCards.removeAt(playerCards.size - 1)
                updateAdvice()
            }
        }

        overlayView.findViewById<Button>(R.id.btn_toggle_auto)?.setOnClickListener {
            isAutoDetect = !isAutoDetect
            updateAutoDetectUI()
            if (isAutoDetect) {
                updateScanStatus("Scanning…")
            }
        }

        overlayView.findViewById<Button>(R.id.btn_calibrate)?.setOnClickListener {
            startCalibration()
        }
    }

    // ─── Calibration overlay ──────────────────────────────────────────────────
    // A separate full-screen overlay window the user drags over their cards.
    // Saves rect as fractions in SharedPreferences; CardDetector reads them.

    private fun startCalibration() {
        if (calibrationView != null) return // already showing

        // Collapse the panel so it doesn't visually fight the calibration UI
        if (isExpanded) toggleExpanded()

        val inflater = LayoutInflater.from(this)
        val calView = inflater.inflate(R.layout.calibration_overlay, null)
        val cv = calView.findViewById<CalibrationView>(R.id.calibration_view)

        // Load any saved calibration so the user can adjust it instead of starting fresh
        val prefs = getSharedPreferences(CardDetector.PREFS_NAME, MODE_PRIVATE)
        val savedLeft = prefs.getFloat(CardDetector.KEY_CAL_LEFT, -1f)
        val savedTop = prefs.getFloat(CardDetector.KEY_CAL_TOP, -1f)
        val savedRight = prefs.getFloat(CardDetector.KEY_CAL_RIGHT, -1f)
        val savedBottom = prefs.getFloat(CardDetector.KEY_CAL_BOTTOM, -1f)
        if (savedLeft >= 0 && savedTop >= 0 && savedRight > savedLeft && savedBottom > savedTop) {
            cv.post { cv.setRectFractions(savedLeft, savedTop, savedRight, savedBottom) }
        }

        calView.findViewById<Button>(R.id.btn_cal_save).setOnClickListener {
            val frac = cv.getRectFractions()
            prefs.edit()
                .putFloat(CardDetector.KEY_CAL_LEFT, frac[0])
                .putFloat(CardDetector.KEY_CAL_TOP, frac[1])
                .putFloat(CardDetector.KEY_CAL_RIGHT, frac[2])
                .putFloat(CardDetector.KEY_CAL_BOTTOM, frac[3])
                .apply()
            stopCalibration()
            updateScanStatus("Calibrated — scanning…")
        }

        calView.findViewById<Button>(R.id.btn_cal_cancel).setOnClickListener {
            stopCalibration()
        }

        calView.findViewById<Button>(R.id.btn_cal_reset).setOnClickListener {
            prefs.edit()
                .remove(CardDetector.KEY_CAL_LEFT)
                .remove(CardDetector.KEY_CAL_TOP)
                .remove(CardDetector.KEY_CAL_RIGHT)
                .remove(CardDetector.KEY_CAL_BOTTOM)
                .apply()
            stopCalibration()
            updateScanStatus("Calibration cleared — using defaults")
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // No FLAG_NOT_FOCUSABLE — we WANT to capture all touches during calibration
            0,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(calView, params)
        calibrationView = calView
    }

    private fun stopCalibration() {
        calibrationView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        calibrationView = null
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        overlayView.findViewById<View>(R.id.expanded_layout).visibility =
            if (isExpanded) View.VISIBLE else View.GONE

        val lp = overlayView.layoutParams as WindowManager.LayoutParams
        lp.flags = if (isExpanded) {
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(overlayView, lp)
    }

    private fun updateAutoDetectUI() {
        val toggleBtn = overlayView.findViewById<Button>(R.id.btn_toggle_auto)
        val scanStatus = overlayView.findViewById<TextView>(R.id.tv_scan_status)
        val cardSection = overlayView.findViewById<View>(R.id.manual_card_section)
        val dealerSection = overlayView.findViewById<View>(R.id.manual_dealer_section)
        val undoBtn = overlayView.findViewById<Button>(R.id.btn_undo)

        if (isAutoDetect) {
            toggleBtn?.text = "Manual Mode"
            toggleBtn?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#2C3E50"))
            scanStatus?.visibility = View.VISIBLE
            cardSection?.visibility = View.GONE
            dealerSection?.visibility = View.GONE
            undoBtn?.isEnabled = false
        } else {
            toggleBtn?.text = "Auto-Detect"
            toggleBtn?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#27AE60"))
            scanStatus?.visibility = View.GONE
            cardSection?.visibility = View.VISIBLE
            dealerSection?.visibility = View.VISIBLE
            undoBtn?.isEnabled = true
        }
    }

    private fun updateScanStatus(msg: String) {
        overlayView.findViewById<TextView>(R.id.tv_scan_status)?.text = "📡 $msg"
    }

    private fun updateAdvice() {
        updatePlayerCardsDisplay()

        val adviceText = overlayView.findViewById<TextView>(R.id.tv_advice)
        val adviceReason = overlayView.findViewById<TextView>(R.id.tv_reason)
        val dealerDisplay = overlayView.findViewById<TextView>(R.id.tv_dealer_card)
        val fabLabel = overlayView.findViewById<TextView>(R.id.fab_label)
        val fabMath = overlayView.findViewById<TextView>(R.id.fab_math)

        dealerDisplay.text = if (dealerCard > 0) {
            "Dealer: ${BlackjackStrategy.cardDisplayName(dealerCard)}"
        } else {
            "Dealer: ?"
        }

        if (playerCards.isEmpty() || dealerCard == 0) {
            adviceText.text = "Enter cards"
            adviceReason.text = "Tap your cards and the dealer's upcard"
            fabLabel.text = "🃏"
            fabMath?.visibility = View.GONE
            // Reset FAB color to neutral when nothing detected
            try {
                overlayView.findViewById<View>(R.id.fab_toggle).backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#1A1A2E"))
            } catch (_: Exception) {}
            return
        }

        val result = BlackjackStrategy.getAdvice(playerCards, dealerCard)
        adviceText.text = "${result.action.emoji} ${result.action.label}"
        adviceReason.text = result.reason
        fabLabel.text = result.action.emoji

        // Live math readout under the FAB emoji, e.g. "K+3=13 v 10"
        fabMath?.text = buildMathSummary()
        fabMath?.visibility = View.VISIBLE

        try {
            overlayView.findViewById<View>(R.id.fab_toggle).backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(result.action.colorHex))
        } catch (_: Exception) {}

        updateCollapsedView(result)
    }

    /** Short hand summary for the FAB readout, e.g. "K+3=13 v 10" or "A,5=16/6 v 7". */
    private fun buildMathSummary(): String {
        val cardsStr = playerCards.joinToString("+") { BlackjackStrategy.cardDisplayName(it) }
        // Compute total — count aces as 11 if it doesn't bust, else 1
        var total = 0
        var aces = 0
        for (c in playerCards) {
            if (c == 1) { aces++; total += 11 } else total += c
        }
        while (total > 21 && aces > 0) { total -= 10; aces-- }
        val totalStr = if (aces > 0 && total <= 21) "$total/${total - 10}" else "$total"
        val dealerStr = if (dealerCard == 1) "A" else "$dealerCard"
        return "$cardsStr=$totalStr v $dealerStr"
    }

    private fun updatePlayerCardsDisplay() {
        overlayView.findViewById<TextView>(R.id.tv_player_cards)?.text = if (playerCards.isEmpty()) {
            "Your cards: none"
        } else {
            "Your: ${playerCards.joinToString(" + ") { BlackjackStrategy.cardDisplayName(it) }}"
        }
    }

    private fun updateCollapsedView(result: BlackjackStrategy.StrategyResult? = null) {
        overlayView.findViewById<TextView>(R.id.fab_label)?.text =
            result?.action?.emoji ?: "🃏"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "bja_overlay", "Blackjack Overlay", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Blackjack Advisor floating widget" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "bja_overlay")
            .setContentTitle("Blackjack Advisor")
            .setContentText("Floating advisor is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cardDetectionReceiver)
        stopCalibration()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }
}
