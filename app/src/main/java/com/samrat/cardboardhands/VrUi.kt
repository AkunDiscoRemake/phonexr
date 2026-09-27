package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import zone.ien.hig.CupertinoButton
import zone.ien.hig.CupertinoButtonDefaults
import zone.ien.hig.CupertinoButtonSize
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme

// Pieces shared by the compose-hig windows of the VR home.

/** An app icon from a Drawable or Bitmap, or the first letter on the accent colour. */
@Composable
fun VrIcon(icon: Any?, title: String, size: Dp = 56.dp, round: Boolean = false) {
    val image = remember(icon) {
        when (icon) {
            is Bitmap -> icon.asImageBitmap()
            is Drawable -> runCatching { icon.toBitmap(192, 192).asImageBitmap() }.getOrNull()
            else -> null
        }
    }
    val shape = RoundedCornerShape(if (round) size / 2 else size * .24f)
    if (image != null) {
        Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(CupertinoTheme.colorScheme.accent), contentAlignment = Alignment.Center) {
            CupertinoText(title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * .42f).sp)
        }
    }
}

/** App Store card: icon, title, one line under it and a pill button. */
@Composable
fun VrStoreCard(title: String, subtitle: String, icon: Any?, button: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(CupertinoTheme.colorScheme.secondarySystemGroupedBackground)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        VrIcon(icon, title)
        Column(Modifier.weight(1f)) {
            CupertinoText(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CupertinoText(subtitle, color = CupertinoTheme.colorScheme.secondaryLabel, style = CupertinoTheme.typography.footnote,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CupertinoButton(
            onClick = onClick,
            size = CupertinoButtonSize.Small,
            colors = CupertinoButtonDefaults.tintedButtonColors(),
        ) { CupertinoText(button, fontWeight = FontWeight.SemiBold) }
    }
}

/** Large title of a VR window page. */
@Composable
fun VrTitle(text: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
        CupertinoText(text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (detail != null) CupertinoText(detail, color = CupertinoTheme.colorScheme.secondaryLabel)
    }
}

/** Section heading inside a VR window. */
@Composable
fun VrHeading(text: String) {
    CupertinoText(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp))
}
