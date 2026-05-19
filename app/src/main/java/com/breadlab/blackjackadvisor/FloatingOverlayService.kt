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
    private var lastBalance: Double? = null

    // Auto-new-hand: only triggers on dealer-rank change (NOT player mismatch — that's
    // unsafe given Stake's 4/8/A OCR misreads). Requires N consecutive frames of the
    // same new dealer value before committing.
    private var pendingNewDealer: Int = 0
    private var pendingNewDealerFrames: Int = 0
    private val newHandConfirmFrames = 2

    // Ambiguity prompt: when detected cards differ from stored by exactly one
    // value AND those values are a known confusable pair, ask the user which is right.
    private var pendingAmbiguity: Pair<Int, Int>? = null  // (storedValue, detectedAlt)
    private val resolvedConfusables = mutableSetOf<Set<Int>>()
    private val confusablePairs = setOf(
        setOf(1, 4),   // A and 4 — angular tops, single dominant stroke
        setOf(3, 8),   // 3 and 8 — rounded right side vs full loop
        setOf(6, 8),   // 6 and 8 — both have lower loop
        setOf(6, 9),   // 6 and 9 — same shape rotated
        setOf(7, 10),  // 7 and J (J=10) — diagonal stroke confusion
        setOf(5, 6),   // 5 and 6 — curved bottom
        setOf(5, 8),   // 5 and 8 — open vs closed top
        setOf(2, 7)    // 2 and 7 — angular top, diagonal sweep
    )

    private val cardDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Always update the diagnostic readout so we can see whether captures are
            // arriving and what OCR is reading — regardless of mode.
            val frame = intent?.getIntExtra(ScreenCaptureService.EXTRA_FRAME_COUNT, 0) ?: 0
            val rawDealer = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_DEALER)
                ?: arrayListOf()
            val rawPlayer = intent?.getStringArrayListExtra(ScreenCaptureService.EXTRA_RAW_PLAYER)
                ?: arrayListOf()
            val detected = intent?.getIntegerArrayListExtra(ScreenCaptureService.EXTRA_PLAYER_CARDS)
                ?: arrayListOf()
            val dealer = intent?.getIntExtra(ScreenCaptureService.EXTRA_DEALER_CARD, 0) ?: 0

            val dStr = if (rawDealer.isEmpty()) "—" else rawDealer.take(5).joinToString(",")
            val pStr = if (rawPlayer.isEmpty()) "—" else rawPlayer.take(5).joinToString(",")
            updateScanStatus("f$frame d:$dealer p:[${detected.joinToString(",")}]  D[$dStr] P[$pStr]")

            // Balance broadcast: -1.0 means none detected this frame.
            // Allow 0 as a legitimate balance (e.g. switched currencies with no funds).
            val rawBalance = intent?.getDoubleExtra(ScreenCaptureService.EXTRA_BALANCE, -1.0) ?: -1.0
            if (rawBalance >= 0) {
                lastBalance = rawBalance
                updateBankrollDisplay()
            }

            // Card-state updates: same gating as before.
            if (isExpanded && !isAutoDetect) return

            // Auto-new-hand: if dealer value has shifted to a different non-zero rank,
            // wait for 2 consecutive frames of the same new value before committing.
            // A single OCR misread can't trigger it; only sustained "different dealer"
            // counts as a real new deal.
            val dealerChanged = dealer > 0 && dealerCard > 0 && dealer != dealerCard
            if (dealerChanged) {
                if (dealer == pendingNewDealer) {
                    pendingNewDealerFrames++
                    if (pendingNewDealerFrames >= newHandConfirmFrames) {
                        // Confirmed — reset and adopt the new state
                        playerCards.clear()
                        playerCards.addAll(detected)
                        dealerCard = dealer
                        pendingNewDealer = 0
                        pendingNewDealerFrames = 0
                        // New hand — clear any locked confusable resolutions
                        resolvedConfusables.clear()
                        clearAmbiguity()
                        updateAdvice()
                    }
                } else {
                    pendingNewDealer = dealer
                    pendingNewDealerFrames = 1
                }
                return
            }

            // No dealer-change signal — clear pending and run normal sticky logic.
            pendingNewDealer = 0
            pendingNewDealerFrames = 0

            // Sticky accumulation: OCR misses frames all the time, but cards on screen
            // don't disappear. Once we've detected a card, keep it. Only REPLACE
            // playerCards when a new frame finds MORE cards than we currently have
            // (i.e. the player hit). Only set dealerCard once. New Hand button resets.
            var changed = false
            if (!detected.isNullOrEmpty() && detected.size > playerCards.size) {
                playerCards.clear()
                playerCards.addAll(detected)
                changed = true
                // Hand grew (hit) — any prior ambiguity prompt is stale.
                clearAmbiguity()
            }
            if (dealer > 0 && dealerCard == 0) {
                dealerCard = dealer
                changed = true
            }

            // Ambiguity check: same card count but values differ by exactly one
            // confusable swap (e.g. 4 vs A). Ask the user once per pair per hand.
            checkAmbiguity(detected)

            if (changed) updateAdvice()
        }
    }

    /** Detect a single confusable swap between stored and detected cards. */
    private fun checkAmbiguity(detected: List<Int>) {
        // Don't override existing pending — only one prompt at a time
        if (pendingAmbiguity != null) return
        if (detected.size != playerCards.size || playerCards.isEmpty()) return

        val storedCounts = playerCards.groupingBy { it }.eachCount()
        val detectedCounts = detected.groupingBy { it }.eachCount()
        if (storedCounts == detectedCounts) return

        val storedOnly = storedCounts.keys - detectedCounts.keys
        val detectedOnly = detectedCounts.keys - storedCounts.keys
        if (storedOnly.size != 1 || detectedOnly.size != 1) return

        val a = storedOnly.first()
        val b = detectedOnly.first()
        val pair = setOf(a, b)
        if (pair in confusablePairs && pair !in resolvedConfusables) {
            pendingAmbiguity = Pair(a, b)
            updateAmbiguityPrompt()
        }
    }

    private fun updateAmbiguityPrompt() {
        val section = overlayView.findViewById<View>(R.id.ambiguity_section)
        val prompt = overlayView.findViewById<TextView>(R.id.tv_ambiguity_prompt)
        val btnA = overlayView.findViewById<Button>(R.id.btn_ambig_a)
        val btnB = overlayView.findViewById<Button>(R.id.btn_ambig_b)
        val (a, b) = pendingAmbiguity ?: run {
            section?.visibility = View.GONE
            return
        }
        val aName = BlackjackStrategy.cardDisplayName(a)
        val bName = BlackjackStrategy.cardDisplayName(b)
        section?.visibility = View.VISIBLE
        prompt?.text = "🤔 Is your card a $aName or a $bName?"
        btnA?.text = aName
        btnA?.setOnClickListener { resolveAmbiguity(a) }
        btnB?.text = bName
        btnB?.setOnClickListener { resolveAmbiguity(b) }
    }

    private fun resolveAmbiguity(chosenValue: Int) {
        val (a, b) = pendingAmbiguity ?: return
        val otherValue = if (chosenValue == a) b else a
        val idx = playerCards.indexOf(otherValue)
        if (idx >= 0) playerCards[idx] = chosenValue
        resolvedConfusables.add(setOf(a, b))
        pendingAmbiguity = null
        updateAmbiguityPrompt()
        updateAdvice()
    }

    private fun clearAmbiguity() {
        if (pendingAmbiguity != null) {
            pendingAmbiguity = null
            updateAmbiguityPrompt()
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
            pendingNewDealer = 0
            pendingNewDealerFrames = 0
            resolvedConfusables.clear()
            clearAmbiguity()
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

        // Load saved rects if any, so the user can adjust instead of restart.
        val prefs = getSharedPreferences(CardDetector.PREFS_NAME, MODE_PRIVATE)
        val dL = prefs.getFloat(CardDetector.KEY_DEALER_LEFT, -1f)
        val dT = prefs.getFloat(CardDetector.KEY_DEALER_TOP, -1f)
        val dR = prefs.getFloat(CardDetector.KEY_DEALER_RIGHT, -1f)
        val dB = prefs.getFloat(CardDetector.KEY_DEALER_BOTTOM, -1f)
        val pL = prefs.getFloat(CardDetector.KEY_PLAYER_LEFT, -1f)
        val pT = prefs.getFloat(CardDetector.KEY_PLAYER_TOP, -1f)
        val pR = prefs.getFloat(CardDetector.KEY_PLAYER_RIGHT, -1f)
        val pB = prefs.getFloat(CardDetector.KEY_PLAYER_BOTTOM, -1f)
        val bL = prefs.getFloat(CardDetector.KEY_BALANCE_LEFT, -1f)
        val bT = prefs.getFloat(CardDetector.KEY_BALANCE_TOP, -1f)
        val bR = prefs.getFloat(CardDetector.KEY_BALANCE_RIGHT, -1f)
        val bB = prefs.getFloat(CardDetector.KEY_BALANCE_BOTTOM, -1f)
        val haveDealer = dL >= 0 && dT >= 0 && dR > dL && dB > dT
        val havePlayer = pL >= 0 && pT >= 0 && pR > pL && pB > pT
        val haveBalance = bL >= 0 && bT >= 0 && bR > bL && bB > bT
        if (haveDealer && havePlayer) {
            // Use saved values for any rect we have; fall back to defaults inside CalibrationView
            // for ones we don't (balance is optional — added later).
            val bLf = if (haveBalance) bL else 0.15f
            val bTf = if (haveBalance) bT else 0.06f
            val bRf = if (haveBalance) bR else 0.60f
            val bBf = if (haveBalance) bB else 0.11f
            cv.post { cv.setRects(dL, dT, dR, dB, pL, pT, pR, pB, bLf, bTf, bRf, bBf) }
        }

        calView.findViewById<Button>(R.id.btn_cal_save).setOnClickListener {
            val d = cv.getDealerFractions()
            val p = cv.getPlayerFractions()
            val b = cv.getBalanceFractions()
            prefs.edit()
                .putFloat(CardDetector.KEY_DEALER_LEFT, d[0])
                .putFloat(CardDetector.KEY_DEALER_TOP, d[1])
                .putFloat(CardDetector.KEY_DEALER_RIGHT, d[2])
                .putFloat(CardDetector.KEY_DEALER_BOTTOM, d[3])
                .putFloat(CardDetector.KEY_PLAYER_LEFT, p[0])
                .putFloat(CardDetector.KEY_PLAYER_TOP, p[1])
                .putFloat(CardDetector.KEY_PLAYER_RIGHT, p[2])
                .putFloat(CardDetector.KEY_PLAYER_BOTTOM, p[3])
                .putFloat(CardDetector.KEY_BALANCE_LEFT, b[0])
                .putFloat(CardDetector.KEY_BALANCE_TOP, b[1])
                .putFloat(CardDetector.KEY_BALANCE_RIGHT, b[2])
                .putFloat(CardDetector.KEY_BALANCE_BOTTOM, b[3])
                .apply()
            stopCalibration()
            updateScanStatus("Calibrated — scanning…")
        }

        calView.findViewById<Button>(R.id.btn_cal_cancel).setOnClickListener {
            stopCalibration()
        }

        calView.findViewById<Button>(R.id.btn_cal_reset).setOnClickListener {
            prefs.edit()
                .remove(CardDetector.KEY_DEALER_LEFT)
                .remove(CardDetector.KEY_DEALER_TOP)
                .remove(CardDetector.KEY_DEALER_RIGHT)
                .remove(CardDetector.KEY_DEALER_BOTTOM)
                .remove(CardDetector.KEY_PLAYER_LEFT)
                .remove(CardDetector.KEY_PLAYER_TOP)
                .remove(CardDetector.KEY_PLAYER_RIGHT)
                .remove(CardDetector.KEY_PLAYER_BOTTOM)
                .remove(CardDetector.KEY_BALANCE_LEFT)
                .remove(CardDetector.KEY_BALANCE_TOP)
                .remove(CardDetector.KEY_BALANCE_RIGHT)
                .remove(CardDetector.KEY_BALANCE_BOTTOM)
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

    private fun updateBankrollDisplay() {
        val bankrollSection = overlayView.findViewById<View>(R.id.bankroll_section) ?: return
        val balanceText = overlayView.findViewById<TextView>(R.id.tv_balance)
        val betText = overlayView.findViewById<TextView>(R.id.tv_bet_suggestion)
        val balance = lastBalance ?: run {
            bankrollSection.visibility = View.GONE
            return
        }
        bankrollSection.visibility = View.VISIBLE
        balanceText?.text = "💰 Balance: ${formatMoney(balance)}"
        betText?.text = "🎯 Suggested bet: ${formatMoney(recommendedBet(balance))}"
    }

    /** 1% of bankroll, rounded to a clean step (1, 5, 10, 25, 100…) for nicer display. */
    private fun recommendedBet(balance: Double): Double {
        val raw = balance * 0.01
        return when {
            raw < 1.0    -> 1.0
            raw < 5.0    -> kotlin.math.round(raw)
            raw < 25.0   -> (kotlin.math.round(raw / 5.0) * 5.0).coerceAtLeast(5.0)
            raw < 100.0  -> (kotlin.math.round(raw / 10.0) * 10.0).coerceAtLeast(10.0)
            raw < 1000.0 -> (kotlin.math.round(raw / 25.0) * 25.0).coerceAtLeast(25.0)
            else         -> (kotlin.math.round(raw / 100.0) * 100.0).coerceAtLeast(100.0)
        }
    }

    private fun formatMoney(amount: Double): String {
        // Whole numbers get no decimals; fractional gets 2.
        return if (amount % 1.0 == 0.0) {
            String.format("%,.0f", amount)
        } else {
            String.format("%,.2f", amount)
        }
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
        val dealerStr = BlackjackStrategy.cardDisplayName(dealerCard)
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
