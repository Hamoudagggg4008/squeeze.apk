package com.example.squeeze

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var label: TextView
    private lateinit var seek: SeekBar
    private lateinit var bar: ProgressBar
    private var uri: Uri? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { uri = u; status.text = "الملف: " + name(u) + "\nالحجم: " + mb(size(u)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pick = Button(this).apply { text = "اختر ملف"; setOnClickListener { picker.launch(arrayOf("*/*")) } }
        label = TextView(this).apply { text = "النسبة المطلوبة: 10%"; textSize = 18f; setPadding(0, 48, 0, 0) }
        seek = SeekBar(this).apply {
            max = 50; progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    label.text = "النسبة المطلوبة: ${p.coerceAtLeast(2)}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val go = Button(this).apply { text = "ابدأ الضغط"; setOnClickListener { start() } }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        status = TextView(this).apply { text = "اختر ملفاً للبدء"; textSize = 16f; setPadding(0, 48, 0, 0) }
        listOf(pick, label, seek, go, bar, status).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        val u = uri ?: run { status.text = "اختر ملفاً أولاً"; return }
        val ratio = seek.progress.coerceAtLeast(2) / 100.0
        val mime = contentResolver.getType(u) ?: ""
        status.text = "جاري الضغط..."
        bar.progress = 0
        when {
            mime.startsWith("video/") -> video(u, ratio)
            mime.startsWith("image/") -> thread { image(u, ratio) }
            else -> thread { generic(u) }
        }
    }

    private fun outFile(base: String, ext: String): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        return File(dir, "squeezed_" + base.substringBeforeLast('.') + ext).also { it.delete() }
    }

    private fun video(u: Uri, ratio: Double) {
        val total = size(u)
        val r = MediaMetadataRetriever()
        r.setDataSource(this, u)
        val durSec = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 1000L) / 1000.0
        r.release()
        val totalBps = ratio * total * 8 / durSec
        val videoBps = (totalBps - 64_000).coerceAtLeast(100_000.0).toInt()
        val height = when {
            videoBps > 1_500_000 -> 720
            videoBps > 600_000 -> 480
            else -> 360
        }
        val out = outFile(name(u), ".mp4")
        val enc = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBps).build())
            .build()
        val effects = Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(height)))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(u)).setEffects(effects).build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, e: ExportResult) {
                    bar.progress = 100
                    done(total, out)
                }
                override fun onError(c: Composition, e: ExportResult, ex: ExportException) {
                    status.text = "فشل: " + ex.message
                }
            }).build()
        t.start(item, out.absolutePath)
        val h = android.os.Handler(mainLooper)
        val holder = ProgressHolder()
        h.post(object : Runnable {
            override fun run() {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) bar.progress = holder.progress
                if (bar.progress < 100) h.postDelayed(this, 500)
            }
        })
    }

    private fun image(u: Uri, ratio: Double) {
        val total = size(u)
        val target = (total * ratio).toLong()
        var bmp = contentResolver.openInputStream(u).use { BitmapFactory.decodeStream(it) }
            ?: return ui { status.text = "تعذّر قراءة الصورة" }
        val out = outFile(name(u), ".jpg")
        var scale = 1.0
        while (true) {
            val b = if (scale < 1.0) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(64), (bmp.height * scale).toInt().coerceAtLeast(64), true) else bmp
            var q = 85
            var bytes: ByteArray
            do {
                val bos = java.io.ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, q, bos)
                bytes = bos.toByteArray()
                q -= 10
            } while (bytes.size > target && q >= 25)
            if (bytes.size <= target || scale < 0.15) {
                out.writeBytes(bytes)
                break
            }
            scale *= 0.8
        }
        ui { bar.progress = 100; done(total, out) }
    }

    private fun generic(u: Uri) {
        val total = size(u)
        val out = outFile(name(u), "." + name(u).substringAfterLast('.', "bin") + ".xz")
        contentResolver.openInputStream(u)!!.use { inp ->
            XZOutputStream(FileOutputStream(out), LZMA2Options(7)).use { xz ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    xz.write(buf, 0, n); done += n
                    if (total > 0) ui { bar.progress = (done * 100 / total).toInt() }
                }
            }
        }
        ui { done(total, out) }
    }

    private fun done(orig: Long, out: File) {
        val pct = if (orig > 0) out.length() * 100.0 / orig else 0.0
        status.text = "تم ✔\nالأصلي: ${mb(orig)}\nالجديد: ${mb(out.length())} (${"%.1f".format(pct)}%)\nالمسار:\n${out.absolutePath}"
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun thread(f: () -> Unit) = Thread {
        try { f() } catch (e: Throwable) { ui { status.text = "خطأ: " + e.message } }
    }.start()

    private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
    private fun name(u: Uri): String = contentResolver.query(u, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: "file"
    private fun size(u: Uri): Long = contentResolver.query(u, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) else 0L
    } ?: 0L
}
