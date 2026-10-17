package com.talktomyfinances.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    fun auditReceiptCardRendersConciseSummaryAndMetricWithoutTechnicalClutter() {
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

        // Verify summary and verified badge are visible cleanly
        rule.onNodeWithText("Verified Fact").assertIsDisplayed()
        rule.onNodeWithText("Total spend calculated across matching transactions is $253.50.").assertIsDisplayed()
        rule.onNodeWithText("Verified Metric: $253.50").assertIsDisplayed()

        // Raw developer schema and toggle should NOT exist
        rule.onNodeWithText("Tap for receipt").assertDoesNotExist()
        rule.onNodeWithText("Audit Specification:").assertDoesNotExist()
        rule.onNodeWithText("• Target Metric: TOTAL_SUM").assertDoesNotExist()
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
    fun chatMessageWithVisualCardRendersCleanInlineCardWithoutMathAuditClutter() {
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
        rule.onNodeWithText("$125.00").assertIsDisplayed()
        rule.onNodeWithText("(67.4%)").assertIsDisplayed()
        rule.onNodeWithText("Menulog").assertIsDisplayed()
        rule.onNodeWithText("$60.50").assertIsDisplayed()
        rule.onNodeWithText("(32.6%)").assertIsDisplayed()

        // Verify math audit box and $/ % toggle are removed from Visual Card
        rule.onNodeWithText("Deterministic Math Audit Receipt").assertDoesNotExist()
        rule.onNodeWithText("0 LLM Arithmetic").assertDoesNotExist()
        rule.onNodeWithText("View: $ (tap for %)").assertDoesNotExist()
    }
}
