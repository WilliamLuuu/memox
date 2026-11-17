package cool.hin.memox.presentation.activity.main.fragment

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.isEmpty
import cool.hin.memox.utils.showErrorDialog

class SearchFragment : MemoXFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TODO: autofocus and show keyboard
        val initialFolder =
            arguments?.let {
                BundleCompat.getSerializable(it, EXTRA_INITIAL_FOLDER, Folder::class.java)
            }
        binding?.ChipGroup?.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding?.MainListView?.scrollIndicators = View.SCROLL_INDICATOR_TOP
        }
        super.onViewCreated(view, savedInstanceState)
        // Errors arrive as one-shot LiveData events observed with the view lifecycle, so a
        // detached Fragment can never receive (and crash on) a late query failure. Each failure
        // carries its request generation; a failure superseded by a newer fetch is rejected here
        // even if it was posted after the newer fetch cleared the previous error.
        model.searchResults?.errors?.observe(viewLifecycleOwner) { event ->
            event?.handle { failure ->
                if (model.searchResults?.isCurrentRequest(failure.requestId) == true) {
                    showErrorDialog(
                        failure.error,
                        R.string.unknown_error,
                        getString(R.string.unknown_error),
                    )
                }
            }
        }

        val initialLabel = arguments?.getString(EXTRA_INITIAL_LABEL)
        model.currentLabel = initialLabel
        if (initialLabel?.isEmpty() == true) {
            val checked =
                when (initialFolder ?: model.folder.value) {
                    Folder.NOTES -> R.id.Notes
                    Folder.DELETED -> R.id.Deleted
                    else -> R.id.Notes
                }

            binding?.ChipGroup?.apply {
                setOnCheckedStateChangeListener { _, checkedId ->
                    when (checkedId.first()) {
                        R.id.Notes -> model.folder.value = Folder.NOTES
                        R.id.Deleted -> model.folder.value = Folder.DELETED
                    }
                }
                check(checked)
                isVisible = true
            }
        } else binding?.ChipGroup?.isVisible = false
        binding?.DateRangeFilter?.apply {
            setOnClickListener { openDateRangePicker() }
            setOnLongClickListener {
                model.setSearchDateRange(null, null)
                text = getString(R.string.date_range)
                true
            }
        }
        getObservable().observe(viewLifecycleOwner) { items ->
            // While loading, the transient empty list is not a real result; pruning the
            // consumer's multi-selection against it would silently drop all selected notes.
            if (model.searchResults?.isLoadingNow != true) {
                model.actionMode.updateSelected(items?.filterIsInstance<BaseNote>()?.map { it.id })
            }
            notesAdapter?.setSearchKeyword(model.keyword)
        }

        model.searchResults?.isLoading?.observe(viewLifecycleOwner) { isLoading ->
            binding?.ImageView?.isVisible = !isLoading && model.searchResults.isEmpty
            binding?.LoadingProgress?.isVisible = isLoading
        }
    }


    private fun openDateRangePicker() {
        val picker =
            com.google.android.material.datepicker.MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(R.string.date_range)
                .setSelection(
                    androidx.core.util.Pair(
                        com.google.android.material.datepicker.MaterialDatePicker
                            .todayInUtcMilliseconds(),
                        com.google.android.material.datepicker.MaterialDatePicker
                            .todayInUtcMilliseconds(),
                    )
                )
                .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val from = localDayStart(selection.first)
            val to = localDayEnd(selection.second)
            model.setSearchDateRange(from, to)
            val format = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            binding?.DateRangeFilter?.text =
                "${format.format(java.util.Date(from))} – ${format.format(java.util.Date(to))}"
        }
        picker.show(childFragmentManager, "date-range")
    }

    private fun localDayStart(utcMillis: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = utcMillis
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun localDayEnd(utcMillis: Long): Long {
        val start = java.util.Calendar.getInstance().apply {
            timeInMillis = localDayStart(utcMillis)
        }
        start.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return start.timeInMillis - 1
    }
    override fun getBackground() = R.drawable.search

    override fun getObservable() = model.searchResults!!.results

    companion object {
        const val EXTRA_INITIAL_FOLDER = "memox.intent.extra.INITIAL_FOLDER"
        const val EXTRA_INITIAL_LABEL = "memox.intent.extra.INITIAL_LABEL"
    }
}
