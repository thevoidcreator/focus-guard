package ru.focusguard.android

import android.content.*
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import ru.focusguard.core.Protocol

object Transfer {
    fun share(ctx: Context, text: String) {
        val dir = File(ctx.cacheDir, "public-transfer").apply { mkdirs() }
        val file = File(dir, "FocusGuard-${java.util.UUID.randomUUID()}.fg2.json").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("Focus Guard", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // WhatsApp is an always-allowed transport. Never grant arbitrary messengers a permanent exception.
        send.setPackage("com.whatsapp")
        require(send.resolveActivity(ctx.packageManager) != null) { "Установите WhatsApp для отправки" }
        ctx.startActivity(send)
    }
    fun read(ctx: Context, uri: Uri): String = Protocol.readStream(requireNotNull(ctx.contentResolver.openInputStream(uri)))
}
