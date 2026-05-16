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
    // Diagnostic fields — populated on every detection cycle even when no cards found.
    val totalTextBlocks: Int = 0,
    val rawTextInZone: List<String> = emptyList()
)

class CardDetector(private val context: Context? = null) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Matches valid card rank tokens: A, 2-9, 10, J, Q, K (exact match, case-insensitive)
    private val cardPattern = Regex("^(A|[2-9]|10|J|Q|K)$", RegexOption.IGNORE_CASE)

    companion object {
        // Fallback zones when no calibration is saved. Tuned for mobile-browser
        // blackjack — dealer cards top third, player cards middle area.
        private const val DEALER_ZONE_TOP    = 0.10
        private const val DEALER_ZONE_BOTTOM = 0.37
        private const val PLAYER_ZONE_TOP    = 0.38
        private const val PLAYER_ZONE_BOTTOM = 0.72

        // Reject text smaller than this fraction of screen height.
        private const val MIN_TEXT_HEIGHT_FRAC = 0.018

        // SharedPreferences keys (must match CalibrationOverlay writer).
        const val PREFS_NAME = "bja_prefs"
        const val KEY_CAL_LEFT   = "cal_left"
        const val KEY_CAL_TOP    = "cal_top"
        const val KEY_CAL_RIGHT  = "cal_right"
        const val KEY_CAL_BOTTOM = "cal_bottom"
    }

    fun detectCards(bitmap: Bitmap, callback: (DetectedCards?) -> Unit) {
        val screenW = bitmap.width
        val screenH = bitmap.height
        val minTextH = (screenH * MIN_TEXT_HEIGHT_FRAC).toInt()

        val zone = loadCalibratedZone(screenW, screenH)
        val dealerTop: Int
        val dealerBottom: Int
        val playerTop: Int
        val playerBottom: Int
        val leftX: Int
        val rightX: Int

        if (zone != null) {
            // Calibrated: top half = dealer, bottom half = player
            leftX = zone.left
            rightX = zone.right
            val midY = (zone.top + zone.bottom) / 2
            dealerTop = zone.top
            dealerBottom = midY
            playerTop = midY
            playerBottom = zone.bottom
        } else {
            // Fallback to default vertical bands, full width
            leftX = 0
            rightX = screenW
            dealerTop    = (screenH * DEALER_ZONE_TOP).toInt()
            dealerBottom = (screenH * DEALER_ZONE_BOTTOM).toInt()
            playerTop    = (screenH * PLAYER_ZONE_TOP).toInt()
            playerBottom = (screenH * PLAYER_ZONE_BOTTOM).toInt()
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val playerCards = mutableListOf<Int>()
                var dealerCard = 0
                val rawInZone = mutableListOf<String>()
                var totalLines = 0

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        totalLines++
                        val text = line.text.trim()
                        val box = line.boundingBox ?: continue
                        val cx = box.centerX()
                        val cy = box.centerY()

                        // Collect anything inside the calibrated/default zone for diagnostics —
                        // including non-card text, so we can see what OCR is reading.
                        if (cx in leftX..rightX && cy in dealerTop..playerBottom) {
                            rawInZone.add(text)
                        }

                        if (!cardPattern.matches(text)) continue
                        if (box.height() < minTextH) continue
                        if (cx !in leftX..rightX) continue

                        val value = parseCardValue(text.uppercase())
                        if (value == 0) continue

                        when {
                            cy in dealerTop..dealerBottom && dealerCard == 0 -> {
                                dealerCard = value
                            }
                            cy in playerTop..playerBottom && playerCards.size < 8 -> {
                                playerCards.add(value)
                            }
                        }
                    }
                }

                // Always callback (even with no cards) so diagnostics flow through.
                callback(DetectedCards(playerCards, dealerCard, totalLines, rawInZone))
            }
            .addOnFailureListener { callback(null) }
    }

    /** Returns the user-calibrated rect in pixels, or null if no valid calibration saved. */
    private fun loadCalibratedZone(w: Int, h: Int): Rect? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val left = prefs.getFloat(KEY_CAL_LEFT, -1f)
        val top = prefs.getFloat(KEY_CAL_TOP, -1f)
        val right = prefs.getFloat(KEY_CAL_RIGHT, -1f)
        val bottom = prefs.getFloat(KEY_CAL_BOTTOM, -1f)
        if (left < 0 || top < 0 || right <= left || bottom <= top) return null
        return Rect(
            (w * left).toInt(),
            (h * top).toInt(),
            (w * right).toInt(),
            (h * bottom).toInt()
        )
    }

    private fun parseCardValue(text: String): Int = when (text) {
        "A" -> 1
        "J", "Q", "K" -> 10
        else -> text.toIntOrNull() ?: 0
    }

    fun close() = recognizer.close()
}
