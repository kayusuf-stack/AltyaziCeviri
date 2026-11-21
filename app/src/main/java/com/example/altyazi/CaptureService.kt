package com.example.altyazi

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CaptureService : Service() {
    // Altyazının bulunduğu alt bölge: ekranın alttan bu oranı taranır (0.4 = alt %40)
    private val SCAN_BOTTOM_RATIO = 0.4

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var last: Image? = null
    private var button: Button? = null
    private var full: TextView? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH).build())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("c", "Altyazı", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "c").setContentTitle("Altyazı Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent!!.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")!!
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)

        val b = wm.currentWindowMetrics.bounds
        val dpi = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(b.width(), b.height(), PixelFormat.RGBA_8888, 3).also { r ->
            r.setOnImageAvailableListener({
                val img = it.acquireLatestImage() ?: return@setOnImageAvailableListener
                last?.close(); last = img
            }, h)
            projection!!.createVirtualDisplay("cap", b.width(), b.height(), dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        addButton()
        return START_NOT_STICKY
    }

    private fun lp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    private fun addButton() {
        button = Button(this).apply {
            text = "ÇEVİR"
            setOnClickListener { capture() }
        }
        val p = lp(-2, -2, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply { gravity = Gravity.TOP or Gravity.END }
        wm.addView(button, p)
    }

    private fun capture() {
        button?.visibility = View.INVISIBLE
        h.postDelayed({
            button?.visibility = View.VISIBLE
            val img = last ?: return@postDelayed show("Görüntü alınamadı")
            val pl = img.planes[0]
            val bmp = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            val top = (img.height * (1 - SCAN_BOTTOM_RATIO)).toInt()
            val crop = Bitmap.createBitmap(bmp, 0, top, img.width, img.height - top)
            recognizer.process(InputImage.fromBitmap(crop, 0)).addOnSuccessListener { res ->
                val t = res.text.replace("\n", " ").trim()
                if (t.isEmpty()) show("Altyazı bulunamadı")
                else translator.translate(t).addOnSuccessListener { show(it) }
                    .addOnFailureListener { show("Çeviri modeli inmedi. İnternete bağlanıp tekrar dene.") }
            }.addOnFailureListener { show("Okuma hatası") }
        }, 300)
    }

    private fun show(text: String) {
        full?.let { wm.removeView(it) }
        full = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
            textSize = 34f; gravity = Gravity.CENTER; setPadding(60, 40, 60, 40)
            setOnClickListener { wm.removeView(this); full = null }
        }
        wm.addView(full, lp(-1, -1, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN))
    }

    override fun onDestroy() {
        button?.let { wm.removeView(it) }
        full?.let { wm.removeView(it) }
        last?.close(); projection?.stop()
        super.onDestroy()
    }
}
