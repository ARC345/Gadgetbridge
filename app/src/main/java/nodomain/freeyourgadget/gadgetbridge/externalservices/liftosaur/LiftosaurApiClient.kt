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

import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.util.Locale

/** Outcome of one call to the Liftosaur API. */
sealed class LiftosaurResult {
    /** The server accepted the request (any 2xx status). */
    object Stored : LiftosaurResult()

    /** The server already holds a reading at that timestamp (409). The reading is not sent twice. */
    object AlreadyStored : LiftosaurResult()

    /** The server refused the API key (401 or 403). Sending stops until the user sets a new key. */
    object Unauthorized : LiftosaurResult()

    /** The server refused this request (other 4xx). Retrying the same request does not help. */
    class Rejected(val reason: String) : LiftosaurResult()

    /** The server could not be reached, or failed (5xx). A later retry may succeed. */
    class Unavailable(val reason: String) : LiftosaurResult()
}

/**
 * Calls to the Liftosaur API under `/api/v1`. Every call takes the API key explicitly, so the setup
 * screen can check a key before it is stored.
 *
 * Blocking: call off the main thread.
 */
object LiftosaurApiClient {
    private val LOG = LoggerFactory.getLogger(LiftosaurApiClient::class.java)

    const val BASE_URL = "https://www.liftosaur.com/api/v1"

    private const val UNKNOWN_REASON = "unknown error"

    /**
     * Checks [apiKey] with a read-only call. Answers (accepted, reason), where reason is a message
     * to show the user when the key is not accepted.
     */
    fun checkApiKey(apiKey: String): Pair<Boolean, String?> {
        val context = GBApplication.getContext()
        return when (val result = send("GET", "measurements", apiKey, null)) {
            LiftosaurResult.Stored -> true to null
            LiftosaurResult.Unauthorized -> false to context.getString(R.string.toast_error_invalid_api_key)
            is LiftosaurResult.Rejected -> false to result.reason
            is LiftosaurResult.Unavailable -> false to result.reason
            LiftosaurResult.AlreadyStored -> false to UNKNOWN_REASON
        }
    }

    /**
     * Stores one value under [key], dated [timestampMs] (unix epoch milliseconds). [value] carries
     * its unit suffix, such as `82.5kg`. A value the server already holds at that time is replaced
     * with an update, so sending the same reading again is safe.
     */
    fun upsertValue(apiKey: String, key: String, value: String, timestampMs: Long): LiftosaurResult {
        val body = JSONObject()
            .put("value", value)
            .put("timestamp", timestampMs)
            .toString()
        val posted = send("POST", "measurements/$key", apiKey, body)
        if (posted != LiftosaurResult.AlreadyStored) {
            return posted
        }
        val update = JSONObject().put("value", value).toString()
        return send("PUT", "measurements/$key/$timestampMs", apiKey, update)
    }

    /** Formats [value] with [unit] the way the API expects, such as `82.5kg`, with at most two decimals. */
    internal fun formatValue(value: Float, unit: String): String {
        val digits = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        return "$digits$unit"
    }

    internal fun formatWeightKg(kg: Float): String = formatValue(kg, "kg")

    /** Maps an HTTP status to a result. A null [status] means no response was received. */
    internal fun outcomeFor(status: Int?, networkReason: String?): LiftosaurResult = when {
        status == null -> LiftosaurResult.Unavailable(networkReason ?: UNKNOWN_REASON)
        status in 200..299 -> LiftosaurResult.Stored
        status == 409 -> LiftosaurResult.AlreadyStored
        status == 401 || status == 403 -> LiftosaurResult.Unauthorized
        status in 400..499 -> LiftosaurResult.Rejected("HTTP $status")
        else -> LiftosaurResult.Unavailable("HTTP $status")
    }

    private fun send(method: String, path: String, apiKey: String, body: String?): LiftosaurResult {
        var networkReason: String? = null
        val response = InternetUtils.doStringRequestWithStatus(
            uri = "$BASE_URL/$path".toUri(),
            method = method,
            requestHeaders = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = body,
            onError = { networkReason = it },
        )
        val result = outcomeFor(response.statusCode, networkReason)
        if (result is LiftosaurResult.Rejected || result is LiftosaurResult.Unavailable) {
            LOG.warn("Liftosaur {} {} failed: {}", method, path, result)
        }
        return result
    }
}
