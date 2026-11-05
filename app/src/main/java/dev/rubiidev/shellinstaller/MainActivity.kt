package dev.rubiidev.shellinstaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.rubiidev.shellinstaller.backend.AdbBackend
import dev.rubiidev.shellinstaller.backend.RootBackend
import dev.rubiidev.shellinstaller.backend.ShizukuBackend
import dev.rubiidev.shellinstaller.backend.ShizukuStatus
import dev.rubiidev.shellinstaller.databinding.ActivityMainBinding
import dev.rubiidev.shellinstaller.install.Prefs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBackend.adapter = adapter
        binding.spinnerBackend.setSelection(Prefs.backendPreference(this).ordinal)
        binding.spinnerBackend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Prefs.setBackendPreference(this@MainActivity, Prefs.BackendPreference.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

    private fun observeBackends() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ShizukuBackend.status.collect { renderShizuku(it) } }
                launch { AdbBackend.status.collect { renderAdb(it) } }
                launch { RootBackend.status.collect { renderRoot(it) } }
            }
        }
    }

    private fun renderShizuku(status: ShizukuStatus) {
        binding.tvShizukuStatus.text = when (status) {
            ShizukuStatus.NotInstalled -> getString(R.string.shizuku_not_installed)
            ShizukuStatus.NotRunning -> getString(R.string.shizuku_not_running)
            ShizukuStatus.NoPermission -> getString(R.string.shizuku_no_permission)
            is ShizukuStatus.Ready ->
                getString(if (status.uid == 0) R.string.shizuku_ready_root else R.string.shizuku_ready_shell)
        }
        binding.btnShizukuGrant.visibility =
            if (status is ShizukuStatus.NoPermission) View.VISIBLE else View.GONE
        binding.btnShizukuOpen.text =
            getString(if (status is ShizukuStatus.NotInstalled) R.string.get_shizuku else R.string.open_shizuku)
    }

    private fun renderAdb(status: AdbBackend.Status) {
        binding.tvAdbStatus.text = when (status) {
            AdbBackend.Status.NotPaired -> getString(R.string.adb_not_paired)
            is AdbBackend.Status.Disconnected -> getString(R.string.adb_paired)
            is AdbBackend.Status.Connected -> getString(R.string.adb_connected, status.port)
        }
        binding.btnAdbDisconnect.visibility =
            if (status is AdbBackend.Status.Connected) View.VISIBLE else View.GONE
    }

    private fun renderRoot(status: RootBackend.Status) {
        binding.tvRootStatus.text = when (status) {
            RootBackend.Status.Unknown -> getString(R.string.root_unknown)
            RootBackend.Status.Available -> getString(R.string.root_available)
            RootBackend.Status.Unavailable -> getString(R.string.root_unavailable)
        }
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
