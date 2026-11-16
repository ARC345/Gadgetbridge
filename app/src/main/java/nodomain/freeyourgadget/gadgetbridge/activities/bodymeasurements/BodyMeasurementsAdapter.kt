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

package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurement
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemBodyMeasurementBinding
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits

class BodyMeasurementsAdapter(
    private val onClick: (BodyMeasurement) -> Unit,
    private val onLongClick: (BodyMeasurement) -> Unit,
) : RecyclerView.Adapter<BodyMeasurementsAdapter.ViewHolder>() {
    private var items: List<BodyMeasurement> = emptyList()
    private var units: BodyMeasurementUnits? = null
    private var enabled: Set<BodyMeasurementType> = emptySet()

    class ViewHolder(val binding: ItemBodyMeasurementBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(items: List<BodyMeasurement>, units: BodyMeasurementUnits, enabled: Set<BodyMeasurementType>) {
        this.items = items
        this.units = units
        this.enabled = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemBodyMeasurementBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.binding.root.context
        holder.binding.bodyMeasurementItemTime.text = DateUtils.formatDateTime(
            context,
            item.timestamp,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )
        val summary = units?.let { summary(context, item, it, enabled) }.orEmpty()
        holder.binding.bodyMeasurementItemSummary.text = summary
        holder.binding.bodyMeasurementItemSummary.isVisible = summary.isNotEmpty()
        holder.binding.bodyMeasurementItemNote.text = item.note
        holder.binding.bodyMeasurementItemNote.isVisible = !item.note.isNullOrEmpty()
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size

    companion object {
        private const val SHOWN_VALUES = 2

        /** The first enabled values of the entry, for example "Waist at navel 32.25 in · Hips 38.00 in · +4 more". */
        fun summary(context: Context, item: BodyMeasurement, units: BodyMeasurementUnits, enabled: Set<BodyMeasurementType>): String {
            val shown = BodyMeasurementType.entries.filter { it in enabled && item.values.containsKey(it.code) }
            if (shown.isEmpty()) {
                return ""
            }
            val parts = shown.take(SHOWN_VALUES).map {
                context.getString(it.labelRes) + " " + units.formatDisplay(context, it, item.values.getValue(it.code).toDouble())
            }
            val rest = shown.size - SHOWN_VALUES
            return (if (rest > 0) parts + context.getString(R.string.body_measurements_more, rest) else parts).joinToString(" · ")
        }
    }
}
