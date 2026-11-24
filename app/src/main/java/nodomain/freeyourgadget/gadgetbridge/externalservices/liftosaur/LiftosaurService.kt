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

import android.content.Context
import androidx.fragment.app.Fragment
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.externalservices.AuthStyle
import nodomain.freeyourgadget.gadgetbridge.externalservices.ExternalService

/**
 * Sends the weight readings of a connected scale to Liftosaur. Liftosaur is hosted at one address,
 * so the user gives no server. Workouts are not sent.
 */
object LiftosaurService : ExternalService {
    override val id = "liftosaur"
    override val nameRes = R.string.service_name_liftosaur
    override val authStyle = AuthStyle.API_TOKEN
    override val serverPrefKey: String? = null
    override val autoUploadTitleRes: Int = R.string.pref_liftosaur_auto_upload_title
    override val autoUploadSummaryRes: Int = R.string.pref_liftosaur_auto_upload_summary

    override fun isLoggedIn(context: Context) = LiftosaurTokenManager(context).isLoggedIn()

    override fun showSetup(fragment: Fragment) {
        LiftosaurSetupBottomSheet().show(fragment.parentFragmentManager, "liftosaur_setup")
    }

    // The key is created and revoked in the Liftosaur settings, so there is nothing to tell the server.
    override fun logout(context: Context): Boolean {
        LiftosaurTokenManager(context).clearTokens()
        return true
    }
}
