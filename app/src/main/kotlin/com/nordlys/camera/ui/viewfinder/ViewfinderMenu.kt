package com.nordlys.camera.ui.viewfinder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nordlys.camera.ui.theme.Selawik

/** A row of the viewfinder menu; [value] shows the current state of a cycling item. */
data class MenuItem(val label: String, val value: String? = null, val onClick: () -> Unit)

/** Slide-up app-bar menu: lowercase items on black, tap outside or back to close. */
@Composable
fun ViewfinderMenu(open: Boolean, items: List<MenuItem>, onClose: () -> Unit) {
    if (open) BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(200)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F1F1F))
                    .navigationBarsPadding()
                    .padding(top = 14.dp, bottom = 20.dp),
            ) {
                items.forEach { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = item.onClick)
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(item.label, style = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 24.sp), color = Color.White)
                        item.value?.let {
                            Text(it, style = TextStyle(fontFamily = Selawik, fontSize = 14.sp), color = Color(0xFF9A9A9A))
                        }
                    }
                }
            }
        }
    }
}
