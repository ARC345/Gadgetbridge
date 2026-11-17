/*  Copyright (C) 2026 Arnav Rastogi

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Sends each new voice note to Gemini, one share per note. Gadgetbridge may open Gemini from
 * the background because it has "Display over other apps" (it is also the companion app of
 * the band). When it may not, when Gemini is missing, or when automatic sending is off, a
 * notification offers "Send to Gemini" and "Play" instead.
 */
object CrpRingVoiceNoteShare {
    private val LOG = LoggerFactory.getLogger(CrpRingVoiceNoteShare::class.java)

    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    private const val MIME = "audio/ogg"
    private const val NOTIFICATION_TAG = "crpring_voice_note"

    fun geminiIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND)
        .setType(MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .setPackage(GEMINI_PACKAGE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Sends to Gemini if it is installed, else opens the normal share menu. */
    fun sendIntent(context: Context, uri: Uri): Intent {
        val gemini = geminiIntent(uri)
        if (gemini.resolveActivity(context.packageManager) != null) {
            return gemini.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val plain = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(plain, context.getString(R.string.crpring_voice_note_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun deliver(context: Context, notes: List<SavedVoiceNote>, autoSend: Boolean) {
        val canOpen = autoSend && Settings.canDrawOverlays(context) &&
            geminiIntent(Uri.EMPTY).resolveActivity(context.packageManager) != null
        for (note in notes) {
            if (canOpen && open(context, note)) continue
            notifyNewNote(context, note.fileName, note.uri)
        }
    }

    private fun open(context: Context, note: SavedVoiceNote): Boolean = try {
        context.startActivity(geminiIntent(note.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LOG.info("Sent voice note {} to Gemini", note.fileName)
        true
    } catch (e: Exception) {
        LOG.warn("Could not open Gemini for voice note {}", note.fileName, e)
        false
    }

    fun playIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notifyNewNote(context: Context, fileName: String, uri: Uri) {
        val id = fileName.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val send = PendingIntent.getActivity(context, id * 2, sendIntent(context, uri), flags)
        val play = PendingIntent.getActivity(context, id * 2 + 1, playIntent(uri), flags)
        val notification = NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice)
            .setContentTitle(context.getString(R.string.crpring_voice_note_title))
            .setContentText(fileName)
            .setContentIntent(play)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_send, context.getString(R.string.crpring_voice_note_send_gemini), send)
            .addAction(R.drawable.ic_play, context.getString(R.string.crpring_voice_note_play), play)
            .build()
        GB.notify(NOTIFICATION_TAG, id, notification, context)
    }
}
