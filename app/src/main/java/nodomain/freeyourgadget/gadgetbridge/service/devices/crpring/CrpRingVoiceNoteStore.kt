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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import androidx.core.content.FileProvider
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Saves downloaded voice notes. Each note becomes an Ogg Opus file in Music/Gadgetbridge/Voice
 * notes (MediaStore, Android 10 and later) or in the app's external files folder. The raw band
 * file is also kept in the app's external files folder, in case the audio format is different
 * from what we expect.
 */
/** A saved voice note: its file name and a content URI that other apps can be allowed to read. */
class SavedVoiceNote(val fileName: String, val uri: Uri)

object CrpRingVoiceNoteStore {
    private val LOG = LoggerFactory.getLogger(CrpRingVoiceNoteStore::class.java)
    private const val PREF_DOWNLOADED = "crpring_downloaded_voice_notes"
    private const val MUSIC_SUBFOLDER = "Gadgetbridge/Voice notes"
    private const val PACKET_SIZE = 40
    private const val SAMPLE_RATE = 16000

    fun downloadedNames(device: GBDevice): Set<String> =
        GBApplication.getDeviceSpecificSharedPrefs(device.address)
            .getStringSet(PREF_DOWNLOADED, emptySet())?.toSet() ?: emptySet()

    /** Returns the saved note (also marked as downloaded), or null. Never throws. */
    fun save(context: Context, device: GBDevice, recording: CrpRingRecording): SavedVoiceNote? {
        val safeName = CrpRingRecordingParsers.safeFileName(recording.name)
        val base = safeName.substringBeforeLast('.').ifEmpty { safeName }
        if (recording.data.size % PACKET_SIZE != 0) {
            LOG.warn("Voice note {} is {} bytes, not a whole number of {}-byte packets", recording.name, recording.data.size, PACKET_SIZE)
        }
        saveRaw(safeName, recording.data)
        val fileName = "$base.ogg"
        val uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMusic(context, fileName, recording.data) else saveToAppFolder(context, fileName, recording.data)
        } catch (e: Throwable) {
            // Also catches OutOfMemoryError: a very long note must not crash the app.
            LOG.error("Failed to save voice note {}", recording.name, e)
            null
        } ?: return null
        markDownloaded(device, recording.name)
        return SavedVoiceNote(fileName, uri)
    }

    private fun saveRaw(fileName: String, data: ByteArray) {
        try {
            val dir = File(FileUtils.getExternalFilesDir(), "voice-notes-raw")
            dir.mkdirs()
            File(dir, fileName).writeBytes(data)
        } catch (e: Throwable) {
            LOG.warn("Failed to keep the raw voice note {}", fileName, e)
        }
    }

    private fun writeOgg(data: ByteArray, out: OutputStream) =
        OggOpusWriter.write(data, PACKET_SIZE, SAMPLE_RATE, 1, out)

    private fun saveToMusic(context: Context, fileName: String, data: ByteArray): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + MUSIC_SUBFOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
            stream.use { writeOgg(data, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish $uri")
        } catch (e: Throwable) {
            // Remove the pending entry, so a retry does not make "name (1).ogg".
            try { resolver.delete(uri, null, null) } catch (ignored: Throwable) {}
            throw e
        }
        LOG.info("Saved voice note {} to {}", fileName, uri)
        return uri
    }

    private fun saveToAppFolder(context: Context, fileName: String, data: ByteArray): Uri {
        val dir = File(FileUtils.getExternalFilesDir(), "voice-notes")
        dir.mkdirs()
        val file = File(dir, fileName)
        try {
            FileOutputStream(file).use { writeOgg(data, it) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        LOG.info("Saved voice note {} to {}", fileName, dir)
        // The app's file provider shares external storage, so other apps can be allowed to read it.
        return FileProvider.getUriForFile(context, context.packageName + ".screenshot_provider", file)
    }

    private fun markDownloaded(device: GBDevice, name: String) {
        val prefs = GBApplication.getDeviceSpecificSharedPrefs(device.address)
        val names = HashSet(prefs.getStringSet(PREF_DOWNLOADED, emptySet()) ?: emptySet())
        names.add(name)
        prefs.edit().putStringSet(PREF_DOWNLOADED, names).apply()
    }
}
