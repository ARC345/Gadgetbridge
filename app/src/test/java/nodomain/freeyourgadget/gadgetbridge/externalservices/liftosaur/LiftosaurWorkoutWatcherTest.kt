/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.liftosaur

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftosaurWorkoutWatcherTest {

    @Test
    fun matchesLiveWorkoutNotification() {
        assertTrue(
            LiftosaurWorkoutWatcher.isWorkoutNotification(
                "com.liftosaur.www.twa", 100, "liftosaur_live_updates_v2"
            )
        )
    }

    @Test
    fun matchesDoNotDisturbVariant() {
        assertTrue(
            LiftosaurWorkoutWatcher.isWorkoutNotification(
                "com.liftosaur.www.twa", 100, "liftosaur_live_updates_dnd"
            )
        )
    }

    @Test
    fun ignoresOtherPackages() {
        assertFalse(
            LiftosaurWorkoutWatcher.isWorkoutNotification(
                "com.example.other", 100, "liftosaur_live_updates_v2"
            )
        )
    }

    @Test
    fun ignoresOtherNotificationIds() {
        assertFalse(
            LiftosaurWorkoutWatcher.isWorkoutNotification(
                "com.liftosaur.www.twa", 101, "liftosaur_live_updates_v2"
            )
        )
    }

    @Test
    fun ignoresOtherChannels() {
        assertFalse(
            LiftosaurWorkoutWatcher.isWorkoutNotification(
                "com.liftosaur.www.twa", 100, "push_channel"
            )
        )
        assertFalse(
            LiftosaurWorkoutWatcher.isWorkoutNotification("com.liftosaur.www.twa", 100, null)
        )
    }
}
