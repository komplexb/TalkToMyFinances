package com.talktomyfinances.android.domain

/**
 * Pluggable contract for calculating Flesch-Kincaid reading levels.
 */
interface ReadabilityCalculator {
    fun gradeLevel(text: String): Double
}

/**
 * Standard Flesch-Kincaid Grade Level Calculator.
 * Employs English phonetic and morphological syllable counting rules (including diphthongs,
 * hiatus vowel pairs, silent endings, and financial terminology exceptions) to accurately
 * measure financial text readability in sub-millisecond execution time with zero cold start.
 */
object ReadabilityScorer : ReadabilityCalculator {

    private val FINANCIAL_EXCEPTIONS = mapOf(
        "liability" to 5,
        "liabilities" to 5,
        "annuity" to 4,
        "annuities" to 4,
        "portfolio" to 4,
        "portfolios" to 4,
        "superannuation" to 6,
        "depreciation" to 5,
        "amortization" to 5,
        "deceleration" to 5,
        "expenditure" to 4,
        "expenditures" to 4,
        "discretionary" to 5
    )

    // Vowel pairs that form two distinct syllables (hiatus) rather than a single diphthong
    private val HIATUS_REGEX = Regex("(?<![cts])ia|(?<![tsc])io|(?<=[a-z])ua|(?<=[a-z])ui|eo|iu")

    override fun gradeLevel(text: String): Double {
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
        if (clean.length <= 2) return 1

        FINANCIAL_EXCEPTIONS[clean]?.let { return it }

        var w = clean

        // Silent 'e' at end (except '-le' preceded by consonant, '-ee', '-ie')
        if (w.endsWith("e") && !w.endsWith("le") && !w.endsWith("ee") && !w.endsWith("ie")) {
            w = w.dropLast(1)
        }
        // Past tense '-ed' (except '-ted' and '-ded' where 'ed' is pronounced)
        if (w.endsWith("ed") && !w.endsWith("ted") && !w.endsWith("ded")) {
            w = w.dropLast(2)
        }
        // Plural '-es' (except '-ses', '-zes', '-ches', '-shes', '-xes' where 'es' is pronounced)
        if (w.endsWith("es") && !w.endsWith("ses") && !w.endsWith("zes") && !w.endsWith("ches") && !w.endsWith("shes") && !w.endsWith("xes")) {
            w = w.dropLast(1)
        }

        // Count base vowel groups
        val baseVowelGroups = Regex("[aeiouy]+").findAll(w).count()

        // Add additional syllables for vowel pairs pronounced in hiatus
        val hiatusExtra = HIATUS_REGEX.findAll(w).count()

        var syllables = baseVowelGroups + hiatusExtra

        // Consonant + 'le' at end forms a syllabic 'l' (e.g. ta-ble, sim-ple)
        if (clean.length > 2 && clean.endsWith("le") && clean[clean.length - 3] !in "aeiouy") {
            if (!w.endsWith("le")) {
                syllables++
            }
        }

        return syllables.coerceAtLeast(1)
    }
}

/** Boundary to the on-device model (Firebase AI Logic) that simplifies text. */
fun interface TextRewriter {
    suspend fun rewrite(text: String, targetGrade: Int): String
}

/**
 * Hybrid Inference readability fallback: text from the Auditor Engine is returned untouched if
 * it already reads at or below [maxGrade]; otherwise it is rewritten on-device using the
 * on-device LLM [rewriter] (Firebase AI Logic Gemini Nano).
 *
 * **Safety Guardrails**:
 * 1. Rewrites must preserve all monetary values from the original text; if a candidate drops
 *    or modifies any dollar amount, it is rejected to prevent LLM numerical hallucinations.
 * 2. Monotonic Quality Selection: Keeps the lowest grade (most readable) candidate across attempts.
 * 3. Graceful Degradation: Returns the original text if on-device model fails or throws an exception.
 */
class ReadabilityGuard(
    private val rewriter: TextRewriter,
    private val scorer: ReadabilityCalculator = ReadabilityScorer,
    private val maxGrade: Double = MAX_GRADE,
    private val maxAttempts: Int = 1
) {
    suspend fun ensureReadable(text: String): String {
        var best = text
        var bestGrade = scorer.gradeLevel(text)
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

            val grade = scorer.gradeLevel(candidate)
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

