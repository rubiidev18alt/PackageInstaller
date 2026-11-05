package dev.rubiidev.shellinstaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
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

class InstallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "extra_uris"
    }

    private lateinit var binding: ActivityInstallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyInsets()
        setupButtons()
        observeState()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun setupButtons() {
        binding.btnInstall.setOnClickListener { InstallManager.install(this) }
        binding.btnCancel.setOnClickListener { InstallManager.cancel() }
        binding.btnRetry.setOnClickListener { InstallManager.install(this) }
        binding.btnDone.setOnClickListener {
            InstallManager.reset()
            finish()
        }
        binding.btnOpen.setOnClickListener { openInstalledApp() }
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
        if (uris.isNotEmpty()) {
            InstallManager.prepare(this, uris)
        }
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

        binding.preparingSection.visibility =
            if (state is InstallManager.State.Preparing) View.VISIBLE else View.GONE
        if (state is InstallManager.State.Preparing) binding.tvPreparing.text = state.message

        binding.cardApp.visibility = if (info != null) View.VISIBLE else View.GONE
        if (info != null) bindAppInfo(info)

        binding.btnInstall.visibility =
            if (state is InstallManager.State.Ready) View.VISIBLE else View.GONE

        binding.progressSection.visibility =
            if (state is InstallManager.State.Installing) View.VISIBLE else View.GONE
        if (state is InstallManager.State.Installing) {
            binding.progress.progress = (state.progress * 1000).toInt()
            binding.tvInstallStatus.text = state.status
        }

        val isResult = state is InstallManager.State.Success || state is InstallManager.State.Failed
        binding.tvResult.visibility = if (isResult) View.VISIBLE else View.GONE
        binding.resultButtons.visibility = if (isResult) View.VISIBLE else View.GONE
        when (state) {
            is InstallManager.State.Success -> {
                binding.tvResult.text = getString(R.string.install_success)
                binding.tvResult.setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
                )
                binding.btnOpen.visibility = View.VISIBLE
                binding.btnRetry.visibility = View.GONE
            }
            is InstallManager.State.Failed -> {
                binding.tvResult.text = getString(R.string.install_failed) + "\n" + state.message
                binding.tvResult.setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorError)
                )
                binding.btnOpen.visibility = View.GONE
                binding.btnRetry.visibility = if (state.info != null) View.VISIBLE else View.GONE
            }
            else -> {}
        }

        val installed = info?.packageName?.let { isInstalled(it) } == true
        binding.btnUninstall.visibility =
            if (info != null && installed &&
                (state is InstallManager.State.Ready || state is InstallManager.State.Failed)
            ) View.VISIBLE else View.GONE

        val logText = when (state) {
            is InstallManager.State.Installing -> InstallManager.logSnapshot()
            is InstallManager.State.Success -> state.log
            is InstallManager.State.Failed -> state.log
            else -> ""
        }
        val showLog = logText.isNotBlank()
        binding.tvLogHeader.visibility = if (showLog) View.VISIBLE else View.GONE
        binding.tvLog.visibility = if (showLog) View.VISIBLE else View.GONE
        binding.tvLog.text = logText
    }

    private fun bindAppInfo(info: ApkInfo) {
        binding.tvAppLabel.text = info.label
        binding.tvPackageName.text = info.packageName ?: "—"
        binding.tvVersion.text = "${info.versionName ?: "?"} (${info.versionCode})"
        if (info.icon != null) {
            binding.ivIcon.setImageDrawable(info.icon)
        } else {
            binding.ivIcon.setImageResource(R.mipmap.ic_launcher)
        }

        val installedInfo = info.packageName?.let { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
            } catch (e: Exception) {
                null
            }
        }
        binding.tvInstalled.text = when {
            installedInfo == null -> getString(R.string.not_installed)
            installedInfo.versionCodeCompat() < info.versionCode ->
                getString(R.string.update_available, installedInfo.versionName ?: "?", info.versionName ?: "?")
            installedInfo.versionCodeCompat() > info.versionCode ->
                getString(R.string.downgrade_warning, installedInfo.versionName ?: "?", info.versionName ?: "?")
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
            backend?.displayName ?: getString(R.string.err_no_backend)
        )
    }

    private fun infoOf(state: InstallManager.State): ApkInfo? = when (state) {
        is InstallManager.State.Ready -> state.info
        is InstallManager.State.Installing -> state.info
        is InstallManager.State.Success -> state.info
        is InstallManager.State.Failed -> state.info
        else -> null
    }

    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun openInstalledApp() {
        val packageName = (InstallManager.state.value as? InstallManager.State.Success)?.packageName
            ?: return
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            render(InstallManager.state.value)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
