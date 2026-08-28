package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.SafeButton
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/**
 * Pretty-print and minify arbitrary JSON input. Matches Android's
 * `JsonConvertActivity` — pure format conversion, no schema
 * validation beyond "does it parse".
 *
 * Accepts objects or arrays transparently: both go through
 * [JsonParser.parseString] → `JsonElement` → Gson round-trip.
 * Invalid JSON surfaces as an error in the error slot and the
 * result stays empty.
 */
class JsonConvertScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var input by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        fun convert(pretty: Boolean) {
            error = null
            if (input.isBlank()) return
            runCatching {
                val element = JsonParser.parseString(input)
                if (pretty) {
                    GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element)
                } else {
                    GsonBuilder().disableHtmlEscaping().create().toJson(element)
                }
            }.onSuccess { result = it }
                .onFailure { error = "Not valid JSON: ${it.message}"; result = "" }
        }

        AppShell(
            title = "JSON converter",
            scaffoldState = scaffoldState,
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CryptoIoBlock(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = "JSON (object or array)",
                    placeholder = "Paste JSON to prettify or minify",
                    heightDp = 200,
                    onClear = { input = ""; result = ""; error = null },
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        input = ""; result = ""; error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = input.isNotBlank(),
                        onClick = { convert(pretty = true) },
                    ) { Text("Prettify") }
                    SafeButton(
                        enabled = input.isNotBlank(),
                        onClick = { convert(pretty = false) },
                    ) { Text("Minify") }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                CryptoIoBlock(
                    value = result,
                    onValueChange = {},
                    label = "Result",
                    readOnly = true,
                    heightDp = 240,
                )
            }
        }
    }
}
