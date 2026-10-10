package com.talktomyfinances.android.domain

/** Flesch-Kincaid grade level estimator. */
object ReadabilityScorer {
    fun gradeLevel(text: String): Double {
        val words = Regex("[A-Za-z']+").findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return 0.0
        val sentences = text.split(Regex("[.!?]+")).count { it.isNotBlank() }.coerceAtLeast(1)
        val syllables = words.sumOf { countSyllables(it) }
        return 0.39 * words.size / sentences + 11.8 * syllables / words.size - 15.59
    }

    internal fun countSyllables(word: String): Int {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return 1
        var count = Regex("[aeiouy]+").findAll(w).count()
        if (w.length > 2 && w.endsWith("e") && !w.endsWith("le")) count--
        return count.coerceAtLeast(1)
    }
}

/** Boundary to the on-device model (Firebase AI Logic) that simplifies text. */
fun interface TextRewriter {
    suspend fun rewrite(text: String, targetGrade: Int): String
}

/**
 * Hybrid Inference readability fallback: text from the Auditor Engine is returned untouched if
 * it already reads at or below [maxGrade]; otherwise it is rewritten on-device, up to
 * [maxAttempts] times, keeping the most readable result.
 */
class ReadabilityGuard(
    private val rewriter: TextRewriter,
    private val maxGrade: Double = MAX_GRADE,
    private val maxAttempts: Int = 2
) {
    suspend fun ensureReadable(text: String): String {
        var best = text
        var bestGrade = ReadabilityScorer.gradeLevel(text)
        var attempts = 0
        while (bestGrade > maxGrade && attempts < maxAttempts) {
            attempts++
            val candidate = try {
                rewriter.rewrite(best, maxGrade.toInt())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            }
            if (candidate.isBlank()) break
            val grade = ReadabilityScorer.gradeLevel(candidate)
            if (grade < bestGrade) {
                best = candidate
                bestGrade = grade
            }
        }
        return best
    }

    companion object {
        const val MAX_GRADE = 8.0
    }
}

