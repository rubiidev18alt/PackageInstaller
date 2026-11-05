package dev.rubiidev.shellinstaller

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import dev.rubiidev.shellinstaller.backend.AdbBackend
import dev.rubiidev.shellinstaller.backend.Backends
import dev.rubiidev.shellinstaller.backend.RootBackend
import dev.rubiidev.shellinstaller.backend.ShizukuBackend
import dev.rubiidev.shellinstaller.backend.ShizukuStatus
import dev.rubiidev.shellinstaller.databinding.ActivityMainBinding
import dev.rubiidev.shellinstaller.install.Prefs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var adbSetupExpanded = false

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) openInstall(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyInsets()
        setupOptions()
        setupButtons()
        observeBackends()
        renderActive()

        binding.tvVersion.text = "v" + BuildConfig.VERSION_NAME
        AdbBackend.savedPort()?.let { binding.etConnectPort.setText(it.toString()) }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun setupOptions() {
        val options = Prefs.options(this)
        binding.switchDowngrade.isChecked = options.allowDowngrade
        binding.switchGrant.isChecked = options.grantAll
        binding.switchBypass.isChecked = options.bypassLowTargetSdk
        binding.switchDowngrade.setOnCheckedChangeListener { _, v -> Prefs.setDowngrade(this, v) }
        binding.switchGrant.setOnCheckedChangeListener { _, v -> Prefs.setGrant(this, v) }
        binding.switchBypass.setOnCheckedChangeListener { _, v -> Prefs.setBypass(this, v) }

        val labels = listOf(
            getString(R.string.backend_auto),
            getString(R.string.shizuku_title),
            getString(R.string.adb_title),
            getString(R.string.root_title)
        )
        binding.dropdownBackend.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        binding.dropdownBackend.setText(labels[Prefs.backendPreference(this).ordinal], false)
        binding.dropdownBackend.setOnItemClickListener { _, _, position, _ ->
            Prefs.setBackendPreference(this, Prefs.BackendPreference.entries[position])
            renderActive()
        }
    }

    private fun setupButtons() {
        binding.btnChooseFile.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }

        binding.btnShizukuOpen.setOnClickListener { ShizukuBackend.openManager(this) }
        binding.btnShizukuGrant.setOnClickListener { ShizukuBackend.requestPermission() }

        binding.btnRootCheck.setOnClickListener {
            lifecycleScope.launch {
                val ok = RootBackend.check()
                toast(getString(if (ok) R.string.root_granted else R.string.root_denied))
            }
        }

        binding.btnAdbToggle.setOnClickListener { toggleAdbSetup() }
        binding.btnPair.setOnClickListener { doPair() }
        binding.btnConnect.setOnClickListener { doConnect() }
        binding.btnAdbScan.setOnClickListener { doScan() }
        binding.btnAdbDisconnect.setOnClickListener { AdbBackend.disconnect() }
        binding.btnDevOptions.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        binding.btnGithub.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rubiidev18alt/PackageInstaller"))
            )
        }
    }

    private fun toggleAdbSetup() {
        adbSetupExpanded = !adbSetupExpanded
        binding.adbSetupSection.visibility = if (adbSetupExpanded) View.VISIBLE else View.GONE
        binding.btnAdbToggle.setText(if (adbSetupExpanded) R.string.adb_hide_setup else R.string.adb_setup)
        binding.btnAdbToggle.setIconResource(
            if (adbSetupExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }

    private fun observeBackends() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ShizukuBackend.status.collect { renderShizuku(it); renderActive() } }
                launch { AdbBackend.status.collect { renderAdb(it); renderActive() } }
                launch { RootBackend.status.collect { renderRoot(it); renderActive() } }
            }
        }
    }

    /** Summary card: which backend will actually be used right now. */
    private fun renderActive() {
        val backend = Backends.readyBackendOrNull(this)
        if (backend != null) {
            binding.ivActiveIcon.setImageResource(R.drawable.ic_check)
            binding.ivActiveIcon.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.ivActiveIcon, com.google.android.material.R.attr.colorPrimary)
            )
            binding.tvActiveTitle.setText(R.string.active_ready_title)
            binding.tvActiveSubtitle.text = getString(R.string.active_ready_subtitle, backend.displayName)
        } else {
            binding.ivActiveIcon.setImageResource(R.drawable.ic_alert)
            binding.ivActiveIcon.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.ivActiveIcon, com.google.android.material.R.attr.colorError)
            )
            binding.tvActiveTitle.setText(R.string.active_none_title)
            binding.tvActiveSubtitle.setText(R.string.active_none_subtitle)
        }
    }

    private fun setPill(view: TextView, text: String, ready: Boolean) {
        view.text = text
        val bgAttr = if (ready) {
            com.google.android.material.R.attr.colorPrimaryContainer
        } else {
            com.google.android.material.R.attr.colorSurfaceVariant
        }
        val fgAttr = if (ready) {
            com.google.android.material.R.attr.colorOnPrimaryContainer
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        view.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(view, bgAttr))
        view.setTextColor(MaterialColors.getColor(view, fgAttr))
    }

    private fun renderShizuku(status: ShizukuStatus) {
        val text = when (status) {
            ShizukuStatus.NotInstalled -> getString(R.string.shizuku_not_installed)
            ShizukuStatus.NotRunning -> getString(R.string.shizuku_not_running)
            ShizukuStatus.NoPermission -> getString(R.string.shizuku_no_permission)
            is ShizukuStatus.Ready ->
                getString(if (status.uid == 0) R.string.shizuku_ready_root else R.string.shizuku_ready_shell)
        }
        setPill(binding.tvShizukuStatus, text, status is ShizukuStatus.Ready)
        binding.btnShizukuGrant.visibility =
            if (status is ShizukuStatus.NoPermission) View.VISIBLE else View.GONE
        binding.btnShizukuOpen.text =
            getString(if (status is ShizukuStatus.NotInstalled) R.string.get_shizuku else R.string.open_shizuku)
    }

    private fun renderAdb(status: AdbBackend.Status) {
        val text = when (status) {
            AdbBackend.Status.NotPaired -> getString(R.string.adb_not_paired)
            is AdbBackend.Status.Disconnected -> getString(R.string.adb_paired)
            is AdbBackend.Status.Connected -> getString(R.string.adb_connected, status.port)
        }
        setPill(binding.tvAdbStatus, text, status is AdbBackend.Status.Connected)
        binding.btnAdbDisconnect.visibility =
            if (status is AdbBackend.Status.Connected) View.VISIBLE else View.GONE
    }

    private fun renderRoot(status: RootBackend.Status) {
        val text = when (status) {
            RootBackend.Status.Unknown -> getString(R.string.root_unknown)
            RootBackend.Status.Available -> getString(R.string.root_available)
            RootBackend.Status.Unavailable -> getString(R.string.root_unavailable)
        }
        setPill(binding.tvRootStatus, text, status is RootBackend.Status.Available)
    }

    private fun doPair() {
        val port = binding.etPairPort.text?.toString()?.trim()?.toIntOrNull()
        val code = binding.etPairCode.text?.toString()?.trim().orEmpty()
        if (port == null || port <= 0 || code.isEmpty()) {
            toast(getString(R.string.err_pair_fields))
            return
        }
        setAdbBusy(true)
        lifecycleScope.launch {
            try {
                val ok = AdbBackend.pair(port, code)
                toast(getString(if (ok) R.string.pairing_success else R.string.pairing_failed))
            } catch (t: Throwable) {
                toast(t.message ?: getString(R.string.pairing_failed))
            } finally {
                setAdbBusy(false)
            }
        }
    }

    private fun doConnect() {
        val port = binding.etConnectPort.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port <= 0) {
            toast(getString(R.string.err_connect_port))
            return
        }
        setAdbBusy(true)
        lifecycleScope.launch {
            try {
                AdbBackend.connect(port)
                toast(getString(R.string.connect_success))
            } catch (t: Throwable) {
                toast(t.message ?: getString(R.string.err_connect_port))
            } finally {
                setAdbBusy(false)
            }
        }
    }

    private fun doScan() {
        setAdbBusy(true)
        toast(getString(R.string.scanning))
        lifecycleScope.launch {
            try {
                val pairingPort = AdbBackend.discoverPort(pairing = true)
                if (pairingPort != null) binding.etPairPort.setText(pairingPort.toString())
                val connectPort = AdbBackend.discoverPort(pairing = false)
                if (connectPort != null) binding.etConnectPort.setText(connectPort.toString())
                toast(getString(R.string.scan_done))
            } finally {
                setAdbBusy(false)
            }
        }
    }

    private fun setAdbBusy(busy: Boolean) {
        binding.adbProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnPair.isEnabled = !busy
        binding.btnConnect.isEnabled = !busy
        binding.btnAdbScan.isEnabled = !busy
    }

    private fun openInstall(uris: List<Uri>) {
        val intent = Intent(this, InstallActivity::class.java)
        intent.putParcelableArrayListExtra(InstallActivity.EXTRA_URIS, ArrayList(uris))
        startActivity(intent)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
