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
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import java.util.regex.Pattern

/** Pebble Qore Vox: screenless band with voice recording. Syncs health data and voice notes. */
class PebbleQoreVoxCoordinator : AbstractCrpRingCoordinator() {
    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^PBL Qore Vox.*")

    override fun getManufacturer(): String = "Pebble"

    override fun getDeviceNameResource(): Int = R.string.devicetype_pebble_qore_vox

    override fun supportsVoiceNotes(): Boolean = true

    override fun getSupportedDeviceSpecificSettings(device: GBDevice): IntArray =
        super.getSupportedDeviceSpecificSettings(device) + R.xml.devicesettings_crpring_voice_notes
}
