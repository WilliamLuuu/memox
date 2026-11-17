package cool.hin.memox.presentation.activity.note.reminders

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TimePicker
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.Repetition
import cool.hin.memox.data.model.RepetitionTimeUnit
import cool.hin.memox.data.model.nextRepetition
import cool.hin.memox.data.model.toCalendar
import cool.hin.memox.databinding.ActivityRemindersBinding
import cool.hin.memox.databinding.DialogReminderCustomRepetitionBinding
import cool.hin.memox.databinding.DialogReminderRepetitionBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.add
import cool.hin.memox.presentation.checkAlarmPermission
import cool.hin.memox.presentation.checkNotificationPermission
import cool.hin.memox.presentation.format
import cool.hin.memox.presentation.getQuantityStringPlain
import cool.hin.memox.presentation.initListView
import cool.hin.memox.presentation.setCancelButton
import cool.hin.memox.presentation.showAndFocus
import cool.hin.memox.presentation.view.main.reminder.ReminderAdapter
import cool.hin.memox.presentation.view.main.reminder.ReminderListener
import cool.hin.memox.presentation.viewmodel.MemoXModel
import cool.hin.memox.utils.canScheduleAlarms
import cool.hin.memox.data.model.toText
import cool.hin.memox.presentation.showToast
import cool.hin.memox.utils.now
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class RemindersActivity : LockedActivity<ActivityRemindersBinding>(), ReminderListener {

    private lateinit var alarmPermissionActivityResultLauncher: ActivityResultLauncher<Intent>
    private val model: MemoXModel by viewModels()
    private lateinit var reminderAdapter: ReminderAdapter
    private var selectedReminder: Reminder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemindersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdgeInsets()
        setupToolbar()
        setupRecyclerView()

        alarmPermissionActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (canScheduleAlarms()) {
                    showDatePickerDialog(selectedReminder)
                }
            }
        val noteId = intent.getLongExtra(NOTE_ID, 0L)
        lifecycleScope.launch {
            model.setState(noteId)
            if (model.reminders.value.isEmpty()) {
                showDatePickerDialog()
            } else if (!canScheduleAlarms()) {
                checkNotificationPermission(
                    REQUEST_NOTIFICATION_PERMISSION_REQUEST_CODE,
                    alsoCheckAlarmPermission = true,
                ) {}
            }
        }
    }

    /**
     * Configures the activity for edge-to-edge display, handling status bar and navigation bar
     * colors, and applying appropriate insets to layout elements.
     */
    private fun configureEdgeToEdgeInsets() {
        // 1. Enable edge-to-edge display for the activity window.
        // This makes the content draw behind the system bars (status bar and navigation bar).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 4. Apply window insets to specific views to prevent content from being obscured.

        // Set an OnApplyWindowInsetsListener on the root layout.
        // This listener will be called whenever the system insets change (e.g., status bar, nav
        // bar, keyboard).
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            // Get the system bars insets (status bar and navigation bar).
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Get the IME (Input Method Editor - keyboard) insets.
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Adjust the top margin of the Toolbar to account for the status bar.
            // This pushes the toolbar down so it's not hidden behind the status bar.
            binding.Toolbar.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout() // Request a layout pass to apply the new margin
            }
            // Apply padding to the RecyclerView and EmptyState TextView to account for
            // the navigation bar and the software keyboard.
            // This ensures that the content within these views is not obscured at the bottom.
            // We use padding here because these views are below the toolbar and extend to
            // match_parent height.
            binding.MainListView.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    systemBarsInsets.bottom + imeInsets.bottom,
                )
            }
            binding.EmptyState.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    systemBarsInsets.bottom + imeInsets.bottom,
                )
            }

            // Return the insets to allow them to be dispatched to child views if necessary.
            insets
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATION_PERMISSION_ON_OPEN_REQUEST_CODE -> {
                if (
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    checkAlarmPermission(alarmPermissionActivityResultLauncher) {
                        showDatePickerDialog(selectedReminder)
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        binding.Toolbar.apply {
            setNavigationOnClickListener { finish() }
            menu.add(R.string.add_reminder, R.drawable.add) { showDatePickerDialog() }
            menu.add(R.string.add_to_calendar, R.drawable.calendar_add_on) { addToCalendar() }
        }
    }

    private fun addToCalendar() {
        val reminders = model.reminders.value.orEmpty()
        if (reminders.isEmpty()) return
        val formatter = java.text.SimpleDateFormat.getDateTimeInstance()
        val labels = reminders.map { formatter.format(calendarStart(it)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_calendar)
            .setItems(labels.toTypedArray()) { _, which ->
                val start = calendarStart(reminders[which])
                val intent =
                    Intent(Intent.ACTION_INSERT)
                        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(
                            android.provider.CalendarContract.Events.TITLE,
                            getString(R.string.app_name),
                        )
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.time)
                        .putExtra(
                            android.provider.CalendarContract.EXTRA_EVENT_END_TIME,
                            start.time + 60 * 60 * 1000,
                        )
                // No resolveActivity pre-check: with targetSdk 30+ package visibility it returns
                // null even when a calendar handler exists, falsely rejecting valid exports.
                // startActivity itself throws ActivityNotFoundException when nothing can handle it.
                try {
                    startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    showToast(R.string.calendar_app_unavailable)
                }
            }
            .show()
    }

    /**
     * 重复提醒的 dateTime 是首次触发时间；导入日历时应使用下一次触发时间，
     * 否则会导出一个早已过去的一次性事件。
     */
    private fun calendarStart(reminder: Reminder): Date =
        reminder.nextRepetition(cool.hin.memox.utils.now().time) ?: reminder.dateTime


    private fun setupRecyclerView() {
        reminderAdapter =
            ReminderAdapter(
                preferences.dateFormat.value,
                preferences.timeFormat.value,
                this,
            )
        binding.MainListView.apply {
            initListView(this@RemindersActivity)
            adapter = reminderAdapter
        }
        model.reminders.observe(this) { reminders ->
            reminderAdapter.submitList(reminders)
            if (reminders.isEmpty()) {
                binding.EmptyState.visibility = View.VISIBLE
            } else binding.EmptyState.visibility = View.INVISIBLE
        }
    }

    private fun showDatePickerDialog(reminder: Reminder? = null, calendar: Calendar? = null) {
        selectedReminder = reminder
        checkNotificationPermission(
            REQUEST_NOTIFICATION_PERMISSION_ON_OPEN_REQUEST_CODE,
            alsoCheckAlarmPermission = true,
            alarmPermissionResultLauncher = alarmPermissionActivityResultLauncher,
        ) {
            DatePickerFragment(calendar?.time ?: reminder?.dateTime) { year, month, day ->
                    val usedCalendar = calendar ?: reminder?.dateTime?.toCalendar() ?: now()
                    usedCalendar.set(year, month, day)
                    showTimePickerDialog(reminder, usedCalendar)
                }
                .show(supportFragmentManager, "reminderDatePicker")
        }
    }

    private fun showTimePickerDialog(reminder: Reminder? = null, calendar: Calendar) {
        TimePickerFragment(
                calendar,
                object : TimePickerListener {
                    override fun onBack() {
                        showDatePickerDialog(reminder, calendar)
                    }

                    override fun onTimeSet(view: TimePicker?, hourOfDay: Int, minute: Int) {
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        showRepetitionDialog(reminder, calendar) { updatedRepetition ->
                            val updatedReminder =
                                Reminder(
                                    reminder?.id ?: NEW_REMINDER_ID,
                                    calendar.time,
                                    updatedRepetition,
                                )
                            if (reminder != null) {
                                lifecycleScope.launch { model.updateReminder(updatedReminder) }
                            } else {
                                lifecycleScope.launch { model.addReminder(updatedReminder) }
                            }
                        }
                    }
                },
            )
            .show(supportFragmentManager, "reminderTimePicker")
    }

    private fun showRepetitionDialog(
        reminder: Reminder? = null,
        calendar: Calendar,
        fromCustomRepetitionDialog: Boolean = false,
        onRepetitionSelected: (Repetition?) -> Unit,
    ) {
        val dialogView =
            DialogReminderRepetitionBinding.inflate(layoutInflater).apply {
                if (reminder == null && fromCustomRepetitionDialog) {
                    None.isChecked = true
                } else {
                    reminder?.repetition.apply {
                        when {
                            this == null -> None.isChecked = true
                            value == 1 && unit == RepetitionTimeUnit.DAYS -> Daily.isChecked = true

                            value == 1 && unit == RepetitionTimeUnit.WEEKS ->
                                Weekly.isChecked = true

                            value == 1 && unit == RepetitionTimeUnit.MONTHS && occurrence == null ->
                                Monthly.isChecked = true

                            value == 1 &&
                                unit == RepetitionTimeUnit.MONTHS &&
                                occurrence != null -> {
                                MonthlyAdvanced.apply {
                                    visibility = View.VISIBLE
                                    text = toText(this@RemindersActivity)
                                    isChecked = true
                                }
                            }

                            value == 1 && unit == RepetitionTimeUnit.YEARS ->
                                Yearly.isChecked = true

                            fromCustomRepetitionDialog -> Custom.isChecked = true
                            else -> {
                                showCustomRepetitionDialog(reminder, calendar, onRepetitionSelected)
                                return
                            }
                        }
                    }
                }
            }

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.repetition)
                .setView(dialogView.root)
                .setPositiveButton(R.string.save) { _, _ ->
                    val repetition =
                        when (dialogView.RepetitionOptions.checkedRadioButtonId) {
                            R.id.None -> null
                            R.id.Daily -> Repetition(1, RepetitionTimeUnit.DAYS)
                            R.id.Weekly -> Repetition(1, RepetitionTimeUnit.WEEKS)
                            R.id.Monthly -> Repetition(1, RepetitionTimeUnit.MONTHS)
                            R.id.Yearly -> Repetition(1, RepetitionTimeUnit.YEARS)
                            R.id.MonthlyAdvanced -> reminder?.repetition?.copy()
                            R.id.Custom -> reminder?.repetition?.copy()
                            else -> null
                        }
                    onRepetitionSelected(repetition)
                }
                .setNegativeButton(R.string.back) { _, _ ->
                    showTimePickerDialog(reminder, calendar)
                }
                .show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialogView.apply {
            Custom.setOnClickListener {
                dialog.dismiss()
                showCustomRepetitionDialog(reminder, calendar, onRepetitionSelected)
            }
            None.setOnCheckedEnableButton(positiveButton)
            Daily.setOnCheckedEnableButton(positiveButton)
            Weekly.setOnCheckedEnableButton(positiveButton)
            Monthly.setOnClickListener {
                dialog.dismiss()
                showMonthlyAdvancedRepetitionDialog(reminder, calendar, onRepetitionSelected)
            }
            MonthlyAdvanced.setOnClickListener {
                dialog.dismiss()
                showMonthlyAdvancedRepetitionDialog(reminder, calendar, onRepetitionSelected)
            }
            Yearly.setOnCheckedEnableButton(positiveButton)
        }
    }

    private fun showMonthlyAdvancedRepetitionDialog(
        reminder: Reminder? = null,
        calendar: Calendar,
        onRepetitionSelected: (Repetition?) -> Unit,
    ) {
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val dayOfWeekStr = SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val isLastDayOfMonth = dayOfMonth == calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        val monthlyOptionText =
            if (isLastDayOfMonth) {
                getString(R.string.of_the_month_last, getQuantityStringPlain(R.plurals.days, 1))
            } else {
                "$dayOfMonth. ${getString(R.string.of_the_month, getQuantityStringPlain(R.plurals.days, 1))}"
            }

        val options =
            arrayOf(
                monthlyOptionText,
                "1. ${getString(R.string.of_the_month, dayOfWeekStr)}",
                "2. ${getString(R.string.of_the_month, dayOfWeekStr)}",
                "3. ${getString(R.string.of_the_month, dayOfWeekStr)}",
                "4. ${getString(R.string.of_the_month, dayOfWeekStr)}",
                getString(R.string.of_the_month_last, dayOfWeekStr),
            )

        var selectedIndex = 0
        reminder?.repetition?.let {
            if (it.unit == RepetitionTimeUnit.MONTHS && it.value == 1) {
                selectedIndex =
                    when (it.occurrence) {
                        null -> 0
                        1 -> 1
                        2 -> 2
                        3 -> 3
                        4 -> 4
                        -1 -> 5
                        else -> 0
                    }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.monthly)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val repetition =
                    when (which) {
                        0 -> Repetition(1, RepetitionTimeUnit.MONTHS)
                        1 -> Repetition(1, RepetitionTimeUnit.MONTHS, 1, dayOfWeek)
                        2 -> Repetition(1, RepetitionTimeUnit.MONTHS, 2, dayOfWeek)
                        3 -> Repetition(1, RepetitionTimeUnit.MONTHS, 3, dayOfWeek)
                        4 -> Repetition(1, RepetitionTimeUnit.MONTHS, 4, dayOfWeek)
                        5 -> Repetition(1, RepetitionTimeUnit.MONTHS, -1, dayOfWeek)
                        else -> Repetition(1, RepetitionTimeUnit.MONTHS)
                    }
                onRepetitionSelected(repetition)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.back) { dialog, _ ->
                dialog.dismiss()
                showRepetitionDialog(
                    reminder,
                    calendar,
                    onRepetitionSelected = onRepetitionSelected,
                )
            }
            .show()
    }

    private fun showCustomRepetitionDialog(
        reminder: Reminder? = null,
        calendar: Calendar,
        onRepetitionSelected: (Repetition?) -> Unit,
    ) {
        val dialogView =
            DialogReminderCustomRepetitionBinding.inflate(layoutInflater).apply {
                reminder?.repetition?.let {
                    when (it.unit) {
                        RepetitionTimeUnit.MINUTES -> Minutes
                        RepetitionTimeUnit.HOURS -> Hours
                        RepetitionTimeUnit.DAYS -> Days
                        RepetitionTimeUnit.WEEKS -> Weeks
                        RepetitionTimeUnit.MONTHS -> Months
                        RepetitionTimeUnit.YEARS -> Years
                    }.isChecked = true
                    Value.setText(it.value.toString())
                }
            }

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.repetition_custom)
                .setView(dialogView.root)
                .setPositiveButton(R.string.save) { _, _ ->
                    val value = dialogView.Value.text.toString().toIntOrNull() ?: 1
                    val selectedTimeUnit =
                        when (dialogView.TimeUnitGroup.checkedRadioButtonId) {
                            R.id.Minutes -> RepetitionTimeUnit.MINUTES
                            R.id.Hours -> RepetitionTimeUnit.HOURS
                            R.id.Days -> RepetitionTimeUnit.DAYS
                            R.id.Weeks -> RepetitionTimeUnit.WEEKS
                            R.id.Months -> RepetitionTimeUnit.MONTHS
                            R.id.Years -> RepetitionTimeUnit.YEARS
                            else -> null
                        }
                    onRepetitionSelected(
                        selectedTimeUnit?.let {
                            if (it == RepetitionTimeUnit.MONTHS && value == 1) {
                                showMonthlyAdvancedRepetitionDialog(
                                    reminder,
                                    calendar,
                                    onRepetitionSelected,
                                )
                                return@setPositiveButton
                            }
                            Repetition(value, it)
                        }
                    )
                }
                .setBackgroundInsetBottom(0)
                .setBackgroundInsetTop(0)
                .setNegativeButton(R.string.back) { dialog, _ ->
                    dialog.dismiss()
                    showRepetitionDialog(
                        reminder,
                        calendar,
                        fromCustomRepetitionDialog = true,
                        onRepetitionSelected,
                    )
                }
                .showAndFocus(dialogView.Value, allowFullSize = true)
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialogView.Value.doAfterTextChanged { text ->
            positiveButton.isEnabled = text.hasValueBiggerZero()
        }
        positiveButton.isEnabled = reminder?.repetition != null
    }

    private fun RadioButton.setOnCheckedEnableButton(button: Button) {
        setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                button.isEnabled = true
            }
        }
    }

    private fun Editable?.hasValueBiggerZero() =
        (!isNullOrEmpty() && toString().toIntOrNull()?.let { it > 0 } ?: false)

    private fun confirmDeletion(reminder: Reminder) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_reminder_question)
            .setMessage(
                "${reminder.dateTime.format(
                    preferences.dateFormat.value,
                    preferences.timeFormat.value,
                    ensureFullFormat = true,)}\n${reminder.repetition?.toText(this) ?: getString(R.string.reminder_no_repetition)}"
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { model.removeReminder(reminder) }
            }
            .setCancelButton()
            .show()
    }

    override fun delete(reminder: Reminder) {
        confirmDeletion(reminder)
    }

    override fun edit(reminder: Reminder) {
        showDatePickerDialog(reminder)
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION_ON_OPEN_REQUEST_CODE = 101
        private const val REQUEST_NOTIFICATION_PERMISSION_REQUEST_CODE = 102
        const val NOTE_ID = "NOTE_ID"
        const val NEW_REMINDER_ID = -1L
    }
}
