package dev.rubiidev.shellinstaller.backend

import android.content.Context
import android.os.Build
import dev.rubiidev.shellinstaller.util.shellQuote
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device ADB over Wireless debugging (Android 11+).
 *
 * Pairing uses the ADB TLS + SPAKE2 handshake (vendored libadb). Once paired, the RSA host key is
 * reused for normal ADB auth. APK bytes are transferred with the binary-safe `sync:` service
 * (push to /data/local/tmp) and installed with `pm install-write <path>`, exactly like `adb install`.
 */
object AdbBackend : InstallBackend {
    override val id: String = "adb"
    override val displayName: String = "Wireless ADB"

    private const val PREFS = "adb_backend"
    private const val KEY_PAIRED = "paired"
    private const val KEY_PORT = "connect_port"
    private const val HOST = "127.0.0.1"

    sealed interface Status {
        data object NotPaired : Status
        data class Disconnected(val port: Int?) : Status
        data class Connected(val port: Int) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.NotPaired)
    val status: StateFlow<Status> = _status

    private lateinit var appContext: Context
    private var manager: Manager? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _status.value = disconnectedStatus()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isPaired: Boolean get() = prefs().getBoolean(KEY_PAIRED, false)

    fun savedPort(): Int? = prefs().getInt(KEY_PORT, 0).takeIf { it > 0 }

    private fun disconnectedStatus(): Status =
        if (isPaired) Status.Disconnected(savedPort()) else Status.NotPaired

    private fun manager(): Manager {
        var m = manager
        if (m == null) {
            m = Manager(appContext)
            manager = m
        }
        return m
    }

    /** Pair with the wireless-debugging pairing port + 6-digit code. */
    suspend fun pair(port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        val m = manager()
        try {
            m.disconnect()
        } catch (_: Exception) {
        }
        val ok = m.pair(HOST, port, code.trim())
        if (ok) {
            prefs().edit().putBoolean(KEY_PAIRED, true).apply()
            _status.value = disconnectedStatus()
        }
        ok
    }

    /** Connect to the wireless-debugging connection port. */
    suspend fun connect(port: Int): Boolean = withContext(Dispatchers.IO) {
        ensureConnected(port)
    }

    fun disconnect() {
        try {
            manager?.disconnect()
        } catch (_: Exception) {
        }
        _status.value = disconnectedStatus()
    }

    private fun ensureConnected(portOverride: Int? = null): Boolean {
        val m = manager()
        if (m.isConnected) {
            _status.value = Status.Connected(savedPort() ?: 0)
            return true
        }
        val port = portOverride ?: savedPort()
            ?: throw IOException("No ADB connection port is configured. Connect on the main screen first.")
        try {
            val ok = m.connect(HOST, port)
            if (ok || m.isConnected) {
                prefs().edit().putInt(KEY_PORT, port).apply()
                _status.value = Status.Connected(port)
                return true
            }
            throw IOException("Could not connect to ADB on port $port. Is Wireless debugging still enabled?")
        } catch (e: AdbPairingRequiredException) {
            prefs().edit().putBoolean(KEY_PAIRED, false).apply()
            _status.value = Status.NotPaired
            throw IOException("ADB pairing was revoked on this device. Please pair again.", e)
        }
    }

