package com.fc.safe.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import com.fc.safe.desktop.screens.NewKeysScreen
import com.fc.safe.desktop.screens.SecurityGuidelinesScreen
import com.fc.safe.desktop.ui.ImportKeyDialog
import com.fc.safe.desktop.ui.PhraseKeyDialog
import core.crypto.Encryptor
import core.crypto.Kdf
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import org.jetbrains.skia.EncodedImageFormat
import utils.Hex
import java.io.File

/**
 * Renders the Import keys dialog off-screen once per kind of input, and the
 * Input phrase dialog, so the verdict line and the fields that come and go
 * with it can be eyeballed without clicking through the app.
 *
 * Run: `./gradlew :app-desktop:importKeyRenderSmoke`
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val prikey = ByteArray(32) { (it + 1).toByte() }
    val wif = KeyTools.prikey32To38WifCompressed(Hex.toHex(prikey))
    val pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey.copyOf()))
    val fid = KeyTools.prikeyToFid(prikey.copyOf())
    val cipher = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
        .apply { setKdf(Kdf.Sha256Iv_No1_NrC7) }
        .encryptByPassword(prikey.copyOf(), "correct horse".toCharArray()).toJson()

    val out = File("build/import-key-smoke").apply { mkdirs() }
    fun shot(name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 640, height = 600) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
        try {
            // Two frames: the dialog layer is laid out on the first.
            scene.render()
            val data = scene.render(16_000_000).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }
    fun import(name: String, text: String, awaitingPassword: Boolean = false, error: String? = null) =
        shot(name) {
            ImportKeyDialog(
                busy = false, error = error, awaitingPassword = awaitingPassword,
                onInputChanged = {}, onSubmit = {}, onDismiss = {}, initialText = text,
            )
        }

    import("1-empty", "")
    import("2-prikey", wif)
    import("3-pubkey", pubkey)
    import("4-fid", fid)
    import("5-bad-fid", fid.dropLast(1) + if (fid.last() == '2') '3' else '2')
    import("6-cipher", cipher, error = "Wrong password or damaged cipher")
    import("7-backup-awaiting-password", "{\"id\":\"$fid\",\"prikeyCipher\":\"AAAA\"}", awaitingPassword = true)
    import("8-unknown", "hello")
    shot("9-phrase") { PhraseKeyDialog(busy = false, error = null, onSubmit = { _, _, _ -> }, onDismiss = {}) }
    shot("10-random-keys") { Navigator(NewKeysScreen(findNice = false)) }
    shot("11-find-nice-fid") { Navigator(NewKeysScreen(findNice = true)) }
    shot("12-guidelines") { Navigator(SecurityGuidelinesScreen()) }
    println("wrote ${out.absolutePath}")
}
