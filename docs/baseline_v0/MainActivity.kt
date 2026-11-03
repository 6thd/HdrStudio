package com.mojahed.hdrstudio

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E0E10")
    private val card = Color.parseColor("#1B1B1F")
    private val accent = Color.parseColor("#FFD60A")

    private var srcUri: Uri? = null
    private var running = false
    private val cancel = AtomicBoolean(false)

    private lateinit var pickBtn: Button
    private lateinit var convertBtn: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sExpand: SeekBar
    private lateinit var sSat: SeekBar
    private lateinit var sSharp: SeekBar
    private lateinit var sContrast: SeekBar
    private lateinit var quality: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }

        root.addView(text("HDR Studio", 28f, accent, true))
        root.addView(text("تحويل SDR إلى HDR10 · ألوان أغنى · إضاءة أعلى · تفاصيل أوضح", 14f, Color.LTGRAY, false))

        pickBtn = button("اختيار فيديو", accent, Color.BLACK) { pickVideo() }
        root.addView(pickBtn, lp(top = 20))

        sExpand = slider(root, "قوة HDR (توسيع الإضاءة)", 80)
        sSat = slider(root, "ثراء الألوان", 40)
        sSharp = slider(root, "وضوح التفاصيل", 40)
        sContrast = slider(root, "التباين", 30)

        root.addView(text("جودة الترميز", 14f, Color.WHITE, false), lp(top = 16))
        quality = Spinner(this)
        quality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("عادية", "عالية (موصى بها)", "قصوى")
        )
        quality.setSelection(1)
        root.addView(quality)

        convertBtn = button("تحويل إلى HDR", accent, Color.BLACK) { onConvertClicked() }
        convertBtn.isEnabled = false
        root.addView(convertBtn, lp(top = 24))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress, lp(top = 16))

        status = text("اختر فيديو للبدء.", 14f, Color.LTGRAY, false)
        root.addView(status, lp(top = 8))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    // ---------------------------------------------------------------- picking

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 42)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            srcUri = uri
            convertBtn.isEnabled = true
            status.text = "تم اختيار: " + displayName(uri)
            progress.progress = 0
        }
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video"
    } catch (e: Exception) {
        "video"
    }

    // ------------------------------------------------------------- converting

    private fun onConvertClicked() {
        if (running) {
            cancel.set(true)
            status.text = "جاري الإلغاء..."
            return
        }
        val uri = srcUri ?: return
        val params = HdrConverter.Params(
            expansion = sExpand.progress / 100f,
            saturation = 1f + sSat.progress / 100f * 0.5f,
            sharpness = sSharp.progress / 100f * 1.5f,
            contrast = sContrast.progress / 100f * 0.5f,
            bitsPerPixel = floatArrayOf(0.10f, 0.14f, 0.20f)[quality.selectedItemPosition]
        )
        running = true
        cancel.set(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        convertBtn.text = "إلغاء"
        pickBtn.isEnabled = false
        status.text = "جاري التحويل... أبقِ الشاشة مفتوحة."

        thread(name = "hdr-convert") {
            val tmp = File(cacheDir, "hdr_${System.currentTimeMillis()}.mp4")
            try {
                val r = HdrConverter(applicationContext).convert(uri, tmp, params, cancel) { f ->
                    runOnUiThread { progress.progress = (f * 1000).toInt() }
                }
                val name = "HDR_" + System.currentTimeMillis() + ".mp4"
                saveToGallery(tmp, name)
                runOnUiThread {
                    status.text = "تم ✅ حُفظ في Movies/HDR Studio\n" +
                        "${r.width}×${r.height} · ${r.fps}fps · ${r.bitrate / 1_000_000} Mbps\n" +
                        "المرمّز: ${r.encoder}" +
                        (if (r.pqSurface) "" else "\n(تنبيه: سطح BT2020-PQ غير مدعوم، قد تحتاج لمعاينة النتيجة)")
                }
            } catch (e: CancellationException) {
                runOnUiThread { status.text = "تم الإلغاء." }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "فشل التحويل: ${t.message ?: t.javaClass.simpleName}" }
            } finally {
                tmp.delete()
                runOnUiThread {
                    running = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    convertBtn.text = "تحويل إلى HDR"
                    pickBtn.isEnabled = true
                }
            }
        }
    }

    private fun saveToGallery(file: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HDR Studio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("تعذر إنشاء ملف في المعرض")
        contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
    }

    // --------------------------------------------------------------- UI utils

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun button(label: String, bgColor: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(fg)
        setBackgroundColor(bgColor)
        setOnClickListener { onClick() }
    }

    private fun slider(parent: LinearLayout, label: String, initial: Int): SeekBar {
        val t = text("$label: $initial%", 14f, Color.WHITE, false)
        val sb = SeekBar(this).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    t.text = "$label: $v%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(t, lp(top = 16))
        parent.addView(sb)
        return sb
    }
}
