package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme
import kotlin.concurrent.thread

/**
 * "Android‑приложения": every installed phone app, runnable in a VR window (a Shizuku virtual
 * display, like Minecraft in the cinema). Touching an icon opens it next to the other windows.
 */
class AndroidAppsContent(private val context: Context, private val open: (packageName: String, label: String) -> Unit) :
    ComposeContent(barTitle = tr("Android‑приложения")) {
    private class App(val packageName: String, val label: String, val icon: Drawable?)

    private var apps by mutableStateOf<List<App>?>(null)

    init {
        thread(name = "PhoneXR android apps") {
            val pm = context.packageManager
            apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != context.packageName }
                .map { (name, info) -> App(name, info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize().background(CupertinoTheme.colorScheme.systemGroupedBackground)) {
            VrTitle(tr("Android‑приложения"), apps?.let { "${it.size} приложений · открываются окнами через Shizuku" } ?: "Загрузка…")
            val list = apps ?: return@Column HigSpinner()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.packageName }) { app ->
                    Column(
                        Modifier.clip(RoundedCornerShape(18.dp)).clickable { open(app.packageName, app.label) }.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VrIcon(app.icon, app.label, size = 72.dp, round = true)
                        CupertinoText(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(110.dp))
                    }
                }
            }
        }
    }

    companion object {
        private const val PREFS = "android_apps"

        /** The store's "Get" for this PhoneXR app: it then shows on the VR home screen. */
        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, value: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
}
