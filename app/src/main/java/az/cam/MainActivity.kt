package az.cam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    // mode: 0 = adi sessiya 1080p60, 1 = high-speed 1080p60, 2 = ehtiyat (60 olmaya biler)
    // order: 0 = zoom<1 olan mentiqi kamera, 1 = adi id, 2 = fiziki axin
    private data class Cand(
        val id: String, val phys: String?, val zoom: Float, val fov: Double,
        val mode: Int, val fps: Range<Int>, val size: Size, val order: Int
    )

    private val S1080 = Size(1920, 1080)

    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var info: TextView
    private lateinit var cm: CameraManager
    private lateinit var orient: OrientationEventListener

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var curSurface: Surface? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var candidates: List<Cand> = emptyList()
    private var tryIndex = 0
    private var opening = false
    private var canRun = false
    private var gen = 0

    @Volatile private var curSize: Size? = null
    @Volatile private var framesSinceStart = 0
    private var frames = 0
    private var lastT = 0L
    private var fpsNow = 0f
    private var report = ""
    private var mainMode = false          // false = 0.6x ultra-wide, true = 1.0x main camera (60fps attempt)
    private var downT = 0L
    private var downInCorner = false
    private val tapTimes = ArrayList<Long>()
    private var status = ""

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
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (canRun) openBest() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                framesSinceStart++
                frames++
                val now = SystemClock.elapsedRealtime()
                if (lastT == 0L) lastT = now
                if (now - lastT >= 1000) {
                    fpsNow = frames * 1000f / (now - lastT)
                    frames = 0; lastT = now
                    if (info.visibility == View.VISIBLE) refreshInfo()
                }
            }
        }
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }
        root.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Gizli diaqnostika: ekrana uzun basanda acilir/baglanir
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = 100 })
        root.setOnLongClickListener {
            info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshInfo(); true
        }
        // Hidden switch: triple-tap the top-left corner (150dp) -> toggles 0.6x <-> 1.0x/60fps
        root.setOnTouchListener { _, e ->
            val dn = resources.displayMetrics.density
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downT = e.eventTime; downInCorner = e.x < 150 * dn && e.y < 150 * dn }
                MotionEvent.ACTION_UP -> if (downInCorner && e.eventTime - downT < 250) {
                    val now = e.eventTime
                    tapTimes.removeAll { now - it > 1500 }
                    tapTimes.add(now)
                    if (tapTimes.size >= 3) { tapTimes.clear(); switchMode() }
                }
            }
            false
        }
        setContentView(root)

        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) { updateTransform() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTransform()
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
        if (orient.canDetectOrientation()) orient.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        canRun = true
        if (tv.isAvailable) openBest()
    }

    override fun onPause() {
        canRun = false
        orient.disable()
        stop()
        super.onPause()
    }

    private fun closeCamera() {
        gen++
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { curSurface?.release() } catch (_: Exception) {}
        session = null; device = null; curSurface = null; opening = false
    }

    private fun switchMode() {
        closeCamera()
        mainMode = !mainMode
        if (canRun && tv.isAvailable) openBest()
    }

    private fun stop() {
        gen++
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { curSurface?.release() } catch (_: Exception) {}
        session = null; device = null; curSurface = null; opening = false
        thread?.quitSafely(); thread = null; handler = null
    }

    private fun refreshInfo() {
        info.text = report + "\n" + status + "\nCANLI FPS: " + String.format("%.1f", fpsNow)
    }

    private fun updateTransform() {
        val size = curSize ?: return
        val vw = tv.width.toFloat(); val vh = tv.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val rot = display?.rotation ?: Surface.ROTATION_0
        val cx = vw / 2f; val cy = vh / 2f
        val m = Matrix()
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            val viewRect = RectF(0f, 0f, vw, vh)
            val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / size.height, vw / size.width)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rot - 2), cx, cy)
        } else {
            val contentAspect = size.height.toFloat() / size.width
            val viewAspect = vw / vh
            var sx = 1f; var sy = 1f
            if (viewAspect < contentAspect) sx = contentAspect / viewAspect else sy = viewAspect / contentAspect
            m.setScale(sx, sy, cx, cy)
            if (rot == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
        }
        tv.setTransform(m)
    }

    private fun fov(c: CameraCharacteristics, zoom: Float): Double {
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0.0
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0.0
        return 2 * atan((sensor.width / (2f * f * zoom)).toDouble())
    }

    private fun deg(r: Double) = Math.toDegrees(r).toInt()

    private fun makeCand(
        id: String, phys: String?, zoom: Float, shape: CameraCharacteristics,
        logical: CameraCharacteristics, screenAspect: Double, order: Int
    ): Cand? {
        val map = shape.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val ranges = logical.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val reg60 = ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val can1080 = sizes.any { it.width == 1920 && it.height == 1080 }
        val fv = fov(shape, zoom)
        if (reg60 != null && can1080) return Cand(id, phys, zoom, fv, 0, reg60, S1080, order)
        if (phys == null) {
            val lm = logical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val hs: Range<Int>? = try {
                if (lm != null && lm.highSpeedVideoSizes.contains(S1080))
                    lm.getHighSpeedVideoFpsRangesFor(S1080).filter { it.upper == 60 }.maxByOrNull { it.lower }
                else null
            } catch (_: Exception) { null }
            if (hs != null) return Cand(id, phys, zoom, fv, 1, hs, S1080, order)
        }
        val size = if (can1080) S1080 else sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .sortedWith(compareBy<Size>({ abs(it.width.toDouble() / it.height - screenAspect) }, { -(it.width * it.height) }))
            .firstOrNull() ?: return null
        val fps = ranges.sortedWith(compareBy<Range<Int>>({ it.upper }, { it.lower })).lastOrNull() ?: Range(30, 30)
        return Cand(id, phys, zoom, fv, 2, fps, size, order)
    }

    private fun buildCandidates(): List<Cand> {
        val b = windowManager.currentWindowMetrics.bounds
        val screenAspect = max(b.width(), b.height()).toDouble() / min(b.width(), b.height())
        val rep = StringBuilder("KAMERALAR:\n")
        val ids = LinkedHashSet<String>(cm.cameraIdList.toList())
        for (i in 0..9) {
            val s = i.toString()
            if (s in ids) continue
            try { cm.getCameraCharacteristics(s); ids.add(s); rep.append("gizli id $s tapildi\n") } catch (_: Exception) {}
        }
        val listed = cm.cameraIdList.toSet()
        val list = ArrayList<Cand>()
        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                val minZ = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
                val phys = c.physicalCameraIds
                rep.append("id=$id ${if (back) "arxa" else "on"} zoomMin=$minZ phys=$phys fov=${deg(fov(c, 1f))} 1080=${has1080(c)} ")
                rep.append("ae60=${(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()).any { it.upper == 60 }}\n")
                if (!back) continue
                val zoom = if (minZ < 1f) max(0.5f, minZ) else 1f
                makeCand(id, null, zoom, c, c, screenAspect, if (zoom < 1f) 0 else 1)?.let { list.add(it) }
                for (p in phys) {
                    try {
                        val pc = cm.getCameraCharacteristics(p)
                        rep.append("  fiziki $p fov=${deg(fov(pc, 1f))} 1080=${has1080(pc)}\n")
                        makeCand(id, p, 1f, pc, c, screenAspect, 2)?.let { list.add(it) }
                        if (p !in listed && p !in ids) makeCand(p, null, 1f, pc, pc, screenAspect, 1)?.let { list.add(it) }
                    } catch (e: Exception) { rep.append("  fiziki $p oxuna bilmedi\n") }
                }
            } catch (e: Exception) { rep.append("id=$id oxuna bilmedi\n") }
        }
        report = rep.toString()
        return list.sortedWith(compareBy<Cand>({ -(it.fov * 10).toInt() }, { it.mode }, { it.order }))
    }

    private fun label(c: Cand?): String =
        if (c == null) "yoxdur" else "id=${c.id}${c.phys?.let { "/fiziki " + it } ?: ""} mod=${c.mode} ${c.size.width}x${c.size.height} fps=${c.fps} zoom=${c.zoom} fov=${deg(c.fov)}"

    private fun openBest() {
        if (opening || device != null) return
        opening = true
        candidates = buildCandidates()
        if (mainMode) {
            // 1.0x main camera: force a 60fps range first (not advertised by the phone), then fall back to normal
            val m = candidates.firstOrNull { it.id == "0" && it.phys == null }
            if (m != null) candidates = listOf(m.copy(mode = 0, fps = Range(60, 60)), m)
        }
        status = (if (mainMode) "MOD: 1.0x / 60fps denemesi\n" else "MOD: 0.6x ultra-genis\n") + "NAMIZAD SIRASI:\n" + candidates.mapIndexed { i, c -> "$i: ${label(c)}" }.joinToString("\n")
        tryIndex = 0
        openCandidate()
    }

    private fun next(why: String) {
        status += "\nXETA #$tryIndex: $why"
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { curSurface?.release() } catch (_: Exception) {}
        session = null; device = null; curSurface = null
        tryIndex++
        openCandidate()
    }

    private fun openCandidate() {
        val p = candidates.getOrNull(tryIndex)
        if (p == null) { status += "\nBUTUN NAMIZADLAR BITDI"; runOnUiThread { refreshInfo() }; return }
        val g = ++gen
        framesSinceStart = 0
        status += "\nCEHD #$tryIndex: ${label(p)}"
        try {
            cm.openCamera(p.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (g != gen) { d.close(); return }
                    device = d; startPreview(d, p, g)
                }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { if (g == gen) next("openCamera xeta $e") else d.close() }
            }, handler)
        } catch (e: SecurityException) {
        } catch (e: Exception) { next("openCamera: ${e.message}") }
    }

    private fun startPreview(d: CameraDevice, p: Cand, g: Int) {
        try {
            runOnUiThread { curSize = p.size; updateTransform() }
            val st = tv.surfaceTexture ?: return
            st.setDefaultBufferSize(p.size.width, p.size.height)
            val surface = Surface(st)
            curSurface = surface

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
                    if (g != gen) { s.close(); return }
                    session = s
                    try {
                        if (p.mode == 1) {
                            val hs = s as CameraConstrainedHighSpeedCaptureSession
                            hs.setRepeatingBurst(hs.createHighSpeedRequestList(req), null, handler)
                        } else s.setRepeatingRequest(req, null, handler)
                        status += "\nISLEYIR: #$tryIndex"
                        // 2.5 san erzinde kadr gelmirse novbeti kameraya kec
                        handler?.postDelayed({
                            if (g == gen && framesSinceStart == 0) runOnUiThread { if (g == gen) next("kadr gelmir") }
                        }, 2500)
                    } catch (e: Exception) { if (g == gen) next("request: ${e.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { if (g == gen) next("session qurulmadi") }
            }
            val cfg = SessionConfiguration(
                if (p.mode == 1) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
                listOf(out), { r -> handler?.post(r) }, cb
            )
            if (p.mode == 1) cfg.sessionParameters = req
            d.createCaptureSession(cfg)
        } catch (e: Exception) { if (g == gen) next("startPreview: ${e.message}") }
    }

    private fun has1080(c: CameraCharacteristics): Boolean {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.getOutputSizes(SurfaceTexture::class.java)?.any { it.width == 1920 && it.height == 1080 } == true
    }
}
