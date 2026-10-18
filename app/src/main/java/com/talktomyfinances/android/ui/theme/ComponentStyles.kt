package com.talktomyfinances.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.Style
import androidx.compose.ui.unit.dp

object ComponentStyles {
    val auditReceiptCardStyle: Style = Style {
        shape(RoundedCornerShape(12.dp))
        contentPadding(12.dp)
        externalPaddingTop(4.dp)
    }

    val auditReceiptBadgeIconStyle: Style = Style {
        size(18.dp)
        shape(CircleShape)
    }

    val auditReceiptMetricPillStyle: Style = Style {
        shape(RoundedCornerShape(6.dp))
        contentPaddingHorizontal(8.dp)
        contentPaddingVertical(4.dp)
    }

    val auditReceiptBulletRowStyle: Style = Style {
        contentPaddingVertical(2.dp)
    }
}
