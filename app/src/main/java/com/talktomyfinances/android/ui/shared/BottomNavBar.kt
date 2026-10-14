package com.talktomyfinances.android.ui.shared

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.talktomyfinances.android.R
import com.talktomyfinances.android.ui.chat.ChatRoute
import com.talktomyfinances.android.ui.insights.InsightsRoute
import com.talktomyfinances.android.ui.theme.Teal

sealed class BottomNavItem(val route: Any, val icon: Int, val label: Int) {
    object Insights : BottomNavItem(InsightsRoute, R.drawable.ic_generate, R.string.nav_bar_insights)
    object Chat : BottomNavItem(ChatRoute(), R.drawable.ic_voice_chat, R.string.nav_bar_chat)
}

/** Two-screen flow per ADR 0002: Home/Insights and Chat. */
@Composable
fun BottomNavBar(selectedIndex: Int, navigateTo: (Any) -> Unit) {
    val items = listOf(BottomNavItem.Insights, BottomNavItem.Chat)

    NavigationBar {
        items.forEachIndexed { index, item ->
            val label = stringResource(item.label)

            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { navigateTo(item.route) },
                icon = { Icon(
                    painter = painterResource(item.icon),
                    contentDescription = label
                ) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Teal,
                    indicatorColor = Color.Transparent,
                    selectedTextColor = Teal
                ),
                label = { Text(label) }
            )
        }
    }
}
