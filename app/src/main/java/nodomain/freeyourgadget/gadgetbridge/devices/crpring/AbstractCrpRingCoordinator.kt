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
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.crpring.CrpRingDeviceSupport

/** Devices that use the MoYoung "CRP ring" SDK protocol (service fdda). */
abstract class AbstractCrpRingCoordinator : AbstractBLEDeviceCoordinator() {
    override fun createDeviceSupport(device: GBDevice): DeviceSupport = CrpRingDeviceSupport()

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.FITNESS_BAND

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_miband6

    override fun getSampleProvider(device: GBDevice, session: DaoSession): SampleProvider<out AbstractActivitySample> =
        CrpRingActivitySampleProvider(device, session)

    override fun getSpo2SampleProvider(device: GBDevice, session: DaoSession): TimeSampleProvider<out Spo2Sample> =
        GenericSpo2SampleProvider(device, session)

    override fun getSupportedDeviceSpecificSettings(device: GBDevice): IntArray =
        intArrayOf(R.xml.devicesettings_pairingkey)

    override fun supportsDataFetching(device: GBDevice): Boolean = true
    override fun supportsActivityTracking(device: GBDevice): Boolean = true
    override fun supportsSleepMeasurement(device: GBDevice): Boolean = true
    override fun supportsRemSleep(device: GBDevice): Boolean = true
    override fun supportsAwakeSleep(device: GBDevice): Boolean = true
    override fun supportsHeartRateMeasurement(device: GBDevice): Boolean = true
    override fun supportsManualHeartRateMeasurement(device: GBDevice): Boolean = true
    override fun supportsSpo2(device: GBDevice): Boolean = true
    override fun supportsFindDevice(device: GBDevice): Boolean = true
}
