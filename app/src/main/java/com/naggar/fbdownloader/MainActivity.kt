package com.naggar.fbdownloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var btnPick: Button
    private lateinit var btnDownload: Button
    private lateinit var folderLabel: TextView
    private lateinit var status: TextView

    private val PREFS = "prefs"
    private val KEY_TREE_URI = "tree_uri"
    private val REQ_TREE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        urlInput = findViewById(R.id.urlInput)
        btnPick = findViewById(R.id.btnPick)
        btnDownload = findViewById(R.id.btnDownload)
        folderLabel = findViewById(R.id.folderLabel)
        status = findViewById(R.id.status)

        val savedUri = getSavedTreeUri()
        if (savedUri != null) {
            folderLabel.text = savedUri.toString()
        }

        btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            startActivityForResult(intent, REQ_TREE)
        }

        btnDownload.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) {
                status.text = "Paste a Facebook URL first."
                return@setOnClickListener
            }
            val tree = getSavedTreeUri()
            if (tree == null) {
                status.text = "Pick a folder first."
                return@setOnClickListener
            }
            btnDownload.isEnabled = false
            status.text = "Resolving URL..."

            thread {
                try {
                    val finalUrl = resolveUrl(url)
                    val normalized = normalizeFbUrl(finalUrl)

                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
                    val tempOut = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "fb_temp_" + ts)
                    tempOut.mkdirs()

                    val py = Python.getInstance()
                    val mod = py.getModule("downloader")
                    val res = mod.callAttr("run", normalized, tempOut.absolutePath).toString()

                    val treeUri = Uri.parse(tree)
                    val success = copyDirToTree(tempOut, treeUri)

                    runOnUiThread {
                        status.text = if (success) "Done. Saved to picked folder.\n$res"
                                      else "Downloaded to temp but copy failed."
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Error: ${e.message}" }
                } finally {
                    runOnUiThread { btnDownload.isEnabled = true }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            saveTreeUri(uri.toString())
            folderLabel.text = uri.toString()
            status.text = "Folder selected."
        }
    }

    private fun saveTreeUri(uri: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri).apply()
    }

    private fun getSavedTreeUri(): String? {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null)
    }

    private fun resolveUrl(input: String): String {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val req = Request.Builder().url(input).get().build()
        val resp = client.newCall(req).execute()
        val finalUrl = resp.request.url.toString()
        resp.close()
        return finalUrl
    }

    private fun normalizeFbUrl(u: String): String {
        val regexGid = Regex("/groups/(\d+)")
        val regexPid = Regex("multi_permalinks=(\d+)")
        val gid = regexGid.find(u)?.groupValues?.getOrNull(1)
        val pid = regexPid.find(u)?.groupValues?.getOrNull(1)
        return if (gid != null && pid != null) {
            "https://www.facebook.com/groups/${gid}/permalink/${pid}/"
        } else {
            u
        }
    }

    private fun copyDirToTree(src: File, treeUri: Uri): Boolean {
        try {
            val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return false
            src.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val child = tree.createFile(getMimeFor(f.name), f.name) ?: return@forEach
                    writeFile(f, child.uri)
                } else if (f.isDirectory) {
                    val dir = tree.createDirectory(f.name) ?: return@forEach
                    copyDirToTree(f, dir.uri)
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun writeFile(src: File, destUri: Uri) {
        val ins: InputStream = src.inputStream()
        val outs = contentResolver.openOutputStream(destUri, "w")
        if (outs != null) {
            ins.copyTo(outs, 8192)
            outs.flush()
            outs.close()
        }
        ins.close()
    }

    private fun getMimeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
