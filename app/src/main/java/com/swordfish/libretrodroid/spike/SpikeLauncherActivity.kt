package com.swordfish.libretrodroid.spike

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.android.libretrodroid.R
import java.io.File
import java.util.Locale

/**
 * THROWAWAY spike launcher. Lets you:
 *  - pick a libretro core from the app's native library dir,
 *  - pick a ROM folder through SAF (persisted), browse it (with drill-down),
 *  - choose a load mode (VFS fd vs copy-to-cache),
 *  - tap a ROM file to launch [SpikeGameActivity].
 *
 * Kept intentionally plain: everything is built with android.widget views added
 * programmatically into a ScrollView (no ListView / RecyclerView).
 */
class SpikeLauncherActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "nx_spike_prefs"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_CORE = "selected_core"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var coreListContainer: LinearLayout
    private lateinit var fileListContainer: LinearLayout
    private lateinit var folderPathView: TextView
    private lateinit var statusView: TextView
    private lateinit var loadModeGroup: RadioGroup

    private var treeUri: Uri? = null

    /** Stack of document ids, first entry = tree root, last = folder currently shown. */
    private val docIdStack = ArrayList<String>()

    private var selectedCore: String? = null

    // Registered as a field so it is created before the activity is STARTED (required by the API).
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                onFolderPicked(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.spike_launcher)

        coreListContainer = findViewById(R.id.coreList)
        fileListContainer = findViewById(R.id.fileList)
        folderPathView = findViewById(R.id.folderPath)
        statusView = findViewById(R.id.statusLine)
        loadModeGroup = findViewById(R.id.loadModeGroup)

        // Ensure the system dir exists and tell the user where to adb-push BIOS files.
        getExternalFilesDir("system")?.mkdirs()

        findViewById<View>(R.id.btnPickFolder).setOnClickListener {
            try {
                pickFolder.launch(null)
            } catch (e: Exception) {
                setStatus("Cannot open folder picker: ${e.message}")
            }
        }

        selectedCore = prefs.getString(KEY_CORE, null)

        renderCores()
        restorePersistedTree()
        updateStatus()
    }

    // ------------------------------------------------------------------ cores

    private fun listCores(): List<String> {
        val dir = File(applicationInfo.nativeLibraryDir)
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            ?.map { it.name }
            ?.filterNot { it == "liblibretrodroid.so" }
            ?.sorted()
            ?: emptyList()
    }

    private fun renderCores() {
        coreListContainer.removeAllViews()
        val cores = listCores()
        if (cores.isEmpty()) {
            coreListContainer.addView(makeLabel("No .so cores found in ${applicationInfo.nativeLibraryDir}"))
            return
        }
        // Drop a previously selected core that is no longer present.
        if (selectedCore != null && selectedCore !in cores) {
            selectedCore = null
        }
        cores.forEach { name ->
            val selected = name == selectedCore
            coreListContainer.addView(
                makeRow(text = name, highlighted = selected) {
                    selectedCore = name
                    prefs.edit().putString(KEY_CORE, name).apply()
                    renderCores()
                    updateStatus()
                }
            )
        }
    }

    // ------------------------------------------------------------------ folder / SAF

    private fun restorePersistedTree() {
        val saved = prefs.getString(KEY_TREE_URI, null) ?: return
        val uri = Uri.parse(saved)
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) {
            treeUri = uri
            resetToRoot()
            renderFiles()
        } else {
            prefs.edit().remove(KEY_TREE_URI).apply()
        }
    }

    private fun onFolderPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            setStatus("Could not persist permission: ${e.message}")
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        treeUri = uri
        resetToRoot()
        renderFiles()
        updateStatus()
    }

    private fun resetToRoot() {
        val uri = treeUri ?: return
        docIdStack.clear()
        docIdStack.add(DocumentsContract.getTreeDocumentId(uri))
    }

    private data class Entry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val isDir: Boolean
    )

    private fun renderFiles() {
        fileListContainer.removeAllViews()
        val uri = treeUri
        if (uri == null || docIdStack.isEmpty()) {
            fileListContainer.addView(makeLabel("Pick a ROM folder to browse."))
            folderPathView.text = "Folder: (none)"
            return
        }

        folderPathView.text = "Folder: ${uri.lastPathSegment}  (depth ${docIdStack.size - 1})"

        // "Up" entry when not at the tree root.
        if (docIdStack.size > 1) {
            fileListContainer.addView(
                makeRow(text = "[..]  Up", highlighted = false) {
                    docIdStack.removeAt(docIdStack.size - 1)
                    renderFiles()
                }
            )
        }

        val parentDocId = docIdStack.last()
        val entries = ArrayList<Entry>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentDocId)
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: "(unnamed)"
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    entries.add(Entry(id, name, mime, size, isDir))
                }
            }
        } catch (e: Exception) {
            fileListContainer.addView(makeLabel("Failed to read folder: ${e.message}"))
            return
        }

        if (entries.isEmpty()) {
            fileListContainer.addView(makeLabel("(empty folder)"))
            return
        }

        // Directories first, then files, both alphabetical.
        entries.sortWith(compareBy({ !it.isDir }, { it.name.lowercase(Locale.US) }))

        for (entry in entries) {
            if (entry.isDir) {
                fileListContainer.addView(
                    makeRow(text = "[DIR]  ${entry.name}", highlighted = false) {
                        docIdStack.add(entry.docId)
                        renderFiles()
                    }
                )
            } else {
                val label = "${entry.name}   (${humanSize(entry.size)})"
                fileListContainer.addView(
                    makeRow(text = label, highlighted = false) {
                        launchGame(entry)
                    }
                )
            }
        }
    }

    private fun launchGame(entry: Entry) {
        val core = selectedCore
        val uri = treeUri
        if (core == null) {
            setStatus("Select a core first.")
            return
        }
        if (uri == null) {
            setStatus("Pick a folder first.")
            return
        }
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, entry.docId)
        val mode = if (loadModeGroup.checkedRadioButtonId == R.id.modeCopy) {
            SpikeGameActivity.MODE_COPY
        } else {
            SpikeGameActivity.MODE_VFS
        }
        val intent = Intent(this, SpikeGameActivity::class.java).apply {
            putExtra(SpikeGameActivity.EXTRA_CORE, core)
            putExtra(SpikeGameActivity.EXTRA_URI, fileUri.toString())
            putExtra(SpikeGameActivity.EXTRA_NAME, entry.name)
            putExtra(SpikeGameActivity.EXTRA_MODE, mode)
            putExtra(SpikeGameActivity.EXTRA_SIZE, entry.size)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            setStatus("Failed to start game: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ status

    private fun updateStatus() {
        val core = selectedCore ?: "(none)"
        val system = getExternalFilesDir("system")?.absolutePath ?: "(unavailable)"
        val folder = treeUri?.lastPathSegment ?: "(none)"
        setStatus(
            "Core: $core\n" +
                "Folder: $folder\n" +
                "systemDirectory (adb push BIOS here):\n$system"
        )
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    // ------------------------------------------------------------------ view helpers

    private fun makeRow(text: String, highlighted: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.typeface = Typeface.MONOSPACE
        val pad = dp(10)
        tv.setPadding(pad, dp(8), pad, dp(8))
        tv.setBackgroundColor(if (highlighted) Color.parseColor("#3F51B5") else Color.parseColor("#222222"))
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(2), 0, dp(2))
        tv.layoutParams = lp
        return tv
    }

    private fun makeLabel(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.LTGRAY)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setPadding(dp(4), dp(6), dp(4), dp(6))
        return tv
    }

    private fun humanSize(size: Long): String = when {
        size < 0 -> "?"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", size / 1024.0 / 1024.0 / 1024.0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
