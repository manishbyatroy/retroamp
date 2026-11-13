package org.yolo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private companion object {
        const val REQ_FILES = 1
        const val REQ_FILES_REPLACE = 2
        const val REQ_FOLDER = 3
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "3gp", "amr", "mid")

        val PRESETS = linkedMapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Rock" to floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
            "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 4f, 2f, 0f, -1f, -1f, -1f),
            "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
            "Classical" to floatArrayOf(4f, 3f, 2f, 1f, 0f, 0f, -1f, 2f, 3f, 4f),
            "Dance" to floatArrayOf(6f, 5f, 2f, 0f, 0f, -2f, 0f, 2f, 4f, 4f),
            "Vocal" to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
            "Bass boost" to floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
            "Treble boost" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        )
    }

    private lateinit var view: AmpView
    private var svc: PlayerService? = null
    private var bound = false
    private var pending: Pair<List<Track>, Boolean>? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as PlayerService.LocalBinder).service
            svc = s
            view.svc = s
            s.onChange = { view.invalidate() }
            pending?.let { s.add(it.first, it.second) }
            pending = null
            view.invalidate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            view.svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = AmpView(this)
        setContentView(view)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onStart() {
        super.onStart()
        val i = Intent(this, PlayerService::class.java)
        startService(i)
        bound = bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        svc?.onChange = null
        if (bound) {
            unbindService(conn)
            bound = false
        }
        svc = null
        view.svc = null
    }

    // ------------------------------------------------------------------ pickers

    fun pickFiles(replace: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        launch(i, if (replace) REQ_FILES_REPLACE else REQ_FILES)
    }

    fun pickFolder() = launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)

    private fun launch(i: Intent, req: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (_: Exception) {
            Toast.makeText(this, "No file picker available on this device", Toast.LENGTH_LONG).show()
        }
    }

    fun showPresets() {
        val names = PRESETS.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Equalizer presets")
            .setItems(names) { _, which ->
                val s = svc ?: return@setItems
                val g = PRESETS[names[which]]!!
                for (b in g.indices) s.engine.eq.setGain(b, g[b])
                s.save()
                view.invalidate()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val replace = requestCode == REQ_FILES_REPLACE
        when (requestCode) {
            REQ_FILES, REQ_FILES_REPLACE -> {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                } else data.data?.let { uris.add(it) }
                for (u in uris) persist(u)
                loadTracks(replace) { uris }
            }
            REQ_FOLDER -> {
                val tree = data.data ?: return
                persist(tree)
                loadTracks(false) { listFolder(tree) }
            }
        }
    }

    private fun persist(u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Still readable for this session; it just won't survive a reboot.
        }
    }

    private fun loadTracks(replace: Boolean, source: () -> List<Uri>) {
        Toast.makeText(this, "Reading files…", Toast.LENGTH_SHORT).show()
        thread(name = "retroamp-scan") {
            val tracks = try {
                source().map { describe(it) }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (tracks.isEmpty()) {
                    Toast.makeText(this, "No audio files found", Toast.LENGTH_SHORT).show()
                } else {
                    val s = svc
                    if (s != null) s.add(tracks, replace) else pending = Pair(tracks, replace)
                }
            }
        }
    }

    /** Audio files under a picked folder, depth-first, sorted by name within each folder. */
    private fun listFolder(tree: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val files = ArrayList<Pair<String, String>>()
            val dirs = ArrayList<Pair<String, String>>()
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs.add(name to id)
                    else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT) {
                        files.add(name to id)
                    }
                }
            }
            files.sortBy { it.first.lowercase() }
            for (f in files) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, f.second))
            if (depth < 6) {
                dirs.sortBy { it.first.lowercase() }
                for (d in dirs) walk(d.second, depth + 1)
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    private fun describe(uri: Uri): Track {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {}
        var title = (name ?: uri.lastPathSegment ?: "Unknown").substringBeforeLast('.')
        var duration = 0L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(this, uri)
            val t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (!t.isNullOrBlank()) title = if (!a.isNullOrBlank()) "$a - $t" else t
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        return Track(uri, title, duration)
    }
}
