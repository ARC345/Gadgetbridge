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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Saves downloaded voice notes. Each note becomes an Ogg Opus file in Music/Gadgetbridge/Voice
 * notes (MediaStore, Android 10 and later) or in the app's external files folder. The raw band
 * file is also kept in the app's external files folder, in case the audio format is different
 * from what we expect.
 */
object CrpRingVoiceNoteStore {
    private val LOG = LoggerFactory.getLogger(CrpRingVoiceNoteStore::class.java)
    private const val PREF_DOWNLOADED = "crpring_downloaded_voice_notes"
    private const val MUSIC_SUBFOLDER = "Gadgetbridge/Voice notes"
    private const val PACKET_SIZE = 40
    private const val SAMPLE_RATE = 16000

    fun downloadedNames(device: GBDevice): Set<String> =
        GBApplication.getDeviceSpecificSharedPrefs(device.address)
            .getStringSet(PREF_DOWNLOADED, emptySet())?.toSet() ?: emptySet()

    /** Returns true if the note was saved and marked as downloaded. */
    fun save(context: Context, device: GBDevice, recording: CrpRingRecording): Boolean {
        val base = recording.name.substringBeforeLast('.').ifEmpty { recording.name }
        if (recording.data.size % PACKET_SIZE != 0) {
            LOG.warn("Voice note {} is {} bytes, not a whole number of {}-byte packets", recording.name, recording.data.size, PACKET_SIZE)
        }
        saveRaw(recording)
        val ogg = OggOpusWriter.fromRawPackets(recording.data, PACKET_SIZE, SAMPLE_RATE, 1)
        val saved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMusic(context, "$base.ogg", ogg) else saveToAppFolder("$base.ogg", ogg)
        } catch (e: Exception) {
            LOG.error("Failed to save voice note {}", recording.name, e)
            false
        }
        if (saved) markDownloaded(device, recording.name)
        return saved
    }

    private fun saveRaw(recording: CrpRingRecording) {
        try {
            val dir = File(FileUtils.getExternalFilesDir(), "voice-notes-raw")
            dir.mkdirs()
            File(dir, recording.name).writeBytes(recording.data)
        } catch (e: Exception) {
            LOG.warn("Failed to keep the raw voice note {}", recording.name, e)
        }
    }

    private fun saveToMusic(context: Context, fileName: String, data: ByteArray): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + MUSIC_SUBFOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return false
        resolver.openOutputStream(uri)?.use { it.write(data) } ?: return false
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        LOG.info("Saved voice note {} to {}", fileName, uri)
        return true
    }

    private fun saveToAppFolder(fileName: String, data: ByteArray): Boolean {
        val dir = File(FileUtils.getExternalFilesDir(), "voice-notes")
        dir.mkdirs()
        File(dir, fileName).writeBytes(data)
        LOG.info("Saved voice note {} to {}", fileName, dir)
        return true
    }

    private fun markDownloaded(device: GBDevice, name: String) {
        val prefs = GBApplication.getDeviceSpecificSharedPrefs(device.address)
        val names = HashSet(prefs.getStringSet(PREF_DOWNLOADED, emptySet()) ?: emptySet())
        names.add(name)
        prefs.edit().putStringSet(PREF_DOWNLOADED, names).apply()
    }
}
