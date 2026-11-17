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

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class CrpRingVoiceNoteShareTest : TestBase() {
    private val uri: Uri = Uri.parse("content://media/external_primary/audio/media/42")

    @Test
    fun geminiIntentSendsTheFileToGemini() {
        val intent = CrpRingVoiceNoteShare.geminiIntent(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/ogg", intent.type)
        assertEquals(CrpRingVoiceNoteShare.GEMINI_PACKAGE, intent.`package`)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun withoutGeminiTheShareMenuOpens() {
        // Robolectric has no Gemini app installed.
        val intent = CrpRingVoiceNoteShare.sendIntent(context, uri)
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertNull(inner.`package`)
        assertEquals(uri, inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun playIntentOpensTheFile() {
        val intent = CrpRingVoiceNoteShare.playIntent(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("audio/ogg", intent.type)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun notificationHasSendAndPlayButtons() {
        CrpRingVoiceNoteShare.notifyNewNote(context, "1794944171.ogg", uri)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = shadowOf(manager).allNotifications
        assertEquals(1, notifications.size)
        assertEquals(2, notifications[0].actions.size)
    }
}
