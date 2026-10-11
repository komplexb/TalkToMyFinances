package com.talktomyfinances.android.domain

/**
 * Standard Flesch-Kincaid Grade Level Calculator.
 * Employs standard English syllable counting exception rules (e.g. silent 'e', '-ed', '-es',
 * prefixes/suffixes) to accurately measure financial terminology readability.
 */
object ReadabilityScorer {
    fun gradeLevel(text: String): Double {
        val words = Regex("[A-Za-z']+").findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return 0.0
        val sentences = text.split(Regex("[.!?]+")).count { it.isNotBlank() }.coerceAtLeast(1)
        val totalSyllables = words.sumOf { countSyllables(it) }

        val grade = 0.39 * (words.size.toDouble() / sentences) + 11.8 * (totalSyllables.toDouble() / words.size) - 15.59
        return grade.coerceAtLeast(0.0)
    }

    internal fun countSyllables(word: String): Int {
        val clean = word.lowercase().filter { it in 'a'..'z' }
        if (clean.isEmpty()) return 1
        if (clean.length <= 3) return 1

        var w = clean
        if (w.endsWith("e") && !w.endsWith("le") && !w.endsWith("ee") && !w.endsWith("ie")) {
            w = w.dropLast(1)
        }
        if (w.endsWith("ed") && !w.endsWith("ted") && !w.endsWith("ded")) {
            w = w.dropLast(2)
        }
        if (w.endsWith("es") && !w.endsWith("ses") && !w.endsWith("zes") && !w.endsWith("ches") && !w.endsWith("shes")) {
            w = w.dropLast(1)
        }

        val vowelGroups = Regex("[aeiouy]+").findAll(w).count()
        return vowelGroups.coerceAtLeast(1)
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
 *
 * **Safety Guardrail**: Rewrites must preserve all monetary values from the original text;
 * if a candidate drops or modifies any dollar amount, it is rejected to prevent LLM hallucinations.
 */
class ReadabilityGuard(
    private val rewriter: TextRewriter,
    private val maxGrade: Double = MAX_GRADE,
    private val maxAttempts: Int = 1
) {
    suspend fun ensureReadable(text: String): String {
        var best = text
        var bestGrade = ReadabilityScorer.gradeLevel(text)
        val originalAmounts = extractMonetaryAmounts(text)
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

            // Financial Safety Guardrail: All monetary amounts from original text must be preserved in candidate
            val candidateAmounts = extractMonetaryAmounts(candidate)
            if (!candidateAmounts.containsAll(originalAmounts)) {
                break // Reject rewrite if numbers were dropped or altered
            }

            val grade = ReadabilityScorer.gradeLevel(candidate)
            if (grade < bestGrade) {
                best = candidate
                bestGrade = grade
            }
        }
        return best
    }

    internal fun extractMonetaryAmounts(text: String): List<String> {
        return CURRENCY_REGEX.findAll(text).map { it.value }.toList()
    }

    companion object {
        const val MAX_GRADE = 8.0
        private val CURRENCY_REGEX = Regex("\\$\\d+([.,]\\d+)?")
    }
}
