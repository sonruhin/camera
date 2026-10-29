package az.cam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    // mode: 0 = adi sessiya 1080p60, 1 = high-speed sessiya 1080p60, 2 = ehtiyat (60 olmaya biler)
    private data class Cand(
        val id: String, val phys: String?, val zoom: Float, val fov: Double,
        val mode: Int, val fps: Range<Int>, val size: Size
    )

    private val S1080 = Size(1920, 1080)

    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var cm: CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var candidates: List<Cand> = emptyList()
    private var tryIndex = 0
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        cm = getSystemService(CameraManager::class.java)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        root.addView(tv)
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        start()
    }

    override fun onPause() {
        stop()
        super.onPause()
    }

    private fun start() {
        if (tv.isAvailable) openBest() else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openBest() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null; opening = false
        thread?.quitSafely(); thread = null; handler = null
    }

    private fun fov(c: CameraCharacteristics, zoom: Float): Double {
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0.0
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0.0
        return 2 * atan((sensor.width / (2f * f * zoom)).toDouble())
    }

    private fun has1080(c: CameraCharacteristics): Boolean {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.getOutputSizes(SurfaceTexture::class.java)?.any { it.width == 1920 && it.height == 1080 } == true
    }

    private fun buildCandidates(): List<Cand> {
        val b = windowManager.currentWindowMetrics.bounds
        val screenAspect = max(b.width(), b.height()).toDouble() / min(b.width(), b.height())
        val list = ArrayList<Cand>()
        for (id in cm.cameraIdList) {
            try {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                val reg60 = ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                val hsRange: Range<Int>? = try {
                    if (map.highSpeedVideoSizes?.contains(S1080) == true)
                        map.getHighSpeedVideoFpsRangesFor(S1080).filter { it.upper == 60 }.maxByOrNull { it.lower }
                    else null
                } catch (_: Exception) { null }
                val minZ = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
                val zoom = if (minZ < 1f) max(0.5f, minZ) else 1f
                val lf = fov(c, zoom)

                // Mentiqi kamera (mumkunse 0.5x zoom ile)
                if (reg60 != null && has1080(c)) list.add(Cand(id, null, zoom, lf, 0, reg60, S1080))
                else if (hsRange != null) list.add(Cand(id, null, zoom, lf, 1, hsRange, S1080))
                else {
                    val size = (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray())
                        .filter { it.width <= 1920 && it.height <= 1080 }
                        .sortedWith(compareBy({ abs(it.width.toDouble() / it.height - screenAspect) }, { -(it.width * it.height) }))
                        .firstOrNull()
                    val fps = ranges.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
                    if (size != null && fps != null) list.add(Cand(id, null, zoom, lf, 2, fps, size))
                }

                // Fiziki kameralar (ultra-geniş burada ola biler)
                if (reg60 != null) for (p in c.physicalCameraIds) {
                    try {
                        val pc = cm.getCameraCharacteristics(p)
                        if (has1080(pc)) list.add(Cand(id, p, 1f, fov(pc, 1f), 0, reg60, S1080))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return list.sortedWith(compareBy({ if (it.mode == 2) 1 else 0 }, { -it.fov }, { if (it.phys == null) 0 else 1 }))
    }

    private fun openBest() {
        if (opening || device != null) return
        opening = true
        candidates = buildCandidates()
        tryIndex = 0
        openCandidate()
    }

    private fun next() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
        tryIndex++
        openCandidate()
    }

    private fun openCandidate() {
        val p = candidates.getOrNull(tryIndex) ?: return
        try {
            cm.openCamera(p.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startPreview(d, p) }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { if (device === d || device == null) next() else d.close() }
            }, handler)
        } catch (e: SecurityException) {
        } catch (e: Exception) { next() }
    }

    private fun startPreview(d: CameraDevice, p: Cand) {
        try {
            val b = windowManager.currentWindowMetrics.bounds
            val sw = b.width().toFloat(); val sh = b.height().toFloat()
            val contentAspect = p.size.height.toFloat() / p.size.width // portret
            val (w, h) = if (sw / sh > contentAspect) Pair(sw, sw / contentAspect) else Pair(sh * contentAspect, sh)
            runOnUiThread { tv.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER) }

            val st = tv.surfaceTexture ?: return
            st.setDefaultBufferSize(p.size.width, p.size.height)
            val surface = Surface(st)

            val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            rb.addTarget(surface)
            rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, p.fps)
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            rb.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            if (p.phys == null) rb.set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoom)
            val req = rb.build()

            val out = OutputConfiguration(surface)
            if (p.phys != null) out.setPhysicalCameraId(p.phys)

            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        if (p.mode == 1) {
                            val hs = s as CameraConstrainedHighSpeedCaptureSession
                            hs.setRepeatingBurst(hs.createHighSpeedRequestList(req), null, handler)
                        } else s.setRepeatingRequest(req, null, handler)
                    } catch (_: Exception) { next() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { next() }
            }
            val cfg = SessionConfiguration(
                if (p.mode == 1) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
                listOf(out), { r -> handler?.post(r) }, cb
            )
            if (p.mode == 1) cfg.sessionParameters = req
            d.createCaptureSession(cfg)
        } catch (_: Exception) { next() }
    }
}
