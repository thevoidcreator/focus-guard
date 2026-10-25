package ru.focusguard.android

import android.content.*
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import ru.focusguard.core.Protocol

object Transfer {
    fun copy(ctx: Context, text: String) { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Focus Guard", text)) }
    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        // WhatsApp is an always-allowed transport. Never grant arbitrary messengers a permanent exception.
        send.setPackage("com.whatsapp")
        require(send.resolveActivity(ctx.packageManager) != null) { "Установите WhatsApp для отправки" }
        ctx.startActivity(send)
    }
    fun read(ctx: Context, uri: Uri): String = Protocol.readStream(requireNotNull(ctx.contentResolver.openInputStream(uri)))
}
