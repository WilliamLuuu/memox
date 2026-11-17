package cool.hin.memox.presentation.activity.main.fragment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.SortedListAdapterCallback
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Item
import cool.hin.memox.data.model.hasAnyUpcomingNotifications
import cool.hin.memox.presentation.activity.note.PickNoteActivity
import cool.hin.memox.presentation.activity.note.reminders.RemindersActivity
import cool.hin.memox.presentation.view.main.BaseNoteAdapter
import cool.hin.memox.presentation.view.main.sorting.BaseNoteLastNotificationSort
import cool.hin.memox.presentation.view.main.sorting.BaseNoteMostRecentNotificationSort
import cool.hin.memox.presentation.view.main.sorting.BaseNoteNextNotificationSort
import cool.hin.memox.presentation.viewmodel.preference.SortDirection

class RemindersFragment : MemoXFragment() {
    private val currentReminderNotes = MutableLiveData<List<Item>>(emptyList())
    private val allReminderNotes: LiveData<List<Item>> by lazy { model.reminderNotes!! }
    private var filterMode = FilterOptions.UPCOMING

    private val pickNoteForReminder =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val id =
                    result.data?.getLongExtra(PickNoteActivity.EXTRA_PICKED_NOTE_ID, -1L) ?: -1L
                if (id != -1L) {
                    startActivity(
                        Intent(requireContext(), RemindersActivity::class.java)
                            .putExtra(RemindersActivity.NOTE_ID, id)
                    )
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentReminderNotes.value = allReminderNotes.value ?: emptyList()
        binding?.ListFab?.isVisible = true
        binding?.ListFab?.setOnClickListener {
            pickNoteForReminder.launch(Intent(requireContext(), PickNoteActivity::class.java))
        }
        binding?.ReminderFilter?.visibility = View.VISIBLE
        allReminderNotes.observe(viewLifecycleOwner) { _ -> applyFilter(filterMode) }
        binding?.ReminderFilter?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) {
                binding?.ReminderFilter?.check(R.id.upcoming)
                return@setOnCheckedStateChangeListener
            }
            filterMode =
                when (checkedIds.first()) {
                    R.id.elapsed -> FilterOptions.ELAPSED
                    R.id.upcoming -> FilterOptions.UPCOMING
                    else -> FilterOptions.ALL
                }
            applyFilter(filterMode)
        }
    }

    override fun getBackground(): Int = R.drawable.notifications

    override fun getObservable(): LiveData<List<Item>> = currentReminderNotes

    override fun notesAdapterSortCallback(): (BaseNoteAdapter) -> SortedListAdapterCallback<Item> =
        { adapter ->
            when (filterMode) {
                FilterOptions.UPCOMING -> BaseNoteNextNotificationSort(adapter, SortDirection.ASC)
                FilterOptions.ELAPSED -> BaseNoteLastNotificationSort(adapter, SortDirection.DESC)
                FilterOptions.ALL -> BaseNoteMostRecentNotificationSort(adapter, SortDirection.DESC)
            }
        }

    fun applyFilter(filterOptions: FilterOptions) {
        val items: List<Item> = allReminderNotes.value ?: return
        val filteredList: List<Item> =
            when (filterOptions) {
                FilterOptions.ALL -> {
                    items
                }
                FilterOptions.UPCOMING -> {
                    items.filter { it is BaseNote && it.reminders.hasAnyUpcomingNotifications() }
                }
                FilterOptions.ELAPSED -> {
                    items.filter { it is BaseNote && !it.reminders.hasAnyUpcomingNotifications() }
                }
            }
        currentReminderNotes.value = filteredList
        notesAdapter?.setNotesSortCallback(notesAdapterSortCallback())
    }
}

enum class FilterOptions {
    UPCOMING,
    ELAPSED,
    ALL,
}
