/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.activities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.res.AssetManager
import android.os.Bundle
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.work.*
import com.machiav3lli.backup.BuildConfig
import com.machiav3lli.backup.PREFS_SKIPPEDENCRYPTION
import com.machiav3lli.backup.R
import com.machiav3lli.backup.actions.BaseAppAction
import com.machiav3lli.backup.classAddress
import com.machiav3lli.backup.databinding.ActivityMainXBinding
import com.machiav3lli.backup.dbs.AppExtras
import com.machiav3lli.backup.dbs.AppExtrasDatabase
import com.machiav3lli.backup.dbs.BlocklistDatabase
import com.machiav3lli.backup.fragments.ProgressViewController
import com.machiav3lli.backup.fragments.RefreshViewController
import com.machiav3lli.backup.handler.LogsHandler
import com.machiav3lli.backup.handler.ShellHandler
import com.machiav3lli.backup.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.items.*
import com.machiav3lli.backup.tasks.AppActionWork
import com.machiav3lli.backup.utils.*
import com.machiav3lli.backup.viewmodels.MainViewModel
import com.machiav3lli.backup.viewmodels.MainViewModelFactory
import com.topjohnwu.superuser.Shell
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference


class MainActivityX : BaseActivity() {

    companion object {
        val VERSION_FILE = "__version__"
        val ASSETS_SUBDIR = "assets"

        var shellHandlerInstance: ShellHandler? = null
            private set

        lateinit var assetDir : File
            private set

        fun initShellHandler() : Boolean {
            return try {
                shellHandlerInstance = ShellHandler()
                true
            } catch (e: ShellHandler.ShellCommandFailedException) {
                false
            }
        }

        var activityRef : WeakReference<MainActivityX> = WeakReference(null)
        var activity : MainActivityX?
            get() {
                return activityRef.get()
            }
            set(activity) {
                activityRef = WeakReference(activity)
            }

        var appsSuspendedChecked = false

        var statusNotificationId = 0
        var counter : Int = 0
        var maxCount : Int = 0
        var action : String = ""
        var actionRunning = false
        var runningOperations : MutableMap<String, MutableMap<String, String>> = mutableMapOf()
        var cancelAllWork = false

        val use_workInfos = true

        fun setOperation(packageName: String, action: String = "", operation: String = "") {
            if(!use_workInfos) {
                synchronized(runningOperations) {
                    if (action.isEmpty()) {
                        runningOperations = mutableMapOf()
                    } else {
                        if (operation.isEmpty()) {
                            runningOperations[action]?.let { runningAction ->
                                runningAction.remove(packageName)
                                if (runningAction.isEmpty()) {
                                    runningOperations.remove(action)
                                }
                            }
                        } else {
                            if (runningOperations[action] == null)
                                runningOperations[action] = mutableMapOf()
                            runningOperations[action]!![packageName] = operation
                        }
                    }
                }
            }
            showRunningStatus()
        }

        fun showRunningStatus() {
            var running = 0
            var queued = 0
            var shortText = ""
            var bigText = ""

            if(!use_workInfos) {
                synchronized(runningOperations) {
                    runningOperations.forEach { action ->
                        action.value.forEach { operation ->
                            when (operation.value) {
                                "..." -> queued++
                                else -> {
                                    running++
                                    bigText += "${action.key} ${operation.value} ${operation.key}\n"
                                }
                            }
                        }
                    }
                }
            }
            if (statusNotificationId == 0)
                statusNotificationId = System.currentTimeMillis().toInt()
            activity?.let { activity ->
                activity.runOnUiThread {
                    val appContext = activity.applicationContext
                    val workManager = WorkManager.getInstance(appContext)
                    val workInfos = workManager.getWorkInfosByTag(
                        AppActionWork::class.qualifiedName!!
                    )
                    var workCount = 0
                    var workEnqueued = 0
                    var workRunning = 0
                    var workFinished = 0
                    var succeeded = 0
                    var failed = 0
                    var cancelled = 0
                    if(use_workInfos) {
                        workInfos.get().forEach { workInfo ->
                            val progress = workInfo.progress
                            val operation = progress.getString("operation")
                            workCount++
                            when(workInfo.state) {
                                WorkInfo.State.SUCCEEDED -> {
                                    succeeded++
                                    workFinished++
                                }
                                WorkInfo.State.FAILED -> {
                                    failed++
                                    workFinished++
                                }
                                WorkInfo.State.CANCELLED -> {
                                    cancelled++
                                    workFinished++
                                }
                                WorkInfo.State.ENQUEUED -> {
                                    queued++
                                    workEnqueued++
                                }
                                WorkInfo.State.RUNNING -> {
                                    val packageName = progress.getString("packageName")
                                    val backupBoolean = progress.getBoolean("backupBoolean", true)
                                    workRunning++
                                    when (operation) {
                                        "..." -> queued++
                                        else -> {
                                            running++
                                            if(!packageName.isNullOrEmpty() and !operation.isNullOrEmpty())
                                                bigText += "${if (backupBoolean) "B" else "R"} $operation : $packageName\n"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val active = running + queued + succeeded + failed
                    if(maxCount < active)
                        maxCount = active
                    counter = maxCount - running - queued
                    val title = "${counter}/${maxCount} ($active/$workCount) +$succeeded -$failed %$cancelled $queued>$running"
                    val notificationManager = NotificationManagerCompat.from(appContext)
                    if (running + queued == 0) {
                        activity.hideProgress()
                        notificationManager.cancel(statusNotificationId)
                        statusNotificationId = 0
                        action = ""
                        actionRunning = false
                        maxCount = 0
                        counter = 0
                    } else {
                        val notificationChannel = NotificationChannel(
                            classAddress("NotificationHandler"),
                            classAddress("NotificationHandler"),
                            NotificationManager.IMPORTANCE_LOW
                        )
                        notificationManager.createNotificationChannel(notificationChannel)
                        val resultIntent = Intent(appContext, MainActivityX::class.java)
                        resultIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        val resultPendingIntent = PendingIntent.getActivity(
                            appContext,
                            0,
                            resultIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val cancelIntent = PendingIntent.getBroadcast(
                            appContext,
                            0,
                            Intent(
                                appContext,
                                ActionReceiver::class.java
                            ).setAction("ACTION_CANCELWORKQUEUE"),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        val notification =
                            NotificationCompat.Builder(
                                appContext,
                                classAddress("NotificationHandler")
                            )
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setSmallIcon(R.drawable.ic_app)
                                .setContentTitle(title)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                                .setContentText(shortText)
                                .setProgress(maxCount, counter, false)
                                .setAutoCancel(true)
                                .setContentIntent(resultPendingIntent)
                                .addAction(
                                    R.drawable.ic_close,
                                    appContext.getString(R.string.dialogCancel),
                                    cancelIntent
                                )
                                .build()
                        notificationManager.notify(statusNotificationId, notification)
                        activity.updateProgress(counter, maxCount)
                    }
                }
            }
        }

        fun cancelWorkQueue(context: Context) {
            activity?.showToast("cancel work queue")
            //cancelAllWork = true
            val workManager = WorkManager.getInstance(context)
            //Timber.i("WorkManager: ${workManager}")
            AppActionWork::class.qualifiedName?.let {
                workManager.cancelAllWorkByTag(it)
            } //?: workManager.cancelAllWork()
            activity?.refreshView()
            showRunningStatus()
        }

        class ActionReceiver : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when(intent?.action) {
                        "ACTION_CANCELWORKQUEUE" -> cancelWorkQueue(context.applicationContext)
                        "ACTION_CANCELWORKQUEUE_SERVICE" -> cancelWorkQueue(context.applicationContext)
                }
            }
        }

        val actionReceiver = ActionReceiver()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var refreshViewController: RefreshViewController
    private lateinit var progressViewController: ProgressViewController

    lateinit var binding: ActivityMainXBinding
    lateinit var viewModel: MainViewModel


    override fun onCreate(savedInstanceState: Bundle?) {
        val context = this
        activity = this

        appsSuspendedChecked = false

        setCustomTheme()
        super.onCreate(savedInstanceState)

        if(PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("catchUncaughtException", true)) {
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                try {
                    LogsHandler.unhandledException(e)
                    LogsHandler(context).writeToLogFile(
                        "uncaught exception happened:\n\n" +
                                "\n${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}\n" +
                                runAsRoot("logcat --pid=${Process.myPid()}").out.joinToString("\n")
                    )
                    //val message = LogsHandler.message(e)
                    object : Thread() {
                        override fun run() {
                            Looper.prepare()
                            repeat(5) {
                                Toast.makeText(
                                    activity,
                                    "Uncaught Exception\n${e.message}\nrestarting application...",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            Looper.loop()
                        }
                    }.start()
                    Thread.sleep(5000)
                } finally {
                    System.exit(2)
                }
            }
        }

        Shell.getShell()
        binding = ActivityMainXBinding.inflate(layoutInflater)
        binding.lifecycleOwner = this
        val blocklistDao = BlocklistDatabase.getInstance(this).blocklistDao
        val appExtrasDao = AppExtrasDatabase.getInstance(this).appExtrasDao
        prefs = getPrivateSharedPrefs()
        val viewModelFactory = MainViewModelFactory(appExtrasDao, blocklistDao, application)
        viewModel = ViewModelProvider(this, viewModelFactory).get(MainViewModel::class.java)
        if (!isRememberFiltering) {
            this.sortFilterModel = SortFilterModel()
            this.sortOrder = false
        }
        viewModel.blocklist.observe(this, {
            viewModel.refreshList()
        })
        viewModel.refreshNow.observe(this, {
            if (it) refreshView()
        })
        initAssetFiles()
        initShell()
        runOnUiThread { showEncryptionDialog() }
        setContentView(binding.root)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        setupOnClicks()
        setupNavigation()
        registerReceiver(actionReceiver, IntentFilter(/*"ACTION_CANCELWORKQUEUE"*/))
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(actionReceiver)
    }

    override fun onResume() {
        super.onResume()
        if (isNeedRefresh) {
            viewModel.refreshList()
            isNeedRefresh = false
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState, outPersistentState)
    }

    override fun onBackPressed() {
        finishAffinity()
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent != null) {
            val action = intent.action
            when (action) {
                "ACTION_CANCELWORKQUEUE" -> cancelWorkQueue(this)
            }
        }
        super.onNewIntent(intent)
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainer) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setOnItemSelectedListener { item: MenuItem ->
            if (item.itemId == binding.bottomNavigation.selectedItemId) return@setOnItemSelectedListener false
            if (binding.bottomNavigation.selectedItemId.itemIdToOrder() < item.itemId.itemIdToOrder())
                navController.navigateRight(item.itemId)
            else
                navController.navigateLeft(item.itemId)
            true
        }
    }

    private fun setupOnClicks() {
        binding.buttonSettings.setOnClickListener {
            viewModel.appInfoList.value?.let { oabx.cache.put("appInfoList", it) }
            startActivity(
                Intent(applicationContext, PrefsActivity::class.java)
            )
        }
    }

    private fun showEncryptionDialog() {
        val dontShowAgain = isEncryptionEnabled()
        if (dontShowAgain) return
        val dontShowCounter = prefs.getInt(PREFS_SKIPPEDENCRYPTION, 0)
        prefs.edit().putInt(PREFS_SKIPPEDENCRYPTION, dontShowCounter + 1).apply()
        if (dontShowCounter % 10 == 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.enable_encryption_title)
                .setMessage(R.string.enable_encryption_message)
                .setPositiveButton(R.string.dialog_approve) { _: DialogInterface?, _: Int ->
                    startActivity(
                        Intent(applicationContext, PrefsActivity::class.java).putExtra(
                            ".toEncryption",
                            true
                        )
                    )
                }
                .show()
        }
    }

    fun initAssetFiles() {

        // copy scripts to file storage
        activity?.let { context ->
            assetDir = File(context.filesDir, ASSETS_SUBDIR)
            assetDir.mkdirs()
            // don't copy if the files exist and are from the current app version
            val appVersion = BuildConfig.VERSION_NAME
            val version = try {
                File(assetDir, VERSION_FILE).readText()
            } catch (e: Throwable) {
                ""
            }
            if (version != appVersion) {
                try {
                    // cleans assetDir and copiers asset files
                    context.assets.copyRecursively("files", assetDir)
                    // additional generated files
                    File(assetDir, ShellHandler.EXCLUDE_FILE)
                        .writeText(
                            (BaseAppAction.DATA_EXCLUDED_DIRS + BaseAppAction.DATA_EXCLUDED_FILES)
                                .map { it + "\n" }.joinToString("")
                        )
                    File(assetDir, ShellHandler.EXCLUDE_CACHE_FILE)
                        .writeText(
                            BaseAppAction.DATA_EXCLUDED_CACHE_DIRS
                                .map { it + "\n" }.joinToString("")
                        )
                    // validate with version file if completed
                    File(assetDir, VERSION_FILE).writeText(appVersion)
                } catch (e: Throwable) {
                    Timber.w("cannot copy scripts to ${assetDir}")
                }
            }
        }
    }

    private fun initShell() {
        // Initialize the ShellHandler for further root checks
        if (!initShellHandler()) {
            showWarning(
                MainActivityX::class.java.simpleName,
                getString(R.string.shell_initproblem)
            ) { _: DialogInterface?, _: Int -> finishAffinity() }
        }
    }

    fun updatePackage(packageName: String) {
        StorageFile.invalidateCache()
        viewModel.updatePackage(packageName)
    }

    fun updateAppExtras(appExtras: AppExtras) {
        viewModel.updateExtras(appExtras)
    }

    fun setRefreshViewController(refreshViewController: RefreshViewController) {
        this.refreshViewController = refreshViewController
    }

    fun refreshView() {
        if (::refreshViewController.isInitialized) refreshViewController.refreshView()
    }

    fun setProgressViewController(progressViewController: ProgressViewController) {
        this.progressViewController = progressViewController
    }

    fun updateProgress(progress: Int, max: Int) {
        if (::progressViewController.isInitialized)
            this.progressViewController.updateProgress(progress, max)
    }

    fun hideProgress() {
        if (::progressViewController.isInitialized)
            this.progressViewController.hideProgress()
    }

    fun showSnackBar(message: String) {
        binding.snackbarText.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    fun dismissSnackBar() {
        binding.snackbarText.visibility = View.GONE
    }

    fun whileShowingSnackBar(message: String, todo: () -> Unit) {
        activity?.runOnUiThread {
            activity?.showSnackBar(message)
        }
        todo()
        activity?.runOnUiThread {
            activity?.dismissSnackBar()
        }
    }
}


fun AssetManager.copyRecursively(assetPath: String, targetFile: File) {
    list(assetPath)?.let { list ->
        if (list.isEmpty()) { // assetPath is file
            open(assetPath).use { input ->
                FileOutputStream(targetFile.absolutePath).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

        } else { // assetPath is folder
            targetFile.deleteRecursively()
            targetFile.mkdir()

            list.forEach {
                copyRecursively("$assetPath/$it", File(targetFile, it))
            }
        }
    }
}
