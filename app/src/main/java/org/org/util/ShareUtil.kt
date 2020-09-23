package org.org.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.org.codex.Codex
import org.org.codex.Encryption
import org.org.stencil.Stencil


object ShareUtil {

    fun shareImage(context: Context, imageUri: Uri, message: String, encodedFriendPublicKey: ByteArray) {

        // Encrypt the message
        // TODO: Use encrypted message in stencil
        val encryptedMessage = Encryption(context).encrypt(encodedFriendPublicKey, message)

        // Encode the image
        val newBitmap = Stencil().encode(context, encryptedMessage!!, imageUri)

        // TODO: Save bitmap to image roll to get URI for sharing intent

        // Sharing requires a custom intent whose action must be Intent.ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {

            // MIME type is always image/jpeg because the repo saves all the memes locally as JPEGs.
            type = "image/jpeg"

            // TODO: Share the new bitmap instead
            // Resolves the meme's local URL and adds it to the intent as Intent.EXTRA_STREAM
            putExtra(Intent.EXTRA_STREAM, imageUri)

            // You need this flag to let the intent read local data and stream the image content to another app.
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        // Starts the share sheet.
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun shareText(context: Context, message: String, encodedFriendPublicKey: ByteArray) {

        val codex = Codex()
        val encryptedMessage = Encryption(context).encrypt(encodedFriendPublicKey, message)

        encryptedMessage?.let {

            val encodedMessage = codex.encode(encryptedMessage)
            val sendIntent: Intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, encodedMessage)
            }

            val shareIntent = Intent.createChooser(sendIntent, null)
            context.startActivity(shareIntent)
        }

    }
}