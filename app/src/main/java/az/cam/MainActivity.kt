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
import kotlin.math.tan

class MainActivity : Activity() {

    private data class Pick(val id: String, val zoom: Float, val size: Size, val fps: Range<Int>, val fov: Double)

    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var cm: CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var candidates: List<Pick> = emptyList()
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

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        // onResume icazədən sonra özü start() çağırır
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

    private fun buildCandidates(): List<Pick> {
        val b = windowManager.currentWindowMetrics.bounds
        val screenAspect = max(b.width(), b.height()).toDouble() / min(b.width(), b.height())
        val list = ArrayList<Pick>()
        for (id in cm.cameraIdList) {
            try {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?: arrayOf(Range(30, 30))
                val fps = ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
                    ?: ranges.maxWithOrNull(compareBy({ it.upper }, { it.lower }))!!
                val all = (map.getOutputSizes(SurfaceTexture::class.java) ?: continue)
                    .filter { it.width <= 1920 && it.height <= 1080 }
                if (all.isEmpty()) continue
                val size = all.sortedWith(compareBy(
                    { if (map.getOutputMinFrameDuration(SurfaceTexture::class.java, it) <= 16_700_000L) 0 else 1 },
                    { abs(it.width.toDouble() / it.height - screenAspect) },
                    { -(it.width * it.height) }
                )).first()
                val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: continue
                val minZ = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
                val zoom = if (minZ < 1f) max(0.5f, minZ) else 1f
                val fov = 2 * atan(tan(atan((sensor.width / (2f * f)).toDouble())) / zoom)
                list.add(Pick(id, zoom, size, fps, fov))
            } catch (_: Exception) {}
        }
        return list.sortedByDescending { it.fov }
    }

    private fun openBest() {
        if (opening || device != null) return
        opening = true
        candidates = buildCandidates()
        tryIndex = 0
        openCandidate()
    }

    private fun openCandidate() {
        val p = candidates.getOrNull(tryIndex) ?: return
        try {
            cm.openCamera(p.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startPreview(d, p) }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { d.close(); tryIndex++; openCandidate() }
            }, handler)
        } catch (e: SecurityException) {
        } catch (e: Exception) { tryIndex++; openCandidate() }
    }

    private fun startPreview(d: CameraDevice, p: Pick) {
        val b = windowManager.currentWindowMetrics.bounds
        val sw = b.width().toFloat(); val sh = b.height().toFloat()
        val contentAspect = p.size.height.toFloat() / p.size.width // portrait
        val (w, h) = if (sw / sh > contentAspect) Pair(sw, sw / contentAspect) else Pair(sh * contentAspect, sh)
        runOnUiThread {
            tv.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
        }
        val st = tv.surfaceTexture ?: return
        st.setDefaultBufferSize(p.size.width, p.size.height)
        val surface = Surface(st)
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, p.fps)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoom)
        }.build()
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            { r -> handler?.post(r) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try { s.setRepeatingRequest(req, null, handler) } catch (_: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    d.close(); tryIndex++; openCandidate()
                }
            })
        d.createCaptureSession(cfg)
    }
}
