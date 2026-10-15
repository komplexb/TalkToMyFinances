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
import com.talktomyfinances.android.domain.VisualCardItemData
import com.talktomyfinances.android.domain.VisualCardPayload
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

    @Test
    fun chatMessageWithVisualCardRendersInlineCardAndAuditReceipt() {
        val visualCard = VisualCardPayload(
            cardType = "category_breakdown",
            title = "Takeaway Spend Breakdown",
            totalAmount = 185.50,
            items = listOf(
                VisualCardItemData(category = "Uber Eats", amount = 125.00, percentage = 67.38),
                VisualCardItemData(category = "Menulog", amount = 60.50, percentage = 32.62)
            ),
            auditReceipt = AuditReceipt(
                summary = "Takeaway spend computed deterministically.",
                value = 185.50
            ),
            timeframe = "This Month"
        )
        val assistantMessage = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "I've popped that breakdown onto your screen so you can check it out whenever you're ready.",
            visualCard = visualCard,
            auditReceipt = visualCard.auditReceipt
        )

        rule.setContent {
            ChatMessageItem(message = assistantMessage)
        }

        // Verify spoken confirmation bubble text
        rule.onNodeWithText("I've popped that breakdown onto your screen so you can check it out whenever you're ready.").assertIsDisplayed()

        // Verify visual card header
        rule.onNodeWithText("Takeaway Spend Breakdown").assertIsDisplayed()
        rule.onNodeWithText("$185.50").assertIsDisplayed()
        rule.onNodeWithText("This Month").assertIsDisplayed()

        // Verify breakdown items rendered inline
        rule.onNodeWithText("Uber Eats").assertIsDisplayed()
        rule.onNodeWithText("Menulog").assertIsDisplayed()

        // Verify inline plain-English audit receipt
        rule.onNodeWithText("Deterministic Math Audit Receipt").assertIsDisplayed()
        rule.onNodeWithText("Takeaway spend computed deterministically.").assertIsDisplayed()
    }
}