    /** Best-effort mDNS discovery of the pairing / connection port. */
    suspend fun discoverPort(pairing: Boolean): Int? = withContext(Dispatchers.IO) {
        val latch = CountDownLatch(1)
        var found: Int? = null
        val mdns = AdbMdns(
            appContext,
            if (pairing) AdbMdns.SERVICE_TYPE_TLS_PAIRING else AdbMdns.SERVICE_TYPE_TLS_CONNECT
        ) { _, port ->
            if (port > 0 && found == null) {
                found = port
                latch.countDown()
            }
        }
        try {
            mdns.start()
            latch.await(8, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            try {
                mdns.stop()
            } catch (_: Exception) {
            }
        }
        found
    }

    override fun exec(
        command: List<String>,
        stdin: File?,
        onProgress: ((Long) -> Unit)?
    ): CommandResult {
        if (!isPaired) throw IOException("ADB is not paired yet. Pair on the main screen first.")
        ensureConnected()
        val m = manager()
        if (stdin != null) {
            val remote = "/data/local/tmp/shellinstaller-" + System.nanoTime() + ".apk"
            pushFile(m, stdin, remote, onProgress)
            try {
                return shellExec(m, command.map { if (it == "-") remote else it })
            } finally {
                try {
                    shellExec(m, listOf("rm", "-f", remote))
                } catch (_: Exception) {
                }
            }
        }
        return shellExec(m, command)
    }

    private fun shellExec(m: Manager, command: List<String>): CommandResult {
        val cmd = command.joinToString(" ") { shellQuote(it) }
        m.openStream("shell:$cmd").use { stream ->
            val bytes = stream.openInputStream().readBytes()
            return CommandResult(null, bytes.toString(Charsets.UTF_8))
        }
    }

    /** ADB sync protocol SEND (a.k.a. `adb push`). */
    private fun pushFile(m: Manager, local: File, remotePath: String, onProgress: ((Long) -> Unit)?) {
        m.openStream("sync:").use { stream ->
            val out = stream.openOutputStream()
            val ins = stream.openInputStream()

            val spec = "$remotePath,33188".toByteArray(Charsets.UTF_8) // 0100644
            out.write("SEND".toByteArray(Charsets.US_ASCII))
            writeLeInt(out, spec.size)
            out.write(spec)

            local.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write("DATA".toByteArray(Charsets.US_ASCII))
                    writeLeInt(out, n)
                    out.write(buffer, 0, n)
                    onProgress?.invoke(n.toLong())
                }
            }
            out.write("DONE".toByteArray(Charsets.US_ASCII))
            writeLeInt(out, (System.currentTimeMillis() / 1000).toInt())
            out.flush()

            when (val id = readFully(ins, 4).toString(Charsets.US_ASCII)) {
                "OKAY" -> Unit
                "FAIL" -> {
                    val len = readLeInt(ins)
                    val msg = readFully(ins, len).toString(Charsets.UTF_8)
                    throw IOException("ADB push failed: $msg")
                }
                else -> throw IOException("Unexpected ADB sync response: $id")
            }
        }
    }

    private fun writeLeInt(out: OutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun readLeInt(ins: InputStream): Int {
        val b = readFully(ins, 4)
        return (b[0].toInt() and 0xff) or
            ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or
            ((b[3].toInt() and 0xff) shl 24)
    }

    private fun readFully(ins: InputStream, n: Int): ByteArray {
        val buffer = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buffer, off, n - off)
            if (r < 0) throw IOException("ADB stream closed unexpectedly")
            off += r
        }
        return buffer
    }

    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val keys = AdbKeys.loadOrCreate(context)

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(20, TimeUnit.SECONDS)
            setHostAddress(HOST)
        }

        override fun getPrivateKey(): PrivateKey = keys.privateKey
        override fun getCertificate(): Certificate = keys.certificate
        override fun getDeviceName(): String = "ShellInstaller"
    }
}

/** Persistent RSA host key + self-signed certificate used for ADB pairing and auth. */
internal object AdbKeys {
    class KeyMaterial(val privateKey: PrivateKey, val certificate: X509Certificate)

    @Synchronized
    fun loadOrCreate(context: Context): KeyMaterial {
        val keyFile = File(context.filesDir, "adbkey.pk8")
        val certFile = File(context.filesDir, "adbkey.x509")
        if (keyFile.exists() && certFile.exists()) {
            try {
                val keyFactory = KeyFactory.getInstance("RSA")
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val certificate = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                return KeyMaterial(privateKey, certificate)
            } catch (_: Throwable) {
                // Regenerate below.
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val certificate = selfSign(pair.getPublic(), pair.getPrivate())
        keyFile.writeBytes(pair.getPrivate().encoded)
        certFile.writeBytes(certificate.encoded)
        return KeyMaterial(pair.getPrivate(), certificate)
    }

    private fun selfSign(publicKey: PublicKey, privateKey: PrivateKey): X509Certificate {
        val name = X500Name("CN=Shell Installer")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 3_650L * 86_400_000L),
            name,
            publicKey
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
