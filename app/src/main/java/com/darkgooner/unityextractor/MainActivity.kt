package com.darkgooner.unityextractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var gamePathView: TextView
    private lateinit var outputPathView: TextView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private lateinit var workers: Spinner
    private lateinit var images: CheckBox
    private lateinit var videos: CheckBox
    private lateinit var skipExisting: CheckBox
    private lateinit var preserveFolders: CheckBox
    private var gamePath: String? = null
    private var outputPath: String? = null
    private var running = false
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gamePathView = findViewById(R.id.gamePath); outputPathView = findViewById(R.id.outputPath)
        statusView = findViewById(R.id.status); statsView = findViewById(R.id.stats); progress = findViewById(R.id.progress)
        start = findViewById(R.id.start); pause = findViewById(R.id.pause); stop = findViewById(R.id.stop)
        workers = findViewById(R.id.workers); images = findViewById(R.id.extractImages); videos = findViewById(R.id.extractVideos)
        skipExisting = findViewById(R.id.skipExisting); preserveFolders = findViewById(R.id.preserveFolders)
        workers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("1  •  safest", "2  •  balanced", "3  •  fast", "4  •  fastest"))
        workers.setSelection(1)
        if (!Environment.isExternalStorageManager()) {
            statusView.text = "Storage permission required — enable All files access."
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.selectGame).setOnClickListener { pickFolder(10) }
        findViewById<Button>(R.id.selectOutput).setOnClickListener { pickFolder(20) }
        start.setOnClickListener { beginExtraction() }
        pause.setOnClickListener {
            paused = !paused
            pause.text = if (paused) "RESUME" else "PAUSE"
            statusView.text = if (paused) "Paused — workers will finish their current file." else "Resuming..."
            val py = Python.getInstance().getModule("extractor")
            py.callAttr(if (paused) "pause" else "resume")
        }
        stop.setOnClickListener {
            File(cacheDir, "unity_extractor_stop").writeText("1")
            statusView.text = "Stopping after current work..."; stop.isEnabled = false; pause.isEnabled = false
        }
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    }

    private fun pickFolder(request: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(i, request)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try { contentResolver.takePersistableUriPermission(uri, data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch (_: Exception) {}
        val path = treeUriToPath(uri) ?: run { Toast.makeText(this, "Use a folder on shared internal storage.", Toast.LENGTH_LONG).show(); return }
        if (requestCode == 10) { gamePath = path; gamePathView.text = path }
        else { outputPath = path; outputPathView.text = path }
    }

    private fun treeUriToPath(uri: Uri): String? {
        val raw = uri.toString(); val marker = raw.substringAfter("/tree/", "").substringBefore("/document/")
        val docId = Uri.decode(marker)
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:")
        return if (rel.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
    }

    private fun beginExtraction() {
        val g = gamePath ?: run { Toast.makeText(this, "Choose the game folder first.", Toast.LENGTH_SHORT).show(); return }
        val o = outputPath ?: run { Toast.makeText(this, "Choose the destination first.", Toast.LENGTH_SHORT).show(); return }
        if (!images.isChecked && !videos.isChecked) { Toast.makeText(this, "Enable Images and/or Videos.", Toast.LENGTH_SHORT).show(); return }
        if (!Environment.isExternalStorageManager()) { Toast.makeText(this, "Enable All files access first.", Toast.LENGTH_LONG).show(); return }
        if (running) return
        running = true; paused = false; start.isEnabled = false; pause.isEnabled = true; stop.isEnabled = true; pause.text = "PAUSE"; progress.progress = 0
        File(cacheDir, "unity_extractor_stop").delete()
        val py = Python.getInstance().getModule("extractor")
        val w = workers.selectedItemPosition + 1
        lifecycleScope.launch(Dispatchers.IO) {
            try { py.callAttr("start_extraction", g, o, cacheDir.absolutePath, w, images.isChecked, videos.isChecked, skipExisting.isChecked, preserveFolders.isChecked) }
            catch (e: Exception) { runOnUiThread { statusView.text = "Error: ${e.message}" } }
            finally { running = false; runOnUiThread { start.isEnabled = true; pause.isEnabled = false; stop.isEnabled = false } }
        }
        lifecycleScope.launch {
            while (running) {
                try {
                    val p = py.callAttr("get_status").toString().split("|")
                    if (p.size >= 7) {
                        val done = p[0].toIntOrNull() ?: 0; val total = p[1].toIntOrNull() ?: 0
                        val imgs = p[2].toIntOrNull() ?: 0; val vids = p[3].toIntOrNull() ?: 0
                        statusView.text = p[4]; statsView.text = "$done / $total files  •  $imgs images  •  $vids videos  •  ${p[5]}"
                        progress.progress = if (total > 0) done * 100 / total else 0
                    }
                } catch (_: Exception) {}
                delay(400)
            }
        }
    }
}
