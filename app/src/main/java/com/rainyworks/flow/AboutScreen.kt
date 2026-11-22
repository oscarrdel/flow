package com.rainyworks.flow

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Component(val name: String, val license: String, val asset: String, val note: String = "")

private val components = listOf(
    Component("LAME 3.100", "GNU LGPL 2.0 o posterior", "licenses/lame-lgpl.txt",
        "Codificador MP3, incluido como biblioteca compartida independiente. Código fuente en lame.sourceforge.io y en el repositorio de Flow (third_party/lame)."),
    Component("AndroidX Media3", "Apache 2.0", "licenses/apache-2.0.txt"),
    Component("Jetpack Compose y AndroidX", "Apache 2.0", "licenses/apache-2.0.txt"),
    Component("Room", "Apache 2.0", "licenses/apache-2.0.txt"),
    Component("Coil", "Apache 2.0", "licenses/apache-2.0.txt"),
    Component("Kotlin y kotlinx", "Apache 2.0", "licenses/apache-2.0.txt"),
)

private const val SOURCE_URL = "https://github.com/oscarrdel/flow"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    BackHandler { if (open != null) open = null else onBack() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (open != null) open = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                }
                Text(open?.first ?: "Acerca de Flow", style = MaterialTheme.typography.titleLarge)
            }
            val current = open
            if (current != null) {
                LicenseText(current.second)
            } else {
                LazyColumn(Modifier.padding(horizontal = FlowTheme.spacing.lg)) {
                    item {
                        Text("Flow ${BuildConfigVersion.name}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(FlowTheme.spacing.sm))
                        Text(
                            "Copyright (C) 2026 Oscar Reyes. Flow es software libre bajo la Licencia Pública General " +
                                "de GNU versión 3 o posterior. Se distribuye sin ninguna garantía.",
                            color = FlowColors.TextSecondary,
                        )
                        Row {
                            TextButton(onClick = { open = "GPL-3.0" to "licenses/gpl-3.0.txt" }) { Text("Ver licencia") }
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                            }) { Text("Código fuente") }
                        }
                        Spacer(Modifier.height(FlowTheme.spacing.lg))
                        Text("Componentes de terceros", style = MaterialTheme.typography.titleMedium)
                    }
                    items(components) { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { open = c.name to c.asset }
                                .padding(vertical = FlowTheme.spacing.md),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(c.license, color = FlowColors.Accent, style = MaterialTheme.typography.bodySmall)
                            if (c.note.isNotEmpty()) Text(c.note, color = FlowColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider(color = FlowColors.Steel)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseText(asset: String) {
    val context = LocalContext.current
    val text by produceState("", asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Text(
        text,
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(FlowTheme.spacing.lg),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = FlowColors.TextSecondary,
    )
}

private object BuildConfigVersion {
    const val name = "0.1.0"
}
