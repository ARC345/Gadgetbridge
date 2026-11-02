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
package nodomain.freeyourgadget.gadgetbridge.activities.charts

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.BottomsheetWeightReadingsBinding
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemWeightReadingBinding
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Lists the weight readings of a device, newest first. A long press deletes a reading, for
 * example one that belongs to someone else. Returns under [REQUEST_KEY] after a deletion.
 */
class WeightReadingsBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomsheetWeightReadingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var device: GBDevice
    private val weightUnit = GBApplication.getPrefs().weightUnit
    private val adapter = ReadingsAdapter()

    override fun getTheme() = R.style.ThemeOverlay_App_ChartConfigSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomsheetWeightReadingsBinding.inflate(inflater, container, false)
        device = requireNotNull(BundleCompat.getParcelable(requireArguments(), ARG_DEVICE, GBDevice::class.java))
        binding.weightReadingsList.layoutManager = LinearLayoutManager(requireContext())
        binding.weightReadingsList.adapter = adapter
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            val readings = withContext(Dispatchers.IO) { loadReadings() }
            adapter.readings = readings.toMutableList()
            render()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun loadReadings(): List<Reading> {
        return try {
            GBApplication.acquireDB().use { db ->
                device.deviceCoordinator.getWeightSampleProvider(device, db.daoSession)
                    ?.getAllSamples(0, Long.MAX_VALUE)
                    .orEmpty()
                    .mapNotNull { sample -> sample?.let { Reading(it.timestamp, it.weightKg) } }
                    .sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            LOG.error("Error loading weight readings", e)
            emptyList()
        }
    }

    private fun render() {
        binding.weightReadingsEmpty.isVisible = adapter.readings.isEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(reading: Reading) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weight_readings_delete_title)
            .setMessage(formatWeight(reading.weightKg) + "\n" + formatTime(reading.timestamp))
            .setPositiveButton(R.string.delete) { _, _ -> delete(reading) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(reading: Reading) {
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteSample(reading.timestamp) }
            if (!deleted) {
                GB.toast(requireContext(), R.string.weight_readings_delete_unsupported, Toast.LENGTH_SHORT, GB.WARN)
                return@launch
            }
            adapter.readings.remove(reading)
            render()
            setFragmentResult(REQUEST_KEY, Bundle())
        }
    }

    private fun deleteSample(timestamp: Long): Boolean {
        return try {
            GBApplication.acquireDB().use { db ->
                val provider = device.deviceCoordinator.getWeightSampleProvider(device, db.daoSession)
                if (provider !is AbstractTimeSampleProvider<*>) {
                    return false
                }
                provider.deleteSample(timestamp)
                true
            }
        } catch (e: Exception) {
            LOG.error("Error deleting the weight reading at {}", timestamp, e)
            false
        }
    }

    private fun formatWeight(weightKg: Float): String {
        return WeightUnit.formatConvertedWeight(requireContext(), WeightUnit.convertWeight(weightKg.toDouble(), weightUnit), weightUnit)
    }

    private fun formatTime(timestamp: Long): String {
        return DateUtils.formatDateTime(
            requireContext(),
            timestamp,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
        )
    }

    private data class Reading(val timestamp: Long, val weightKg: Float)

    private inner class ReadingsAdapter : RecyclerView.Adapter<ReadingsAdapter.ViewHolder>() {
        var readings: MutableList<Reading> = mutableListOf()

        inner class ViewHolder(val item: ItemWeightReadingBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemWeightReadingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val reading = readings[position]
            holder.item.weightReadingValue.text = formatWeight(reading.weightKg)
            holder.item.weightReadingTime.text = formatTime(reading.timestamp)
            holder.item.root.setOnLongClickListener {
                confirmDelete(reading)
                true
            }
        }

        override fun getItemCount() = readings.size
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(WeightReadingsBottomSheet::class.java)

        const val REQUEST_KEY = "weight_readings"
        private const val ARG_DEVICE = "device"

        @JvmStatic
        fun newInstance(device: GBDevice) = WeightReadingsBottomSheet().apply {
            arguments = Bundle().apply { putParcelable(ARG_DEVICE, device) }
        }
    }
}
