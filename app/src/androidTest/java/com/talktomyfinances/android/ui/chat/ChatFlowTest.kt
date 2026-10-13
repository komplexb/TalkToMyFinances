package com.talktomyfinances.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.DenominatorStrategy
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.TargetMetric
import com.talktomyfinances.android.domain.TemporalGrain
import org.junit.Rule
import org.junit.Test

class ChatFlowTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun auditReceiptCardExpandsToRevealSpecificationDetails() {
        val receipt = AuditReceipt(
            summary = "Total spend calculated across matching transactions is $253.50.",
            value = 253.50,
            spec = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedMerchants = listOf("Uber"),
                temporalGrain = TemporalGrain.MONTH,
                denominatorStrategy = DenominatorStrategy.ACTIVE_PERIODS_ONLY
            )
        )

        rule.setContent {
            AuditReceiptCard(receipt = receipt)
        }

        // Verify summary and verified badge are initially visible
        rule.onNodeWithText("Verified Fact").assertIsDisplayed()
        rule.onNodeWithText("Total spend calculated across matching transactions is $253.50.").assertIsDisplayed()
        rule.onNodeWithText("Verified Metric: $253.50").assertIsDisplayed()
        rule.onNodeWithText("Tap for receipt").assertIsDisplayed()

        // Tap to expand disclosure
        rule.onNodeWithText("Tap for receipt").performClick()

        // Verify expanded details
        rule.onNodeWithText("Hide details").assertIsDisplayed()
        rule.onNodeWithText("Audit Specification:").assertIsDisplayed()
        rule.onNodeWithText("• Target Metric: TOTAL_SUM").assertIsDisplayed()
        rule.onNodeWithText("• Temporal Grain: MONTH").assertIsDisplayed()
        rule.onNodeWithText("• Denominator Strategy: ACTIVE_PERIODS_ONLY").assertIsDisplayed()
        rule.onNodeWithText("• Included Merchants: Uber").assertIsDisplayed()
    }

    @Test
    fun chatMessageRendersUserAndAssistantTurns() {
        val userTurn = ChatMessage(
            sender = MessageSender.USER,
            text = "What was my spend on Uber?"
        )
        val assistantTurn = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "You spent $253.50 on Uber this month."
        )

        rule.setContent {
            ChatMessageItem(message = userTurn)
            ChatMessageItem(message = assistantTurn)
        }

        rule.onNodeWithText("What was my spend on Uber?").assertIsDisplayed()
        rule.onNodeWithText("You spent $253.50 on Uber this month.").assertIsDisplayed()
    }
}
