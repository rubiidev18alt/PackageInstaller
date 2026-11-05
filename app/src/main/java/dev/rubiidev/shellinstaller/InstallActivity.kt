package dev.rubiidev.shellinstaller

import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.rubiidev.shellinstaller.backend.Backends
import dev.rubiidev.shellinstaller.databinding.ActivityInstallBinding
import dev.rubiidev.shellinstaller.install.ApkInfo
import dev.rubiidev.shellinstaller.install.InstallManager
import dev.rubiidev.shellinstaller.util.formatSize
import dev.rubiidev.shellinstaller.util.versionCodeCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * System-installer-style popup. The activity uses a floating dialog theme and renders the
 * install session as a compact confirmation box (Cancel / Install -> progress -> Done / Open).
 */
class InstallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "extra_uris"
        private const val MAX_DIALOG_WIDTH_DP = 420
    }

    private lateinit var binding: ActivityInstallBinding

    private var detailsExpanded = false
    private var boundInfo: ApkInfo? = null
    private var installedInfo: PackageInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        configureWindow()
        setupButtons()
        observeState()
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun configureWindow() {
        val metrics = resources.displayMetrics
        val maxWidth = (MAX_DIALOG_WIDTH_DP * metrics.density).toInt()
        val width = min((metrics.widthPixels * 0.92f).toInt(), maxWidth)
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.CENTER)
    }

    private fun setupButtons() {
        binding.btnDetails.setOnClickListener {
            detailsExpanded = !detailsExpanded
            render(InstallManager.state.value)
        }
        binding.btnUninstall.setOnClickListener { confirmUninstall() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallManager.state.collect { render(it) }
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        boundInfo = null
        installedInfo = null
        detailsExpanded = false
        InstallManager.prepare(this, uris)
    }

    @Suppress("DEPRECATION")
    private fun collectUris(intent: Intent): List<Uri> {
        val list = ArrayList<Uri>()
        intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)?.let { list.addAll(it) }
        intent.data?.let { list.add(it) }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
        }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
        }
        return list.distinct()
    }

    private fun render(state: InstallManager.State) {
        val info = infoOf(state)
        if (info != null && (info !== boundInfo || state !is InstallManager.State.Installing)) {
            bindAppInfo(info)
        } else if (info == null) {
            boundInfo = null
            installedInfo = null
            binding.tvAppLabel.setText(R.string.app_name)
            binding.tvPackageName.visibility = View.GONE
            binding.tvVersion.visibility = View.GONE
            binding.ivIcon.setImageResource(R.mipmap.ic_launcher)
        }

        val showInfo = info != null
        binding.tvPackageName.visibility = if (showInfo) View.VISIBLE else View.GONE
        binding.tvVersion.visibility = if (showInfo) View.VISIBLE else View.GONE
        binding.tvInstalled.visibility =
            if (showInfo && state is InstallManager.State.Ready) View.VISIBLE else View.GONE
        binding.tvMeta.visibility =
            if (showInfo && (state is InstallManager.State.Ready || state is InstallManager.State.Installing)) View.VISIBLE else View.GONE
        binding.tvBackendLine.visibility = binding.tvMeta.visibility

        when (state) {
            is InstallManager.State.Preparing -> {
                binding.tvQuestion.visibility = View.GONE
                binding.tvError.visibility = View.GONE
                showProgress(indeterminate = true, status = state.message, progress = 0f)
                setButtons(negative = R.string.cancel to { InstallManager.reset(); finish() }, positive = null)
            }
            is InstallManager.State.Ready -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.text = questionFor(info!!)
                binding.tvError.visibility = View.GONE
                hideProgress()
                setButtons(
                    negative = R.string.cancel to { InstallManager.reset(); finish() },
                    positive = R.string.install to { InstallManager.install(this) }
                )
            }
            is InstallManager.State.Installing -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.setText(R.string.installing_app)
                binding.tvError.visibility = View.GONE
                showProgress(indeterminate = false, status = state.status, progress = state.progress)
                setButtons(negative = R.string.cancel to { InstallManager.cancel() }, positive = null)
            }
            is InstallManager.State.Success -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.setText(R.string.install_success_popup)
                binding.tvError.visibility = View.GONE
                hideProgress()
                setButtons(
                    negative = R.string.done to { InstallManager.reset(); finish() },
                    positive = R.string.open_app to { openInstalledApp() }
                )
            }
            is InstallManager.State.Failed -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.setText(R.string.install_failed_popup)
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = state.message
                hideProgress()
                setButtons(
                    negative = R.string.close to { InstallManager.reset(); finish() },
                    positive = if (state.info != null) R.string.retry to { InstallManager.install(this) } else null
                )
            }
            InstallManager.State.Idle -> {
                binding.tvQuestion.visibility = View.GONE
                binding.tvError.visibility = View.GONE
                hideProgress()
                setButtons(negative = null, positive = null)
            }
        }

        renderDetails(state)
        renderUninstall(state, info)
    }

    private fun bindAppInfo(info: ApkInfo) {
        boundInfo = info
        binding.tvAppLabel.text = info.label
        binding.tvPackageName.text = info.packageName ?: "—"
        binding.tvVersion.text = "${info.versionName ?: "?"} (${info.versionCode})"
        if (info.icon != null) {
            binding.ivIcon.setImageDrawable(info.icon)
        } else {
            binding.ivIcon.setImageResource(R.mipmap.ic_launcher)
        }

        installedInfo = info.packageName?.let { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
            } catch (e: Exception) {
                null
            }
        }
        binding.tvInstalled.text = when {
            installedInfo == null -> getString(R.string.not_installed)
            installedInfo!!.versionCodeCompat() < info.versionCode ->
                getString(R.string.update_available, installedInfo!!.versionName ?: "?", info.versionName ?: "?")
            installedInfo!!.versionCodeCompat() > info.versionCode ->
                getString(R.string.downgrade_warning, installedInfo!!.versionName ?: "?", info.versionName ?: "?")
            else -> getString(R.string.same_version, info.versionName ?: "?")
        }

        val splitText = if (info.splitCount > 1) {
            getString(R.string.splits_count, info.splitCount)
        } else {
            getString(R.string.single_apk)
        }
        binding.tvMeta.text = listOf(
            formatSize(info.totalSize),
            splitText,
            getString(R.string.sdk_range, info.minSdk, info.targetSdk)
        ).joinToString(" · ")

        val backend = Backends.readyBackendOrNull(this)
        binding.tvBackendLine.text = getString(
            R.string.backend_line,
            backend?.displayName ?: getString(R.string.no_backend_short)
        )
    }

    private fun questionFor(info: ApkInfo): String {
        val installed = installedInfo
        return when {
            installed == null -> getString(R.string.install_question)
            installed.versionCodeCompat() < info.versionCode -> getString(R.string.update_question)
            installed.versionCodeCompat() > info.versionCode -> getString(R.string.downgrade_question)
            else -> getString(R.string.same_version_question)
        }
    }

    private fun showProgress(indeterminate: Boolean, status: String, progress: Float) {
        binding.progressSection.visibility = View.VISIBLE
        binding.progress.isIndeterminate = indeterminate
        if (!indeterminate) {
            binding.progress.progress = (progress * 1000).toInt()
        }
        binding.tvInstallStatus.text = status
    }

    private fun hideProgress() {
        binding.progressSection.visibility = View.GONE
    }

    private fun setButtons(negative: Pair<Int, () -> Unit>?, positive: Pair<Int, () -> Unit>?) {
        bindButton(binding.btnNegative, negative)
        bindButton(binding.btnPositive, positive)
    }

    private fun bindButton(button: MaterialButton, spec: Pair<Int, () -> Unit>?) {
        if (spec == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.setText(spec.first)
            button.setOnClickListener { spec.second.invoke() }
        }
    }

    private fun renderDetails(state: InstallManager.State) {
        val logText = when (state) {
            is InstallManager.State.Installing -> InstallManager.logSnapshot()
            is InstallManager.State.Success -> state.log
            is InstallManager.State.Failed -> state.log
            else -> ""
        }
        val hasLog = logText.isNotBlank()
        binding.btnDetails.visibility = if (hasLog) View.VISIBLE else View.GONE
        binding.btnDetails.setText(if (detailsExpanded) R.string.hide_details else R.string.details)
        binding.btnDetails.setIconResource(if (detailsExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        binding.detailsSection.visibility = if (hasLog && detailsExpanded) View.VISIBLE else View.GONE
        binding.tvLog.text = logText
    }

    private fun renderUninstall(state: InstallManager.State, info: ApkInfo?) {
        val canUninstall = info?.packageName != null && installedInfo != null &&
            (state is InstallManager.State.Ready || state is InstallManager.State.Failed)
        binding.btnUninstall.visibility = if (canUninstall) View.VISIBLE else View.GONE
    }

    private fun infoOf(state: InstallManager.State): ApkInfo? = when (state) {
        is InstallManager.State.Ready -> state.info
        is InstallManager.State.Installing -> state.info
        is InstallManager.State.Success -> state.info
        is InstallManager.State.Failed -> state.info
        else -> null
    }

    private fun handleBack() {
        when (InstallManager.state.value) {
            is InstallManager.State.Installing -> InstallManager.cancel()
            else -> {
                InstallManager.reset()
                finish()
            }
        }
    }

    private fun openInstalledApp() {
        val packageName = (InstallManager.state.value as? InstallManager.State.Success)?.packageName
            ?: return
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            InstallManager.reset()
            finish()
        } else {
            toast(getString(R.string.not_installed))
        }
    }

    private fun confirmUninstall() {
        val info = infoOf(InstallManager.state.value) ?: return
        val packageName = info.packageName ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.uninstall_confirm_title, info.label))
            .setMessage(R.string.uninstall_confirm_msg)
            .setPositiveButton(R.string.uninstall) { _, _ -> doUninstall(packageName) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doUninstall(packageName: String) {
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val backend = Backends.resolve(this@InstallActivity)
                    val result = backend.exec(listOf("pm", "uninstall", "--user", "current", packageName))
                    if (result.looksSuccessful) getString(R.string.uninstalled) else result.output.trim()
                } catch (t: Throwable) {
                    t.message ?: "Uninstall failed"
                }
            }
            toast(message)
            boundInfo = null
            installedInfo = null
            render(InstallManager.state.value)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
