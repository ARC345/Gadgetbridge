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

/**
 * Holds back the commands that need a verified phone (time, user info) until the band
 * answers the PIN. The vendor app does the same. If the band does not answer, the vendor
 * app continues after a timeout, so [onTimeout] sends the commands. A rejected PIN sends
 * nothing. One gate is used for each connection; call [reset] on a new connection.
 */
class CrpRingPostAuth(private val sendCommands: () -> Unit) {
    private var done = false

    var rejected = false
        private set

    @Synchronized
    fun onAuthResult(ok: Boolean) {
        if (done) return
        done = true
        if (ok) sendCommands() else rejected = true
    }

    @Synchronized
    fun onTimeout() {
        if (done) return
        done = true
        sendCommands()
    }

    @Synchronized
    fun reset() {
        done = false
        rejected = false
    }
}
