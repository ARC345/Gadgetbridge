/*  Copyright (C) 2026 ARC345

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

package nodomain.freeyourgadget.gadgetbridge.model

/** The unit in which body circumferences are shown and typed. They are always stored in cm. */
enum class LengthUnit(val prefValue: String, val decimals: Int) {
    CENTIMETER("cm", 1),
    INCH("in", 2),
    ;

    fun fromCm(cm: Double): Double = if (this == INCH) cm / CM_PER_INCH else cm

    fun toCm(value: Double): Double = if (this == INCH) value * CM_PER_INCH else value

    companion object {
        const val CM_PER_INCH = 2.54

        fun fromPrefValue(value: String?): LengthUnit? = entries.firstOrNull { it.prefValue == value }
    }
}
