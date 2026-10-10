package com.talktomyfinances.android.domain

/** The five predefined goal categories every onboarding goal resolves to. */
enum class FinancialGoal(val label: String) {
    PAYCHECK_STABILITY("Paycheck Stability"),
    SAFETY_NET("Safety Net"),
    MAJOR_SAVINGS_TARGET("Major Savings Target"),
    DEBT_MINIMIZATION("Debt Minimization"),
    MINDFUL_SPENDING("Mindful Spending");

    companion object {
        val FALLBACK = MINDFUL_SPENDING

        fun fromLabel(raw: String?): FinancialGoal? {
            val normalized = raw?.trim()?.trim('"', '.', '\'') ?: return null
            return entries.firstOrNull {
                it.label.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized.replace(' ', '_'), ignoreCase = true)
            }
        }
    }
}

/** Boundary to the LLM that classifies free text into a goal label. Returns raw model text. */
fun interface GoalClassifier {
    suspend fun classify(customGoal: String): String?
}

/**
 * Resolves a user's onboarding goal. Predefined picks pass straight through; "Other" text is
 * silently mapped by the [GoalClassifier], defaulting to [FinancialGoal.FALLBACK] when the
 * model answer is blank, unrecognised, or the call fails.
 */
class GoalResolver(private val classifier: GoalClassifier) {
    suspend fun resolveCustom(customGoal: String): FinancialGoal {
        if (customGoal.isBlank()) return FinancialGoal.FALLBACK
        return try {
            FinancialGoal.fromLabel(classifier.classify(customGoal.trim())) ?: FinancialGoal.FALLBACK
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FinancialGoal.FALLBACK
        }
    }
}

