package com.example.eyeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class GuardService : Service() {

    private val size = 224
    private val interval = 1500L
    private val holdMs = 3000L
    private val threshold = 0.6f

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var interpreter: Interpreter? = null
    private var worker: HandlerThread? = null
    private var bg: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var overlay: View? = null
    @Volatile private var paused = false
    @Volatile private var running = false
    private var holdUntil = 0L
    private var skipUntil = 0L
    private var lastSig = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            paused = i?.action == Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        loadModel()

        val t = HandlerThread("guard")
        t.start()
        worker = t
        bg = Handler(t.looper)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)
        projection = proj

        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2
        val h = dm.heightPixels / 2
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "eyeguard", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )

        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenReceiver, f)

        bg?.post(loop)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("guard", "EyeGuard", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "guard")
            .setContentTitle("EyeGuard شغال")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun loadModel() {
        try {
            val fd = assets.openFd("nsfw.tflite")
            val ch = FileInputStream(fd.fileDescriptor).channel
            val mb = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = Interpreter(mb, Interpreter.Options().setNumThreads(2))
        } catch (e: Exception) {
            interpreter = null
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
            }
            if (running) bg?.postDelayed(this, interval)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val img = reader?.acquireLatestImage() ?: return

        if (paused) {
            img.close()
            return
        }
        if (overlay != null) {
            img.close()
            if (now >= holdUntil) {
                main.post { hideOverlay() }
                skipUntil = now + 800
            }
            return
        }
        if (now < skipUntil) {
            img.close()
            return
        }

        val bmp = toSmallBitmap(img)
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()

        var sig = 0L
        var i = 0
        while (i < px.size) {
            sig = sig * 31 + px[i]
            i += 97
        }
        if (sig == lastSig) return
        lastSig = sig

        if (unsafeScore(px) >= threshold) {
            holdUntil = now + holdMs
            main.post { showOverlay() }
        }
    }

    private fun toSmallBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pad = plane.rowStride - plane.pixelStride * img.width
        val fullW = img.width + pad / plane.pixelStride
        val full = Bitmap.createBitmap(fullW, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        val h = img.height
        val w = img.width
        img.close()
        val cropped = if (fullW != w) Bitmap.createBitmap(full, 0, 0, w, h) else full
        val small = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped !== full) cropped.recycle()
        full.recycle()
        return small
    }

    private fun unsafeScore(px: IntArray): Float {
        val itp = interpreter ?: return 0f
        val buf = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        for (p in px) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8) and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        val n = itp.getOutputTensor(0).shape().last()
        val out = Array(1) { FloatArray(n) }
        itp.run(buf, out)
        val o = out[0]
        return when (n) {
            2 -> o[1]
            5 -> o[1] + o[3] + o[4]
            else -> o[n - 1]
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 31) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = 90
            v.setBackgroundColor(0x99000000.toInt())
        } else {
            v.setBackgroundColor(0xF0202020.toInt())
        }
        wm.addView(v, lp)
        overlay = v
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        overlay = null
    }

    override fun onDestroy() {
        running = false
        bg?.removeCallbacksAndMessages(null)
        hideOverlay()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        interpreter?.close()
        worker?.quitSafely()
        super.onDestroy()
    }
}
