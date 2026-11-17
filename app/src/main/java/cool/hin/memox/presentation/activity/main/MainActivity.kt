package cool.hin.memox.presentation.activity.main

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.GravityCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.platform.MaterialFade
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment
import cool.hin.memox.presentation.activity.main.fragment.settings.SettingsFragment
import cool.hin.memox.presentation.viewmodel.preference.NotesSort
import cool.hin.memox.presentation.viewmodel.preference.NotesSortBy
import cool.hin.memox.presentation.viewmodel.preference.NotesView
import cool.hin.memox.presentation.viewmodel.preference.SortDirection
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.data.sync.SyncState
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.data.sync.onedrive.OneDriveAuthHelper
import cool.hin.memox.databinding.ActivityMainBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.activity.main.fragment.DisplayLabelFragment.Companion.EXTRA_DISPLAYED_LABEL
import cool.hin.memox.presentation.activity.main.fragment.MemoXFragment
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.activity.note.NoteActionHandler
import cool.hin.memox.presentation.activity.note.handleRejection
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.hideKeyboard
import cool.hin.memox.presentation.showKeyboard
import cool.hin.memox.presentation.setupProgressDialog
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences.Companion.START_VIEW_DEFAULT
import cool.hin.memox.presentation.viewmodel.progress.MigrationProgress
import cool.hin.memox.utils.LATEST_DATA_SCHEMA
import cool.hin.memox.utils.backup.exportNotes
import cool.hin.memox.utils.runMigrations
import cool.hin.memox.utils.security.showBiometricOrPinPrompt
import cool.hin.memox.utils.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : LockedActivity<ActivityMainBinding>() {

    private lateinit var navController: NavController
    private lateinit var configuration: AppBarConfiguration
    private var restoringSearchText = false
    private lateinit var exportFileActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportNotesActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var identityVerifyActivityResultLauncher: ActivityResultLauncher<Intent>

    private var pendingIdentityVerifiedAction: (() -> Unit)? = null

    private var isStartViewFragment = false

    private var lastToolbarTapAt = 0L

    private fun currentListFragment(): androidx.fragment.app.Fragment? {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.NavHostFragment) ?: return null
        return navHost.childFragmentManager.primaryNavigationFragment
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val toolbar = binding.Toolbar
            val location = IntArray(2).also { toolbar.getLocationOnScreen(it) }
            val inside =
                ev.rawX >= location[0] &&
                    ev.rawX <= location[0] + toolbar.width &&
                    ev.rawY >= location[1] &&
                    ev.rawY <= location[1] + toolbar.height
            if (inside) {
                val now = System.currentTimeMillis()
                if (now - lastToolbarTapAt < 300) {
                    lastToolbarTapAt = 0
                    (currentListFragment() as? ScrollToTop)?.scrollToTop()
                } else {
                    lastToolbarTapAt = now
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private val topLevelDestinations = setOf(
        R.id.Notes,
        R.id.Reminders,
        R.id.Archive,
        R.id.Deleted,
        R.id.Labels,
        R.id.Folders,
        R.id.Settings,
        R.id.SettingsAbout,
    )

    private val syncHideHandler = Handler(Looper.getMainLooper())
    private val syncHideRunnable = Runnable { hideSyncIsland() }
    private val actionModeCancelCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                baseModel.actionMode.close(true)
            }
        }

    /**
     * 搜索栏可见时用 overlay 优先级的 back 回调，抢在输入法之前消费返回键。
     *
     * 输入法窗口持有焦点时，第一次返回只会收起键盘，Activity 的 OnBackPressedCallback
     * 根本不会触发，导致“退出搜索要按两次”。overlay 回调在 API 33+ 上先于 IME 收到
     * back，因此一次即可退出搜索页/收起搜索栏。
     */
    private val searchBackCallback: OnBackInvokedCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnBackInvokedCallback {
                if (baseModel.actionMode.enabled.value) {
                    baseModel.actionMode.close(true)
                    return@OnBackInvokedCallback
                }
                handleSearchBack()
            }
        } else {
            null
        }
    private var searchBackRegistered = false

    var getCurrentFragmentNotes: (() -> Collection<BaseNote>?)? = null

    override fun onSupportNavigateUp(): Boolean {
        // 与返回键一致：从笔记页进入且 Notes 已弹出时，Up 必须结束本任务，
        // 不能走 Navigation 的 deep-link Up 分支重新启动首页。
        if (shouldFinishOnUp()) {
            finish()
            return true
        }
        return NavigationUI.navigateUp(navController, configuration)
    }

    private fun shouldFinishOnUp(): Boolean =
        intent.getBooleanExtra(EXTRA_SKIP_START_VIEW_ON_BACK, false) &&
            navController.previousBackStackEntry == null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "memox" && data.host == "onedrive-auth") {
            lifecycleScope.launch {
                val error = OneDriveAuthHelper.handleRedirect(this@MainActivity, data)
                if (error != null) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.onedrive_auth_failed, error),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.Toolbar)
        configureEdgeToEdgeInsets()
        cool.hin.memox.data.security.PendingContextHolder.context = application
        if (!preferences.isLockEnabled) {
            cool.hin.memox.data.security.SecuritySession.markNoLockConfigured()
        }
        setupFAB()
        setupActionMode()
        setupNavigation()
        restorePendingSettingsDestination()
        setupUpdateCheck()
        setupToolbar()
        setupSyncIsland()
        setupActivityResultLaunchers()

        // 启动数据观察（Room/偏好/搜索）并执行必要的历史数据迁移；必须在页面观察数据前调用。
        checkForMigrations(savedInstanceState)


        // 旋转重建后重新绑定可能存活的日期选择器监听器（FragmentManager 不恢复监听器）。
        restoreDateRangePicker()

        // Sync with the active provider on app start (pull remote updates)
        SyncRouter.syncNow(this)

        // Handle OneDrive OAuth redirect if the activity was (re)launched by it
        handleOAuthRedirect(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (baseModel.actionMode.enabled.value) {
                        return
                    }
                    if (handleSearchBack()) return
                    // Let Navigation handle back first (e.g. Settings sub-page -> Settings root)
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else if (
                        !isStartViewFragment &&
                            !intent.getBooleanExtra(EXTRA_SKIP_START_VIEW_ON_BACK, false)
                    ) {
                        navigateToStartView()
                    } else {
                        finish()
                    }
                }
            },
        )
        onBackPressedDispatcher.addCallback(this, actionModeCancelCallback)

        baseModel.progress.setupProgressDialog(this)
    }

    override fun initViewModel() {}

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NoteActionHandler.REQUEST_NOTIFICATION_PERMISSION_PIN_TO_STATUS -> {
                if (
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    val baseNotes = baseModel.actionMode.selectedNotes.values
                    baseModel.pinBaseNotesToStatusBar(
                        this@MainActivity,
                        baseNotes.any { !it.isPinnedToStatus },
                    )
                } else handleRejection(R.string.to_pin_note_status_bar)
            }
        }
    }

    private fun checkForMigrations(savedInstanceState: Bundle?) {
        val proceed: () -> Unit = {
            baseModel.startObserving()
            val fragmentIdToLoad = intent.getIntExtra(EXTRA_FRAGMENT_TO_OPEN, -1)
            if (fragmentIdToLoad != -1) {
                val skipStartViewOnBack =
                    intent.getBooleanExtra(EXTRA_SKIP_START_VIEW_ON_BACK, false)
                navController.navigate(
                    fragmentIdToLoad,
                    intent.extras,
                    navOptions {
                        // 从笔记页点标签进入时，返回应结束 MainActivity 回到原笔记，
                        // 不能先回退到 Navigation 的起始首页。
                        if (skipStartViewOnBack) {
                            popUpTo(R.id.Notes) { inclusive = true }
                        }
                    },
                )
            } else if (savedInstanceState == null) {
                navigateToStartView()
            }
        }
        if (preferences.dataSchemaId.value < LATEST_DATA_SCHEMA) {
            val migrationProgress = MutableLiveData<MigrationProgress>()
            migrationProgress.setupProgressDialog(this)
            lifecycleScope.launch {
                migrationProgress.postValue(
                    MigrationProgress(R.string.migrating_data, indeterminate = true)
                )
                application.runMigrations { titleId ->
                    migrationProgress.postValue(MigrationProgress(titleId, indeterminate = true))
                }
                migrationProgress.postValue(
                    MigrationProgress(R.string.migrating_data, inProgress = false)
                )
                proceed()
            }
        } else {
            proceed()
        }
    }

    private fun configureEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val navHostFragment = binding.NavHostFragment
        ViewCompat.setOnApplyWindowInsetsListener(binding.RelativeLayout) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            binding.Toolbar.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            binding.ActionMode.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            binding.TakeNote.apply {
                val marginLayoutParams = layoutParams as ViewGroup.MarginLayoutParams
                marginLayoutParams.bottomMargin = 16.dp + systemBarsInsets.bottom + imeInsets.bottom
                marginLayoutParams.marginEnd = 16.dp
                requestLayout()
            }

            navHostFragment.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    systemBarsInsets.bottom + imeInsets.bottom,
                )
            }
            insets
        }
    }

    private fun getStartViewNavigation(): Pair<Int, Bundle> {
        return when (val startView = preferences.startView.value) {
            START_VIEW_DEFAULT -> Pair(R.id.Notes, Bundle())
            else -> {
                val bundle = Bundle().apply { putString(EXTRA_DISPLAYED_LABEL, startView) }
                Pair(R.id.DisplayLabel, bundle)
            }
        }
    }

    private fun navigateToStartView() {
        val (id, bundle) = getStartViewNavigation()
        navController.navigate(id, bundle)
    }

    private fun setupFAB() {
        binding.TakeNote.setOnClickListener {
            val intent = Intent(this, EditNoteActivity::class.java)
            startActivity(prepareNewNoteIntent(intent))
        }
    }

    private fun prepareNewNoteIntent(intent: Intent): Intent {
        return supportFragmentManager
            .findFragmentById(R.id.NavHostFragment)
            ?.childFragmentManager
            ?.fragments
            ?.firstOrNull()
            ?.let { fragment ->
                return if (fragment is MemoXFragment) {
                    fragment.prepareNewNoteIntent(intent)
                } else intent
            } ?: intent
    }

    private fun setupActionMode() {
        binding.ActionMode.setNavigationOnClickListener { baseModel.actionMode.close(true) }

        val transition =
            MaterialFade().apply {
                secondaryAnimatorProvider = null
                excludeTarget(binding.NavHostFragment, true)
                excludeChildren(binding.NavHostFragment, true)
                excludeTarget(binding.TakeNote, true)
            }

        baseModel.actionMode.enabled.observe(this) { enabled ->
            TransitionManager.beginDelayedTransition(binding.RelativeLayout, transition)
            if (enabled) {
                binding.Toolbar.visibility = View.GONE
                binding.ActionMode.visibility = View.VISIBLE
                syncHideHandler.removeCallbacks(syncHideRunnable)
                binding.SyncIsland.visibility = View.GONE
            } else {
                binding.Toolbar.visibility = View.VISIBLE
                binding.ActionMode.visibility = View.GONE
            }
            actionModeCancelCallback.isEnabled = enabled
        }

        @SuppressLint("RestrictedApi")
        val menu = binding.ActionMode.menu
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        baseModel.folder.observe(this@MainActivity, ModelFolderObserver(this, menu, baseModel))
        baseModel.actionMode.loading.observe(this@MainActivity) { loading ->
            menu.setGroupEnabled(Menu.NONE, !loading)
        }
    }

    private fun setupSyncIsland() {
        SyncStatus.state.observe(this) { ui ->
            when (ui.state) {
                SyncState.SYNCING -> {
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    showSyncIsland(syncing = true, message = ui.message)
                }
                SyncState.COMPLETED -> {
                    showSyncIsland(syncing = false, message = ui.message)
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    syncHideHandler.postDelayed(syncHideRunnable, 3000)
                }
                SyncState.IDLE -> {
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    hideSyncIsland()
                }
            }
        }
    }

    /** [message] 非空时展示自定义文案（如批量压缩进度），否则回落到默认同步文案。 */
    private fun showSyncIsland(syncing: Boolean, message: String? = null) {
        val island = binding.SyncIsland
        binding.SyncIslandText.text =
            message
                ?: if (syncing) getString(R.string.sync_status_syncing)
                else getString(R.string.sync_status_completed)
        binding.SyncIslandSpinner.visibility = if (syncing) View.VISIBLE else View.GONE
        binding.SyncIslandDone.visibility = if (syncing) View.GONE else View.VISIBLE
        if (island.visibility != View.VISIBLE) {
            // Expand from the centre (horizontal "pop") for a Dynamic-Island-like reveal.
            island.alpha = 0f
            island.scaleX = 0.4f
            island.scaleY = 1f
            island.visibility = View.VISIBLE
            island.animate()
                .alpha(1f)
                .scaleX(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator())
                .start()
        }
    }

    private fun hideSyncIsland() {
        val island = binding.SyncIsland
        if (island.visibility == View.VISIBLE) {
            // Contract back towards the centre and fade out.
            island.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { island.visibility = View.GONE }
                .start()
        }
    }

    internal fun exportSelectedNotes(mimeType: ExportMimeType) {
        exportNotes(
            baseModel.actionMode.selectedNotes.values,
            mimeType,
            exportFileActivityResultLauncher,
            exportNotesActivityResultLauncher,
        )
    }

    private fun restorePendingSettingsDestination() {
        val destinationId = SettingsFragment.pendingDestinationId ?: return
        SettingsFragment.pendingDestinationId = null
        binding.NavHostFragment.post {
            if (navController.currentDestination?.id != destinationId) {
                navController.navigate(destinationId)
            }
        }
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        configuration = AppBarConfiguration(topLevelDestinations, binding.DrawerLayout)
        binding.NavView.setupWithNavController(navController)

        // Explicit drawer toggle. The NavigationUI auto-wiring (setupActionBarWithNavController)
        // was not opening the drawer on this device, so we wire the hamburger explicitly.
        val toggle =
            ActionBarDrawerToggle(
                this,
                binding.DrawerLayout,
                binding.Toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close,
            )
        binding.DrawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navController.addOnDestinationChangedListener { _, destination, bundle ->
            val isTopLevel = destination.id in topLevelDestinations
            if (isTopLevel) {
                toggle.isDrawerIndicatorEnabled = true
                toggle.syncState()
            } else {
                // 非顶层（设置二级页等）：显式显示带 tint 的返回箭头。
                // ActionBarDrawerToggle 自带的箭头在某些设备因未着色而不可见。
                toggle.isDrawerIndicatorEnabled = false
                binding.Toolbar.setNavigationIcon(R.drawable.back)
                binding.Toolbar.navigationIcon?.setTint(
                    MaterialColors.getColor(
                        binding.Toolbar,
                        com.google.android.material.R.attr.colorOnSurface,
                        0,
                    ),
                )
            }

            toggle.setToolbarNavigationClickListener {
                if (shouldFinishOnUp()) {
                    finish()
                } else if (destination.id in topLevelDestinations) {
                    binding.DrawerLayout.openDrawer(GravityCompat.START)
                } else {
                    NavigationUI.navigateUp(navController, configuration)
                }
            }
            // 搜索栏显隐：进入 Search 目的显示搜索栏，否则显示常态栏
            if (destination.id == R.id.Search) {
                showSearchBar()
            } else {
                hideSearchBar()
                restoringSearchText = true
                binding.SearchEditText.setText(baseModel.keyword)
                restoringSearchText = false
            }
            when (destination.id) {
                R.id.DisplayLabel ->
                    bundle?.getString(EXTRA_DISPLAYED_LABEL)?.let {
                        baseModel.currentLabel = it
                    }
                else -> {
                    baseModel.currentLabel = BaseNoteModel.CURRENT_LABEL_EMPTY
                }
            }
            when (destination.id) {

                R.id.Notes -> {
                    binding.TakeNote.show()
                    // #1: 视图/排序按钮只跟首页 Notes/DisplayLabel/DisplayFolder 共用一个顶部 Toolbar
                    // 显示；其余顶层（Settings/About/Reminders/Archive/Labels/Folders/Deleted）都不显示
                    if (!isSearchBarVisible()) {
                        binding.HomeBar.visibility = View.VISIBLE
                    }
                    // 筛选只作用于首页 baseNotes；DisplayLabel/DisplayFolder 的数据源不消费它。
                    binding.HomeFilterButton.visibility = View.VISIBLE
                }
                R.id.DisplayLabel, R.id.DisplayFolder -> {
                    binding.TakeNote.show()
                    if (!isSearchBarVisible()) {
                        binding.HomeBar.visibility = View.VISIBLE
                    }
                    binding.HomeFilterButton.visibility = View.GONE
                }
                R.id.Labels, R.id.Folders -> {
                    binding.TakeNote.hide()
                    binding.HomeBar.visibility = View.GONE
                    binding.HomeFilterButton.visibility = View.GONE
                }
                else -> {
                    binding.TakeNote.hide()
                    binding.HomeBar.visibility = View.GONE
                    binding.HomeFilterButton.visibility = View.GONE
                }
            }
            isStartViewFragment = isStartViewFragment(destination.id, bundle)
        }

        setupUpdateBadges()
    }

    /**
     * 启动静默检查更新。有新版本时抽屉「关于」菜单项显示红点；点击「关于」进入关于页面，
     * 关于页版本号后的红点点击才弹更新弹窗。
     */
    private fun setupUpdateCheck() {
        UpdateChecker.checkForUpdates(this)
    }

    /**
     * 有新版本时，在抽屉「关于」菜单项（actionLayout 红点）显示红点。红点持续显示直到没有新版本。
     */
    private fun setupUpdateBadges() {
        binding.NavView.post {
            val aboutDot =
                binding.NavView.menu.findItem(R.id.SettingsAbout).actionView
                    ?.findViewById<View>(R.id.badge_dot)
            UpdateChecker.state.observe(this) { info ->
                val available = info != null && UpdateChecker.shouldShow(this, info)
                aboutDot?.visibility = if (available) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupToolbar() {
        val searchEditText = binding.SearchEditText
        val viewToggle = binding.ViewToggleButton
        val sortButton = binding.SortButton
        val homeViewToggle = binding.HomeViewToggleButton
        val homeSort = binding.HomeSortButton

        binding.HomeSearchButton.setOnClickListener {
            if (isSearchBarVisible()) hideSearchBar() else showSearchBar()
        }
        binding.HomeFilterButton.setOnClickListener { showHomeFilterDialog() }

        restoringSearchText = true
        searchEditText.setText(baseModel.keyword)
        restoringSearchText = false
        searchEditText.doAfterTextChanged { text ->
            if (restoringSearchText) return@doAfterTextChanged
            val keyword = text?.toString().orEmpty()
            if (baseModel.keyword != keyword) {
                baseModel.keyword = keyword
            }
            val isSearch = navController.currentDestination?.id == R.id.Search
            if (keyword.isNotEmpty() && !isSearch) {
                navController.navigate(
                    R.id.Search,
                    Bundle().apply {
                        putSerializable(SearchFragment.EXTRA_INITIAL_FOLDER, baseModel.folder.value)
                        putSerializable(SearchFragment.EXTRA_INITIAL_LABEL, baseModel.currentLabel)
                    },
                )
            } else if (keyword.isEmpty() && isSearch) {
                navController.popBackStack()
            }
        }

        fun updateToggleIcon() {
            val isGrid = baseModel.preferences.notesView.value == NotesView.GRID
            val icon = if (isGrid) R.drawable.view_list else R.drawable.view_grid
            viewToggle.setImageResource(icon)
            homeViewToggle.setImageResource(icon)
            viewToggle.contentDescription = getString(if (isGrid) R.string.list else R.string.grid)
            homeViewToggle.contentDescription =
                getString(if (isGrid) R.string.list else R.string.grid)
        }
        updateToggleIcon()
        val onToggleClick = View.OnClickListener {
            val next =
                if (baseModel.preferences.notesView.value == NotesView.GRID) {
                    NotesView.LIST
                } else {
                    NotesView.GRID
                }
            baseModel.preferences.notesView.save(next)
            updateToggleIcon()
        }
        viewToggle.setOnClickListener(onToggleClick)
        homeViewToggle.setOnClickListener(onToggleClick)

        fun updateSortIcon() {
            // 排序按钮固定显示「上下箭头组合」图标（↑↓），点击弹出排序字段菜单
            sortButton.setImageResource(R.drawable.sort_arrows)
            homeSort.setImageResource(R.drawable.sort_arrows)
        }
        val onSortClick = View.OnClickListener { showSortMenu(it) }
        sortButton.setOnClickListener(onSortClick)
        homeSort.setOnClickListener(onSortClick)
        updateSortIcon()

    }

    private fun showHomeFilterDialog() {
        val filter = baseModel.currentHomeFilter()
        val options =
            arrayOf(
                getString(R.string.date_range),
                getString(R.string.filter_label),
                getString(R.string.filter_folder),
                getString(R.string.filter_clear),
            )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickFilterDateRange(filter)
                    1 -> pickFilterLabel(filter)
                    2 -> pickFilterFolder(filter)
                    else -> baseModel.setHomeFilter(BaseNoteModel.HomeFilter())
                }
            }
            .show()
    }

    private fun pickFilterDateRange(current: BaseNoteModel.HomeFilter) {
        val picker =
            com.google.android.material.datepicker.MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(R.string.date_range)
                .build()
        bindDateRangePicker(picker, current)
        picker.show(supportFragmentManager, HOME_FILTER_DATE_PICKER_TAG)
    }

    /**
     * FragmentManager 会在旋转后重建 MaterialDatePicker，但监听器不会随 Fragment 状态恢复。
     * 每次 onCreate 后按 tag 找回仍存活的 picker 并重新绑定，避免确认后筛选不生效。
     */
    private fun restoreDateRangePicker() {
        (supportFragmentManager.findFragmentByTag(HOME_FILTER_DATE_PICKER_TAG)
            as? com.google.android.material.datepicker.MaterialDatePicker<androidx.core.util.Pair<Long, Long>>)
            ?.let { bindDateRangePicker(it, baseModel.currentHomeFilter()) }
    }

    private fun bindDateRangePicker(
        picker: com.google.android.material.datepicker.MaterialDatePicker<androidx.core.util.Pair<Long, Long>>,
        current: BaseNoteModel.HomeFilter,
    ) {
        // MaterialDatePicker 返回 UTC 零点；重新绑定会覆盖旧监听器，不会重复应用筛选。
        picker.addOnPositiveButtonClickListener { selection ->
            baseModel.setHomeFilter(
                current.copy(
                    from = localStartOfDay(selection.first),
                    to = exclusiveEndOfDay(selection.second, java.util.TimeZone.getDefault()),
                )
            )
        }
    }

    private fun pickFilterLabel(current: BaseNoteModel.HomeFilter) {
        val values = baseModel.labels.value.orEmpty().map { it.value }
        if (values.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_label)
            .setItems(values.toTypedArray()) { _, which ->
                baseModel.setHomeFilter(current.copy(label = values[which]))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickFilterFolder(current: BaseNoteModel.HomeFilter) {
        val values = baseModel.folders.value.orEmpty().map { it.name }
        if (values.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_folder)
            .setItems(values.toTypedArray()) { _, which ->
                baseModel.setHomeFilter(current.copy(folderId = values[which]))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 搜索框可见时优先只收起键盘，保留搜索框和关键词；
     * 键盘已收起时再按返回才会收起搜索框，但关键词仍保留在 [BaseNoteModel.keyword]。
     */
    private fun handleSearchBack(): Boolean {
        if (!isSearchBarVisible()) return false
        val imeVisible =
            ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            hideKeyboard(binding.SearchEditText)
            return true
        }
        if (navController.currentDestination?.id == R.id.Search) {
            // 键盘已收起后的下一次返回才真正退出 Search；关键词仍保存在 model 里。
            navController.popBackStack()
            return true
        }
        hideSearchBar()
        return true
    }
    internal fun showSearchBar() {
        registerSearchBackCallback()
        if (binding.SearchPill.visibility != View.VISIBLE) {
            binding.SearchPill.visibility = View.VISIBLE
            binding.HomeBar.visibility = View.GONE
            binding.HomeSearchButton.visibility = View.GONE
            // Space 与 SearchPill 都是 weight=1，会各占 1/2；
            // 搜索时让 Space 隐藏，SearchPill 自然占满剩余空间。
            binding.ToolbarSpacer.visibility = View.GONE
        }
        // 重新打开搜索框时保留关键词，但必须同步进入 Search 页；
        // 否则文字还留在搜索框里，列表却是未过滤的首页笔记，看起来像搜索结果错误。
        if (
            !binding.SearchEditText.text.isNullOrBlank() &&
            navController.currentDestination?.id != R.id.Search
        ) {
            navController.navigate(
                R.id.Search,
                Bundle().apply {
                    putSerializable(SearchFragment.EXTRA_INITIAL_FOLDER, baseModel.folder.value)
                    putSerializable(SearchFragment.EXTRA_INITIAL_LABEL, baseModel.currentLabel)
                },
            )
        }
        // 进入/呼出搜索框时让输入框重新获得焦点并弹出键盘，
        // 避免搜索结果页接管后输入框丢失输入连接（键盘还挂着却无法输入，必须再点一次）。
        binding.SearchEditText.post {
            binding.SearchEditText.requestFocus()
            showKeyboard(binding.SearchEditText)
        }
    }

    internal fun hideSearchBar() {
        unregisterSearchBackCallback()
        hideKeyboard(binding.SearchEditText)
        if (binding.SearchPill.visibility != View.GONE) {
            binding.SearchPill.visibility = View.GONE
            // 目的地没有变化时不会触发导航监听，这里必须按当前目的地恢复 HomeBar。
            val showHomeBar =
                navController.currentDestination?.id in HOME_BAR_DESTINATIONS
            binding.HomeBar.visibility = if (showHomeBar) View.VISIBLE else View.GONE
            binding.HomeSearchButton.visibility = View.VISIBLE
            // 恢复 Space 把 HomeBar/Avatar 推回 toolbar 最右。
            binding.ToolbarSpacer.visibility = View.VISIBLE
        }
    }

    private fun registerSearchBackCallback() {
        if (searchBackRegistered) return
        val callback = searchBackCallback ?: return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        searchBackRegistered = true
    }

    private fun unregisterSearchBackCallback() {
        if (!searchBackRegistered) return
        val callback = searchBackCallback ?: return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        searchBackRegistered = false
    }

    fun isSearchBarVisible(): Boolean = binding.SearchPill.visibility == View.VISIBLE

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val current = baseModel.preferences.notesSorting.value
        NotesSortBy.entries.forEach { sortBy ->
            val label = getString(sortBy.textResId)
            val title =
                if (sortBy == current.sortedBy) {
                    "$label  ${if (current.sortDirection == SortDirection.DESC) "▼" else "▲"}"
                } else {
                    label
                }
            val item = popup.menu.add(Menu.NONE, sortBy.ordinal, Menu.NONE, title)
            item.isChecked = (sortBy == current.sortedBy)
        }
        popup.menu.setGroupCheckable(Menu.NONE, true, true)
        popup.setOnMenuItemClickListener { item ->
            val sortBy = NotesSortBy.entries[item.itemId]
            val cur = baseModel.preferences.notesSorting.value
            val next =
                if (sortBy == cur.sortedBy) {
                    cur.copy(
                        sortDirection =
                            if (cur.sortDirection == SortDirection.DESC) {
                                SortDirection.ASC
                            } else {
                                SortDirection.DESC
                            },
                    )
                } else {
                    cur.copy(sortedBy = sortBy)
                }
            baseModel.preferences.notesSorting.save(next)
            true
        }
        popup.show()
    }

    private fun isStartViewFragment(id: Int, bundle: Bundle?): Boolean {
        val (startViewId, startViewBundle) = getStartViewNavigation()
        return startViewId == id &&
            startViewBundle.getString(EXTRA_DISPLAYED_LABEL) ==
                bundle?.getString(EXTRA_DISPLAYED_LABEL)
    }

    internal fun navigateWithAnimation(id: Int) {
        val options = navOptions {
            launchSingleTop = true
            anim {
                exit = androidx.navigation.ui.R.anim.nav_default_exit_anim
                enter = androidx.navigation.ui.R.anim.nav_default_enter_anim
                popExit = androidx.navigation.ui.R.anim.nav_default_pop_exit_anim
                popEnter = androidx.navigation.ui.R.anim.nav_default_pop_enter_anim
            }
            popUpTo(navController.graph.startDestination) { inclusive = false }
        }
        navController.navigate(id, null, options)
    }

    private fun setupActivityResultLaunchers() {
        exportFileActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        baseModel.exportSelectedNoteToFile(uri, binding.root)
                    }
                }
            }
        exportNotesActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        baseModel.exportSelectedNotesToFolder(uri, binding.root)
                    }
                }
            }
        identityVerifyActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    pendingIdentityVerifiedAction?.invoke()
                }
                pendingIdentityVerifiedAction = null
            }
    }

    fun verifyIdentityThen(action: () -> Unit) {
        showBiometricOrPinPrompt(
            isForDecrypt = false,
            cipherIv = null,
            activityResultLauncher = identityVerifyActivityResultLauncher,
            titleResId = R.string.unlock,
            descriptionResId = R.string.note_locked,
            onSuccess = { _ -> action() },
        ) { errorCode ->
            if (
                errorCode ==
                    android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                    errorCode ==
                        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
            ) {
                action()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Labels and Settings are now reached from the navigation drawer, so the top bar
        // only hosts the search pill and the account avatar (wired in setupToolbar).
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            ACTION_LABELS -> {
                navController.navigate(R.id.Labels)
                true
            }
            ACTION_SETTINGS -> {
                navController.navigate(R.id.Settings)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FRAGMENT_TO_OPEN = "memox.intent.extra.FRAGMENT_TO_OPEN"
        const val EXTRA_SKIP_START_VIEW_ON_BACK = "memox.intent.extra.SKIP_START_VIEW_ON_BACK"
        private const val ACTION_LABELS = 1001
        private const val ACTION_SETTINGS = 1002
        private const val HOME_FILTER_DATE_PICKER_TAG = "home-filter-date"

        private val HOME_BAR_DESTINATIONS =
            setOf(R.id.Notes, R.id.DisplayLabel, R.id.DisplayFolder)
        /**
         * MaterialDatePicker 返回所选日期的 UTC 零点。先在 UTC 读出真正的年月日，
         * 再在当地时区构造当天零点；直接在当地时区解析 UTC 零点会让负时区读成前一天。
         * 与 [exclusiveEndOfDay] 的排他上界配套，覆盖结束日全天。
         */
        internal fun localStartOfDay(utcMidnight: Long): Long =
            localStartOfDay(utcMidnight, java.util.TimeZone.getDefault())

        /** 纯逻辑重载，便于 JVM 单测按固定时区验证边界。 */
        internal fun localStartOfDay(utcMidnight: Long, timeZone: java.util.TimeZone): Long {
            val utc =
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = utcMidnight
                }
            return java.util.Calendar.getInstance(timeZone)
                .apply {
                    clear()
                    set(
                        utc.get(java.util.Calendar.YEAR),
                        utc.get(java.util.Calendar.MONTH),
                        utc.get(java.util.Calendar.DAY_OF_MONTH),
                        0,
                        0,
                        0,
                    )
                }
                .timeInMillis
        }

        /** 排他上界：结束日“下一天”的本地零点，因此结束日 23:59:59 仍落在范围内。 */
        internal fun exclusiveEndOfDay(utcMidnight: Long, timeZone: java.util.TimeZone): Long =
            java.util.Calendar.getInstance(timeZone)
                .apply {
                    timeInMillis = localStartOfDay(utcMidnight, timeZone)
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                .timeInMillis

        val ACTIVITES_WITHOUT_TOOLBAR_ICONS =
            setOf(
                R.id.Settings,
                R.id.SettingsAppearance,
                R.id.SettingsBackup,
                R.id.SettingsData,
                R.id.SettingsAbout,
                R.id.Labels,
                R.id.Search,
            )
    }
}
