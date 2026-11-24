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
import nodomain.freeyourgadget.gadgetbridge.externalservices.ApiTokenStore

/** The Liftosaur API key of the user, kept encrypted at rest. */
class LiftosaurTokenManager(context: Context) : ApiTokenStore(context, "liftosaur_tokens") {
    /** Liftosaur issues its API keys under a fixed prefix, so anything else is a typo. */
    override fun isLoggedIn(): Boolean = getApiToken()?.startsWith(TOKEN_PREFIX) == true

    companion object {
        const val TOKEN_PREFIX = "lftsk_"
    }
}
