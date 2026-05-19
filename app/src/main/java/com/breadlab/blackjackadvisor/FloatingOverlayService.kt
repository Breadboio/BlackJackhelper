package com.breadlab.blackjackadvisor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
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

    private val tracker = HandTracker()
    private var lastBalance: Double? = null

    private val cardDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val frame = intent?.getIntExtra(ScreenCaptureService.EXTRA_FRAME_COUNT, 0) ?: 0
            val rawDealer = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_DEALER) ?: arrayListOf()
            val rawPlayer = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_PLAYER) ?: arrayListOf()
            val detected = intent?.getIntegerArrayListExtra(ScreenCaptureService.EXTRA_PLAYER_CARDS) ?: arrayListOf()
            val dealer = intent?.getIntExtra(ScreenCaptureService.EXTRA_DEALER_CARD, 0) ?: 0
            val dStr = if (rawDealer.isEmpty()) "—" else rawDealer.take(5).joinToString(",")
            val pStr = if (rawPlayer.isEmpty()) "—" else rawPlayer.take(5).joinToString(",")
            updateScanStatus("f$frame d:$dealer p:[${detected.joinToString(",")}]  D[$dStr] P[$pStr]")

            val rawBalance = intent?.getDoubleExtra(ScreenCaptureService.EXTRA_BALANCE, -1.0) ?: -1.0
            if (rawBalance >= 0) { lastBalance = rawBalance; updateBankrollDisplay() }

            if (isExpanded && !tracker.isAutoDetect()) return

            val u = tracker.onFrame(detected, dealer)
            if (u.ambiguityCleared || u.ambiguity != null) updateAmbiguityPrompt()
            if (u.cardsChanged) updateAdvice()
        }
    }

    private fun updateAmbiguityPrompt() {
        val section = overlayView.findViewById<View>(R.id.ambiguity_section)
        val prompt = overlayView.findViewById<TextView>(R.id.tv_ambiguity_prompt)
        val btnA = overlayView.findViewById<Button>(R.id.btn_ambig_a)
        val btnB = overlayView.findViewById<Button>(R.id.btn_ambig_b)
        val amb = tracker.currentAmbiguity() ?: run { section?.visibility = View.GONE; return }
        val aName = BlackjackStrategy.cardDisplayName(amb.stored)
        val bName = BlackjackStrategy.cardDisplayName(amb.detectedAlt)
        section?.visibility = View.VISIBLE
        prompt?.text = "🤔 Is your card a $aName or a $bName?"
        btnA?.text = aName
        btnA?.setOnClickListener { val u = tracker.resolveAmbiguity(amb.stored); updateAmbiguityPrompt(); if (u.cardsChanged) updateAdvice() }
        btnB?.text = bName
        btnB?.setOnClickListener { val u = tracker.resolveAmbiguity(amb.detectedAlt); updateAmbiguityPrompt(); if (u.cardsChanged) updateAdvice() }
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
                val u = tracker.addPlayerCard(value)
                if (u.cardsChanged) updateAdvice()
            }
        }

        val dealerButtons = mapOf(
            R.id.dealer_ace to 1, R.id.dealer_2 to 2, R.id.dealer_3 to 3,
            R.id.dealer_4 to 4, R.id.dealer_5 to 5, R.id.dealer_6 to 6,
            R.id.dealer_7 to 7, R.id.dealer_8 to 8, R.id.dealer_9 to 9, R.id.dealer_10 to 10
        )
        dealerButtons.forEach { (btnId, value) ->
            overlayView.findViewById<Button>(btnId)?.setOnClickListener {
                val u = tracker.setDealer(value)
                if (u.cardsChanged) updateAdvice()
            }
        }

        overlayView.findViewById<Button>(R.id.btn_clear)?.setOnClickListener {
            val u = tracker.reset()
            updateAmbiguityPrompt()
            if (u.cardsChanged) updateAdvice()
            updatePlayerCardsDisplay()
        }

        overlayView.findViewById<Button>(R.id.btn_undo)?.setOnClickListener {
            val u = tracker.undo()
            if (u.cardsChanged) updateAdvice()
        }

        overlayView.findViewById<Button>(R.id.btn_toggle_auto)?.setOnClickListener {
            tracker.setAutoDetect(!tracker.isAutoDetect())
            updateAutoDetectUI()
            if (tracker.isAutoDetect()) {
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

        // Load saved rects if any, so the user can adjust instead of restart.
        val store = CalibrationStore(this)
        val d = store.fractions(CalibrationStore.Zone.DEALER)
        val p = store.fractions(CalibrationStore.Zone.PLAYER)
        val b = store.fractions(CalibrationStore.Zone.BALANCE)
        if (d != null && p != null) {
            val bb = b ?: floatArrayOf(0.15f, 0.06f, 0.60f, 0.11f)
            cv.post { cv.setRects(d[0], d[1], d[2], d[3], p[0], p[1], p[2], p[3], bb[0], bb[1], bb[2], bb[3]) }
        }

        calView.findViewById<Button>(R.id.btn_cal_save).setOnClickListener {
            store.save(cv.getDealerFractions(), cv.getPlayerFractions(), cv.getBalanceFractions())
            stopCalibration(); updateScanStatus("Calibrated — scanning…")
        }

        calView.findViewById<Button>(R.id.btn_cal_cancel).setOnClickListener {
            stopCalibration()
        }

        calView.findViewById<Button>(R.id.btn_cal_reset).setOnClickListener {
            store.clear(); stopCalibration(); updateScanStatus("Calibration cleared — using defaults")
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
        calView.isFocusableInTouchMode = true
        calView.requestFocus()
        calView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                stopCalibration(); true
            } else false
        }
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

        if (tracker.isAutoDetect()) {
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

    private fun updateBankrollDisplay() {
        val bankrollSection = overlayView.findViewById<View>(R.id.bankroll_section) ?: return
        val balanceText = overlayView.findViewById<TextView>(R.id.tv_balance)
        val betText = overlayView.findViewById<TextView>(R.id.tv_bet_suggestion)
        val balance = lastBalance ?: run {
            bankrollSection.visibility = View.GONE
            return
        }
        bankrollSection.visibility = View.VISIBLE
        balanceText?.text = "💰 Balance: ${Bankroll.formatMoney(balance)}"
        betText?.text = "🎯 Suggested bet: ${Bankroll.formatMoney(Bankroll.recommendedBet(balance))}"
    }

    private fun updateAdvice() {
        updatePlayerCardsDisplay()

        val adviceText = overlayView.findViewById<TextView>(R.id.tv_advice)
        val adviceReason = overlayView.findViewById<TextView>(R.id.tv_reason)
        val dealerDisplay = overlayView.findViewById<TextView>(R.id.tv_dealer_card)
        val fabLabel = overlayView.findViewById<TextView>(R.id.fab_label)
        val fabMath = overlayView.findViewById<TextView>(R.id.fab_math)

        dealerDisplay.text = if (tracker.dealerCard > 0) {
            "Dealer: ${BlackjackStrategy.cardDisplayName(tracker.dealerCard)}"
        } else {
            "Dealer: ?"
        }

        val result = BlackjackStrategy.getAdvice(tracker.playerCards, tracker.dealerCard)

        if (result.action == BlackjackStrategy.Action.INCOMPLETE) {
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
    }

    /** Short hand summary for the FAB readout, e.g. "K+3=13 v 10" or "A,5=16/6 v 7". */
    private fun buildMathSummary(): String {
        val cardsStr = tracker.playerCards.joinToString("+") { BlackjackStrategy.cardDisplayName(it) }
        // Compute total — count aces as 11 if it doesn't bust, else 1
        var total = 0
        var aces = 0
        for (c in tracker.playerCards) {
            if (c == 1) { aces++; total += 11 } else total += c
        }
        while (total > 21 && aces > 0) { total -= 10; aces-- }
        val totalStr = if (aces > 0 && total <= 21) "$total/${total - 10}" else "$total"
        val dealerStr = BlackjackStrategy.cardDisplayName(tracker.dealerCard)
        return "$cardsStr=$totalStr v $dealerStr"
    }

    private fun updatePlayerCardsDisplay() {
        overlayView.findViewById<TextView>(R.id.tv_player_cards)?.text = if (tracker.playerCards.isEmpty()) {
            "Your cards: none"
        } else {
            "Your: ${tracker.playerCards.joinToString(" + ") { BlackjackStrategy.cardDisplayName(it) }}"
        }
    }

    private fun updateCollapsedView() {
        overlayView.findViewById<TextView>(R.id.fab_label)?.text = "🃏"
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
