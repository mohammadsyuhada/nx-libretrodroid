package com.swordfish.libretrodroid.spike

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.libretrodroid.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroAchievements
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.VirtualFile
import com.swordfish.libretrodroid.VirtualGamePadConfigs
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * THROWAWAY spike game screen. Boots a single core against a single ROM (picked
 * in [SpikeLauncherActivity]) and shows a live diagnostics overlay: FPS, a rolling
 * 5s average, the min frame interval, the load mode, elapsed time and last error.
 *
 * The ROM is delivered either as a virtual file (fd from SAF) or copied to cache
 * and loaded by path. State/SRAM buttons let us verify save/load on real hardware.
 */
class SpikeGameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CORE = "nx.core"
        const val EXTRA_URI = "nx.uri"
        const val EXTRA_NAME = "nx.name"
        const val EXTRA_MODE = "nx.mode"
        const val EXTRA_SIZE = "nx.size"

        const val MODE_VFS = "vfs"
        const val MODE_COPY = "copy"

        private const val LOG_TAG = "NXSPIKE"

        // A frame interval longer than this counts as a "slow" (dropped-ish) frame.
        private const val SLOW_FRAME_MS = 25f
    }

    private lateinit var retroView: GLRetroView
    private lateinit var leftPad: RadialGamePad
    private lateinit var rightPad: RadialGamePad

    private lateinit var overlay: TextView
    private lateinit var loadingText: TextView
    private lateinit var buttonBar: View

    private var coreName: String = ""
    private var gameName: String = ""
    private var loadMode: String = MODE_VFS

    // Set depending on load mode, consumed by startEmulator().
    private var pendingGameFilePath: String? = null
    private var pendingVirtualFile: VirtualFile? = null

    // FPS / frame accounting. All read & written on the main thread only.
    private var frameCount = 0
    private var lastFrameNanos = 0L
    // Per-1s-window stutter metrics, accumulated live and snapshotted by the timer.
    private var windowMaxIntervalMs = 0f
    private var windowSlowCount = 0
    private var lastMaxInt = 0f
    private var lastSlow = 0
    private val fpsWindow = ArrayDeque<Float>()
    private var lastFps = 0f
    private var lastAvg5 = 0f
    private var lastErrorText: String? = null

    private var startElapsed = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.spike_game)

        overlay = findViewById(R.id.overlayText)
        loadingText = findViewById(R.id.loadingText)
        buttonBar = findViewById(R.id.buttonBar)

        // Tap the overlay to toggle the button bar so the screen stays clear.
        overlay.setOnClickListener {
            buttonBar.visibility =
                if (buttonBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        coreName = intent.getStringExtra(EXTRA_CORE) ?: ""
        gameName = intent.getStringExtra(EXTRA_NAME) ?: "game"
        loadMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VFS
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val expectedSize = intent.getLongExtra(EXTRA_SIZE, -1L)

        startElapsed = SystemClock.elapsedRealtime()

        if (coreName.isEmpty() || uriStr.isNullOrEmpty()) {
            Toast.makeText(this, "Missing core or ROM uri", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uri = Uri.parse(uriStr)

        if (loadMode == MODE_COPY) {
            loadingText.visibility = View.VISIBLE
            loadingText.text = "Copying $gameName ..."
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { copyToCache(uri, gameName, expectedSize) }
                withContext(Dispatchers.Main) {
                    loadingText.visibility = View.GONE
                    result.onSuccess { path ->
                        pendingGameFilePath = path
                        startEmulator()
                    }.onFailure { e ->
                        lastErrorText = "COPY_FAILED: ${e.message}"
                        Toast.makeText(this@SpikeGameActivity, "Copy failed: ${e.message}", Toast.LENGTH_LONG).show()
                        renderOverlay()
                    }
                }
            }
        } else {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("openFileDescriptor returned null")
                pendingVirtualFile = VirtualFile("/rom/$gameName", pfd)
                startEmulator()
            } catch (e: Exception) {
                lastErrorText = "FD_FAILED: ${e.message}"
                Toast.makeText(this, "Open fd failed: ${e.message}", Toast.LENGTH_LONG).show()
                renderOverlay()
            }
        }
    }

    /** Streams the SAF document into cacheDir/roms/<name>, skipping if same size present. */
    private fun copyToCache(uri: Uri, name: String, expectedSize: Long): String {
        val romsDir = File(cacheDir, "roms").apply { mkdirs() }
        val target = File(romsDir, name)

        if (target.exists() && expectedSize > 0 && target.length() == expectedSize) {
            runOnUiThread { loadingText.text = "Reusing cached copy ($name)" }
            return target.absolutePath
        }

        val started = SystemClock.elapsedRealtime()
        var copied = 0L
        var lastUpdate = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 200) {
                        lastUpdate = now
                        val elapsed = (now - started) / 1000f
                        val pct = if (expectedSize > 0) (copied * 100 / expectedSize) else -1
                        val msg = if (pct >= 0) {
                            "Copying $name  $pct%  (${elapsed}s)"
                        } else {
                            "Copying $name  ${copied / 1024 / 1024} MB  (${elapsed}s)"
                        }
                        runOnUiThread { loadingText.text = msg }
                    }
                }
            }
        } ?: throw IllegalStateException("openInputStream returned null")

        val elapsedMs = SystemClock.elapsedRealtime() - started
        Log.i(LOG_TAG, "copy done name=$name bytes=$copied ms=$elapsedMs")
        return target.absolutePath
    }

    private fun startEmulator() {
        val systemDir = getExternalFilesDir("system")!!.apply { mkdirs() }
        val savesDir = getExternalFilesDir("saves")!!.apply { mkdirs() }

        val data = GLRetroViewData(this).apply {
            coreFilePath = coreName
            systemDirectory = systemDir.absolutePath
            savesDirectory = savesDir.absolutePath
            shader = ShaderConfig.Default()
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
            variables = defaultVariablesFor(coreName, systemDir)
            val path = pendingGameFilePath
            val vf = pendingVirtualFile
            when {
                path != null -> gameFilePath = path
                vf != null -> gameVirtualFiles = listOf(vf)
            }
        }

        // Smoke for LibretroAchievements.hash: launch with --ei ra_console <rcheevos console id>.
        runCatching {
            val console = intent.getIntExtra("ra_console", 0)
            val opened = if (data.gameVirtualFiles.isEmpty()) {
                val file = File(data.gameFilePath!!)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else null
            try {
                val files = opened?.let { listOf(VirtualFile("/rom/" + File(data.gameFilePath!!).name, it)) }
                    ?: data.gameVirtualFiles
                Log.i("Spike", "RA hash (console $console): " + LibretroAchievements.hash(console, files))
            } finally {
                opened?.close()
            }
        }.onFailure { Log.w("Spike", "RA hash failed", it) }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        val container = findViewById<FrameLayout>(R.id.gamecontainer)
        container.addView(retroView)
        retroView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        initializeVirtualGamePad()
        setupButtons(savesDir)
        observeFrames()
        observeErrors()
        startFpsTimer()
        renderOverlay()

        // Auto-dump the core's variables once (~5s in) so defaults show up in logcat
        // without pressing the Vars button.
        lifecycleScope.launch {
            delay(5000)
            try {
                dumpVariables()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "auto var dump failed", e)
            }
        }
    }

    /** Per-core default libretro options, keyed by core filename prefix. */
    private fun defaultVariablesFor(core: String, systemDir: File): Array<Variable> = when {
        core.startsWith("flycast_") -> {
            // Prefer a real Dreamcast BIOS at <systemDir>/dc/dc_boot.bin if present,
            // otherwise fall back to flycast's HLE BIOS.
            val bootBios = File(systemDir, "dc/dc_boot.bin")
            val hasRealBios = bootBios.exists()
            val hleBios = if (hasRealBios) "disabled" else "enabled"
            Log.i(
                LOG_TAG,
                "flycast BIOS mode: reicast_hle_bios=$hleBios dc_boot.bin exists=$hasRealBios path=${bootBios.absolutePath}"
            )
            arrayOf(
                Variable("reicast_threaded_rendering", "disabled"),
                Variable("reicast_hle_bios", hleBios)
            )
        }
        else -> emptyArray()
    }

    // ------------------------------------------------------------------ diagnostics

    private fun observeFrames() {
        lifecycleScope.launch {
            retroView.getGLRetroEvents()
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { event ->
                    if (event is GLRetroView.GLRetroEvents.FrameRendered) {
                        frameCount++
                        val now = System.nanoTime()
                        if (lastFrameNanos != 0L) {
                            val delta = (now - lastFrameNanos) / 1_000_000f
                            if (delta > windowMaxIntervalMs) windowMaxIntervalMs = delta
                            if (delta > SLOW_FRAME_MS) windowSlowCount++
                        }
                        lastFrameNanos = now
                    }
                }
        }
    }

    private fun observeErrors() {
        lifecycleScope.launch {
            retroView.getGLRetroErrors()
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { code ->
                    lastErrorText = errorName(code)
                    Log.e(LOG_TAG, "error=$lastErrorText core=$coreName mode=$loadMode")
                    renderOverlay()
                }
        }
    }

    private fun startFpsTimer() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                lastFps = frameCount.toFloat()
                frameCount = 0
                lastMaxInt = windowMaxIntervalMs
                lastSlow = windowSlowCount
                windowMaxIntervalMs = 0f
                windowSlowCount = 0
                fpsWindow.addLast(lastFps)
                while (fpsWindow.size > 5) fpsWindow.removeFirst()
                lastAvg5 = if (fpsWindow.isEmpty()) 0f else fpsWindow.sum() / fpsWindow.size
                renderOverlay()
                Log.i(
                    LOG_TAG,
                    String.format(
                        Locale.US,
                        "fps=%.1f avg5=%.1f maxInt=%s slow=%d core=%s mode=%s",
                        lastFps, lastAvg5, maxIntString(), lastSlow, coreName, loadMode
                    )
                )
            }
        }
    }

    private fun maxIntString(): String =
        if (lastMaxInt <= 0f) "-" else String.format(Locale.US, "%.1f", lastMaxInt)

    private fun renderOverlay() {
        val elapsed = (SystemClock.elapsedRealtime() - startElapsed) / 1000
        overlay.text = buildString {
            append("core: $coreName\n")
            append("mode: $loadMode   rom: $gameName\n")
            append("elapsed: ${elapsed}s\n")
            append(String.format(Locale.US, "fps: %.1f   avg5: %.1f\n", lastFps, lastAvg5))
            append("maxInt: ${maxIntString()} ms   slow: $lastSlow\n")
            append("error: ${lastErrorText ?: "-"}\n")
            append("(tap here to toggle buttons)")
        }
    }

    private fun errorName(code: Int): String = when (code) {
        GLRetroView.ERROR_LOAD_LIBRARY -> "ERROR_LOAD_LIBRARY (core .so failed to load)"
        GLRetroView.ERROR_LOAD_GAME -> "ERROR_LOAD_GAME (core rejected ROM / missing BIOS?)"
        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "ERROR_GL_NOT_COMPATIBLE (GL/GLES mismatch)"
        GLRetroView.ERROR_SERIALIZATION -> "ERROR_SERIALIZATION (save/load state failed)"
        GLRetroView.ERROR_CHEAT -> "ERROR_CHEAT"
        GLRetroView.ERROR_GENERIC -> "ERROR_GENERIC"
        else -> "UNKNOWN($code)"
    }

    // ------------------------------------------------------------------ state buttons

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun stateFile(savesDir: File): File =
        File(savesDir, "${sanitize(coreName)}-${sanitize(gameName)}.state")

    private fun sramFile(savesDir: File): File =
        File(savesDir, "${sanitize(coreName)}-${sanitize(gameName)}.srm")

    private fun setupButtons(savesDir: File) {
        findViewById<Button>(R.id.btnSaveState).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val started = SystemClock.elapsedRealtime()
                val result = runCatching {
                    val bytes = retroView.serializeState()
                    val file = stateFile(savesDir)
                    file.writeBytes(bytes)
                    bytes.size to (SystemClock.elapsedRealtime() - started)
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { (size, ms) ->
                        toast("Saved state: $size bytes in ${ms}ms")
                    }.onFailure { e -> reportError("Save state", e) }
                }
            }
        }

        findViewById<Button>(R.id.btnLoadState).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val started = SystemClock.elapsedRealtime()
                val result = runCatching {
                    val file = stateFile(savesDir)
                    if (!file.exists()) throw IllegalStateException("no state file at ${file.name}")
                    val ok = retroView.unserializeState(file.readBytes())
                    ok to (SystemClock.elapsedRealtime() - started)
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { (ok, ms) ->
                        toast("Load state: ${if (ok) "OK" else "FAILED"} in ${ms}ms")
                    }.onFailure { e -> reportError("Load state", e) }
                }
            }
        }

        findViewById<Button>(R.id.btnSaveSram).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val bytes = retroView.serializeSRAM()
                    val file = sramFile(savesDir)
                    file.writeBytes(bytes)
                    bytes.size
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { size -> toast("Saved SRAM: $size bytes") }
                        .onFailure { e -> reportError("Save SRAM", e) }
                }
            }
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            try {
                retroView.reset()
                toast("Reset")
            } catch (e: Exception) {
                reportError("Reset", e)
            }
        }

        findViewById<Button>(R.id.btnVars).setOnClickListener {
            try {
                val count = dumpVariables()
                toast("Logged $count vars (tag $LOG_TAG)")
            } catch (e: Exception) {
                reportError("Vars", e)
            }
        }
    }

    /** Logs the core's current variables under NXSPIKE and returns the count. */
    private fun dumpVariables(): Int {
        val vars = retroView.getVariables()
        Log.i(LOG_TAG, "vars core=$coreName count=${vars.size}")
        vars.forEach { v ->
            Log.i(LOG_TAG, "var ${v.key}=${v.value}")
        }
        return vars.size
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun reportError(what: String, e: Throwable) {
        lastErrorText = "$what: ${e.message}"
        Log.e(LOG_TAG, "$what failed", e)
        Toast.makeText(this, "$what failed: ${e.message}", Toast.LENGTH_LONG).show()
        renderOverlay()
    }

    // ------------------------------------------------------------------ input plumbing

    private fun initializeVirtualGamePad() {
        leftPad = RadialGamePad(VirtualGamePadConfigs.RETRO_PAD_LEFT, 8f, this)
        rightPad = RadialGamePad(VirtualGamePadConfigs.RETRO_PAD_RIGHT, 8f, this)

        leftPad.gravityX = -1f
        leftPad.gravityY = 1f
        rightPad.gravityX = 1f
        rightPad.gravityY = 1f

        findViewById<FrameLayout>(R.id.leftcontainer).addView(leftPad)
        findViewById<FrameLayout>(R.id.rightcontainer).addView(rightPad)

        lifecycleScope.launch {
            merge(leftPad.events(), rightPad.events())
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { handleGamePadEvent(it) }
        }
    }

    private fun handleGamePadEvent(event: Event) {
        when (event) {
            is Event.Button -> retroView.sendKeyEvent(event.action, event.id)
            is Event.Direction -> retroView.sendMotionEvent(event.id, event.xAxis, event.yAxis)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && ::retroView.isInitialized) {
            sendMotionEvent(event, GLRetroView.MOTION_SOURCE_DPAD, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
            sendMotionEvent(event, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
            sendMotionEvent(event, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        }
        return super.onGenericMotionEvent(event)
    }

    private fun sendMotionEvent(event: MotionEvent, source: Int, xAxis: Int, yAxis: Int) {
        retroView.sendMotionEvent(source, event.getAxisValue(xAxis), event.getAxisValue(yAxis), 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (::retroView.isInitialized) retroView.sendKeyEvent(event.action, keyCode)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (::retroView.isInitialized) retroView.sendKeyEvent(event.action, keyCode)
        return super.onKeyUp(keyCode, event)
    }
}
