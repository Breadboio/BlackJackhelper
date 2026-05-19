package com.breadlab.blackjackadvisor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class DetectedCards(
    val playerCards: List<Int>,
    val dealerCard: Int,
    // Player's current bankroll, as read from the BALANCE zone. null if not detected.
    val balance: Double? = null,
    // Diagnostic fields shown in the scan-status line so we can see what OCR is reading.
    val rawDealerZone: List<String> = emptyList(),
    val rawPlayerZone: List<String> = emptyList()
)

class CardDetector(private val context: Context? = null) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val cardPattern = Regex(
        "^(A|[2-9]|10|J|Q|K)[\\s♠♥♦♣•·.,]{0,3}$",
        RegexOption.IGNORE_CASE
    )

    private val multiCardPattern = Regex(
        "^(A|10|[2-9JQK])(\\s+(A|10|[2-9JQK]))+$",
        RegexOption.IGNORE_CASE
    )

    private val rankExtractor = Regex("^(A|10|[2-9]|[JQK])", RegexOption.IGNORE_CASE)

    companion object {
        // Fallback zones when no calibration is saved.
        private const val DEFAULT_DEALER_TOP    = 0.10
        private const val DEFAULT_DEALER_BOTTOM = 0.34
        private const val DEFAULT_PLAYER_TOP    = 0.40
        private const val DEFAULT_PLAYER_BOTTOM = 0.56

        // Min text height as fraction of screen — keeps tiny UI text (balance, tab labels)
        // out while still catching small card pips. Tuned low because the two-rect
        // calibration already does the heavy filtering by region.
        private const val MIN_TEXT_HEIGHT_FRAC = 0.010

        // Vertical gap (in pixels) required between a candidate "badge" and the cards
        // below it before we treat it as a hand-total badge. Prevents false drops of
        // a legitimate hit card that happens to equal the sum of earlier cards.
        private const val BADGE_GAP_PX = 30

    }

    // Matches a balance-style number: "189,994.26", "12,500", "5.50", etc.
    private val balanceNumberPattern = Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?")

    fun detectCards(bitmap: Bitmap, callback: (DetectedCards?) -> Unit) {
        val screenW = bitmap.width
        val screenH = bitmap.height
        val minTextH = (screenH * MIN_TEXT_HEIGHT_FRAC).toInt()

        val store = context?.let { CalibrationStore(it) }
        val dealerZone = store?.rect(CalibrationStore.Zone.DEALER, screenW, screenH)
        val playerZone = store?.rect(CalibrationStore.Zone.PLAYER, screenW, screenH)
        val balanceZone = store?.rect(CalibrationStore.Zone.BALANCE, screenW, screenH)

        // Dealer zone (calibrated or default)
        val dz = dealerZone ?: Rect(
            0, (screenH * DEFAULT_DEALER_TOP).toInt(),
            screenW, (screenH * DEFAULT_DEALER_BOTTOM).toInt()
        )
        // Player zone (calibrated or default)
        val pz = playerZone ?: Rect(
            0, (screenH * DEFAULT_PLAYER_TOP).toInt(),
            screenW, (screenH * DEFAULT_PLAYER_BOTTOM).toInt()
        )
        // Balance zone is optional — null means user hasn't calibrated it yet.
        val bz = balanceZone

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val playerHits = mutableListOf<DetectedHit>()
                val dealerHits = mutableListOf<DetectedHit>()
                val rawDealer = mutableListOf<String>()
                val rawPlayer = mutableListOf<String>()
                val rawBalance = mutableListOf<String>()

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        val box = line.boundingBox ?: continue
                        val cx = box.centerX()
                        val cy = box.centerY()

                        val inDealer = dz.contains(cx, cy)
                        val inPlayer = pz.contains(cx, cy)
                        val inBalance = bz?.contains(cx, cy) == true

                        if (inDealer) rawDealer.add(text)
                        if (inPlayer) rawPlayer.add(text)
                        if (inBalance) rawBalance.add(text)

                        if (!inDealer && !inPlayer) continue
                        val values = extractCardValues(text)
                        if (values.isEmpty()) continue
                        if (box.height() < minTextH) continue

                        for (value in values) {
                            if (inDealer) dealerHits.add(DetectedHit(value, cy))
                            else if (inPlayer) playerHits.add(DetectedHit(value, cy))
                        }
                    }
                }

                val dealerFinal = filterHandTotalBadge(dealerHits)
                val playerFinal = filterHandTotalBadge(playerHits)

                val dealerCard = dealerFinal.firstOrNull() ?: 0
                val playerCards = playerFinal.take(8)
                val balance = extractBalance(rawBalance)

                callback(DetectedCards(
                    playerCards, dealerCard, balance,
                    rawDealer, rawPlayer
                ))
            }
            .addOnFailureListener { callback(null) }
    }

    /** Pick the largest numeric value found in the balance zone text. */
    private fun extractBalance(texts: List<String>): Double? {
        var maxValue: Double? = null
        for (text in texts) {
            for (m in balanceNumberPattern.findAll(text)) {
                val cleaned = m.value.replace(",", "")
                val v = cleaned.toDoubleOrNull() ?: continue
                if (maxValue == null || v > maxValue) maxValue = v
            }
        }
        return maxValue
    }

    /**
     * Parse an OCR text line and return zero, one, or more card values.
     * "9", "9♥"          → [9]
     * "Q 10"             → [10, 10]  (whitespace-separated multi-rank)
     * "9K"               → [9, 10]   (glued multi-rank)
     * "BLACKJACK PAYS 2" → []        (non-rank chars block all match paths)
     */
    private fun extractCardValues(text: String): List<Int> {
        if (cardPattern.matches(text)) {
            val rank = rankExtractor.find(text)?.value ?: return emptyList()
            val v = parseCardValue(rank.uppercase())
            return if (v > 0) listOf(v) else emptyList()
        }
        if (multiCardPattern.matches(text)) {
            val values = mutableListOf<Int>()
            for (token in text.split(Regex("\\s+"))) {
                val rank = rankExtractor.find(token)?.value ?: continue
                val v = parseCardValue(rank.uppercase())
                if (v > 0) values.add(v)
            }
            return values
        }
        // Glued ranks with no separator. Accept only if all of the text is rank chars.
        val rankFinder = Regex("(10|[A2-9JQK])", RegexOption.IGNORE_CASE)
        val matches = rankFinder.findAll(text).toList()
        if (matches.size >= 2) {
            val cleanText = text.replace(Regex("[\\s♠♥♦♣•·.,]"), "")
            val matchedLength = matches.sumOf { it.value.length }
            if (matchedLength == cleanText.length) {
                return matches.map { parseCardValue(it.value.uppercase()) }
            }
        }
        return emptyList()
    }

    private fun parseCardValue(text: String): Int = when (text) {
        "A" -> 1
        "J", "Q", "K" -> 10
        else -> text.toIntOrNull() ?: 0
    }

    /** One OCR card detection — value + the Y center of the bounding box, for badge filtering. */
    private data class DetectedHit(val value: Int, val y: Int)

    /**
     * If the topmost hit is clearly above the others AND its value equals the sum of
     * the rest, treat it as the hand-total badge and drop it. Falls through unchanged
     * if there's no clear separation (so a hit card stacked at the same Y as earlier
     * cards isn't mistakenly dropped just because its value happens to match the sum).
     */
    private fun filterHandTotalBadge(hits: List<DetectedHit>): List<Int> {
        if (hits.size < 3) return hits.map { it.value }
        val sorted = hits.sortedBy { it.y }
        val topmost = sorted.first()
        val others = sorted.drop(1)
        val gap = others.minOf { it.y } - topmost.y
        if (gap < BADGE_GAP_PX) return hits.map { it.value }
        val othersSum = others.sumOf { it.value }
        // For aces, also accept the "soft total" form by checking aces-as-11 sum.
        val othersSumWithAce = othersSum + others.count { it.value == 1 } * 10
        return if (topmost.value == othersSum || topmost.value == othersSumWithAce) {
            others.map { it.value }
        } else {
            hits.map { it.value }
        }
    }

    fun close() = recognizer.close()
}
