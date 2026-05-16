package com.breadlab.blackjackadvisor

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class DetectedCards(
    val playerCards: List<Int>,
    val dealerCard: Int
)

class CardDetector {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Matches valid card rank tokens: A, 2-9, 10, J, Q, K
    private val cardPattern = Regex("^(A|[2-9]|10|J|Q|K)$", RegexOption.IGNORE_CASE)

    /**
     * Analyze a screen capture bitmap and find card ranks.
     * Screen is divided into three zones by Y position:
     *   - Top 35%  → dealer zone
     *   - Mid 30%  → ignored (middle table area)
     *   - Bottom 35% → player zone
     */
    fun detectCards(bitmap: Bitmap, callback: (DetectedCards?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val screenHeight = bitmap.height
        val dealerZoneEnd = (screenHeight * 0.35).toInt()
        val playerZoneStart = (screenHeight * 0.65).toInt()

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val playerCards = mutableListOf<Int>()
                var dealerCard = 0

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        if (!cardPattern.matches(text)) continue

                        val value = parseCardValue(text.uppercase())
                        if (value == 0) continue

                        val centerY = line.boundingBox?.centerY() ?: continue

                        when {
                            centerY <= dealerZoneEnd && dealerCard == 0 -> dealerCard = value
                            centerY >= playerZoneStart && playerCards.size < 8 -> playerCards.add(value)
                        }
                    }
                }

                if (playerCards.isNotEmpty() || dealerCard > 0) {
                    callback(DetectedCards(playerCards, dealerCard))
                } else {
                    callback(null)
                }
            }
            .addOnFailureListener { callback(null) }
    }

    private fun parseCardValue(text: String): Int = when (text) {
        "A" -> 1
        "J", "Q", "K" -> 10
        else -> text.toIntOrNull() ?: 0
    }

    fun close() = recognizer.close()
}
