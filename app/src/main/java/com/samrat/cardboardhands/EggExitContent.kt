package com.samrat.cardboardhands

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The one button of the space easter egg: back from space to where the user was. */
class EggExitContent(private val onExit: () -> Unit) : ComposeContent(pixelWidth = 600, pixelHeight = 240) {
    override val transparent = true

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            VrStoreCard(tr("Выйти"), "PhoneXR", null, tr("Выйти"), Modifier) { onExit() }
        }
    }
}
