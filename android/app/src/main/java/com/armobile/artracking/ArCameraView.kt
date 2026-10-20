package com.armobile.artracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

interface ArTrackingListener {
    fun onAnchorFound(name: String)
    fun onAnchorUpdated(
        name: String,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
    )
    fun onAnchorRemoved(name: String)
    /** state: "loading" | "ready" | "error" */
    fun onStatus(state: String, message: String?)
}

private const val TAG = "ArCameraView"

/** Trần số ảnh của 1 AugmentedImageDatabase (ARCore) */
private const val MAX_DB_IMAGES = 1000

/** Ảnh trigger được thu nhỏ về tối đa cỡ này trước khi addImage (nhanh hơn, ARCore vẫn đủ chi tiết) */
private const val MAX_TARGET_DIM = 1024

class TargetSpec(val name: String, val url: String)

/**
 * View camera AR thuần, không qua react-viro. Tự nhận diện NHIỀU ảnh trigger cùng lúc:
 *  - `targetsJson`: danh sách [{name, url}] (name = projectId) -> dựng 1 AugmentedImageDatabase.
 *    Database được serialize ra cache nên lần mở app sau chỉ deserialize (nhanh), không addImage lại.
 *  - Mỗi ảnh có trạng thái tracking riêng; báo found/removed theo `name`.
 *  - `overlayJson`: {"target": name, "assets": [...]} — overlay của project đang "active", chỉ vẽ khi
 *    ảnh của target đó đang được track thật (FULL_TRACKING).
 */
class ArCameraView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    var listener: ArTrackingListener? = null
    var physicalWidthMeters: Float = 0.15f
    /** Bắn event pose mỗi frame qua bridge — chỉ bật khi cần debug (60 event/giây/marker) */
    var emitPose: Boolean = false
    /** Gọi khi view bị gỡ (manager dùng để bỏ lifecycle listener) */
    var onDispose: (() -> Unit)? = null

    /** JSON mảng [{"name": "...", "url": "..."}] */
    var targetsJson: String? = null
        set(value) {
            if (field == value) return
            field = value
            restartTargets(value)
        }

    /** Đổi số này để dựng lại database + session (nút "Thử lại" khi lỗi) mà không cần gỡ view */
    var reloadToken: Int = 0
        set(value) {
            if (field == value) return
            field = value
            restartTargets(targetsJson)
        }

    /** {"target": "<name>", "assets": [...]} — xem parseOverlayConfig */
    var overlayJson: String? = null
        set(value) {
            if (field == value) return
            field = value
            applyOverlay(value)
        }

    @Volatile private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val overlay = OverlayRenderer { queueEvent(it) }
    @Volatile private var activeTarget: String? = null
    @Volatile private var loadGeneration = 0
    @Volatile private var hostResumed = false
    private var surfaceReady = false
    private var viewWidth = 0
    private var viewHeight = 0

    // Chỉ truy cập trên GL thread
    private val trackedNames = HashSet<String>()
    private val knownImages = HashMap<String, AugmentedImage>()
    private val lastState = HashMap<String, Int>()
    private val poses = HashMap<String, Pose>()

    companion object {
        private val SESSION_LOCK = Any()
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                backgroundRenderer.createOnGlThread()
                overlay.createOnGlThread()
                surfaceReady = true
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
                viewWidth = width
                viewHeight = height
                session?.setDisplayGeometry(getDisplayRotation(), width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                drawFrame()
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun getDisplayRotation(): Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation

    // ---------------------------------------------------------------- overlay

    private fun applyOverlay(json: String?) {
        val cfg = parseOverlayConfig(json)
        activeTarget = cfg.target
        overlay.setAssets(cfg.specs)
        queueEvent {
            val t = cfg.target
            overlay.setActive(t != null && trackedNames.contains(t))
        }
    }

    // ---------------------------------------------------------------- dựng database + session

    private fun parseTargets(json: String?): List<TargetSpec> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val url = o.optString("url")
                if (name.isEmpty() || url.isEmpty()) null else TargetSpec(name, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "targetsJson không hợp lệ", e)
            emptyList()
        }
    }

    private fun restartTargets(json: String?) {
        loadGeneration++
        val gen = loadGeneration
        Log.i(TAG, "restartTargets view=${System.identityHashCode(this)} gen=$gen")
        queueEvent { releaseSession() }
        if (json == null) return
        val targets = parseTargets(json)
        if (targets.isEmpty()) {
            listener?.onStatus("error", "Không có ảnh trigger nào để nhận diện")
            return
        }
        Thread { prepareTargets(gen, targets) }.start()
    }

    /** GL thread */
    private fun releaseSession() {
        session?.close()
        session = null
        trackedNames.clear()
        knownImages.clear()
        lastState.clear()
        poses.clear()
        overlay.setActive(false)
    }

    /**
     * Thread nền: CHỈ tải ảnh (khi chưa có cache database). Việc tạo Session + dựng database chạy ở GL thread,
     * nơi ARCore đã hoạt động ổn từ trước — tạo Session ở thread nền thuần làm ARCore ném FatalException.
     */
    private fun prepareTargets(gen: Int, all: List<TargetSpec>) {
        try {
            val targets = if (all.size > MAX_DB_IMAGES) {
                Log.w(TAG, "Có ${all.size} ảnh trigger, ARCore chỉ nạp tối đa $MAX_DB_IMAGES — bỏ phần dư")
                all.take(MAX_DB_IMAGES)
            } else all
            listener?.onStatus("loading", "Đang chuẩn bị ${targets.size} ảnh nhận diện…")

            val cacheFile = cacheFileFor(targets)
            val hasCache = cacheFile.exists() && cacheFile.length() > 0
            val images = if (hasCache) null else downloadAll(targets, gen)
            if (gen != loadGeneration) return
            queueEvent { createSessionOnGl(gen, targets, cacheFile, images) }
        } catch (e: Throwable) {
            fail("Không tải được ảnh nhận diện: ${describe(e)}", e)
        }
    }

    /** GL thread. images == null nghĩa là dùng database đã cache. */
    private fun createSessionOnGl(gen: Int, targets: List<TargetSpec>, cacheFile: File, images: List<Pair<String, Bitmap>>?) {
        if (gen != loadGeneration) return
        var s: Session? = null
        try {
            val avail = ArCoreApk.getInstance().checkAvailability(context)
            Log.i(TAG, "ARCore availability=$avail (view=${System.identityHashCode(this)}, thread=${Thread.currentThread().name})")
            when (avail) {
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    fail("Thiết bị này không hỗ trợ ARCore", null)
                    return
                }
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED, ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                    fail("Cần cài hoặc cập nhật \"Google Play Services for AR\" trên Google Play", null)
                    return
                }
                else -> Unit
            }
            listener?.onStatus("loading", "Đang khởi tạo AR…")

            // Chỉ cho phép 1 Session được tạo tại một thời điểm trong toàn process
            s = synchronized(SESSION_LOCK) { Session(context) }
            val db = if (images == null) loadCachedDatabase(s, cacheFile) else buildDatabase(s, targets, cacheFile, images, gen)
            if (gen != loadGeneration) return
            if (db == null) {
                if (images == null) {
                    // Cache hỏng -> xoá, chạy lại từ đầu (lần này sẽ tải ảnh và dựng mới)
                    Log.w(TAG, "Cache imgdb hỏng, dựng lại từ đầu")
                    cacheFile.delete()
                    restartTargets(targetsJson)
                } else {
                    fail("Không có ảnh trigger nào dùng được (tải lỗi hoặc ảnh chất lượng thấp)", null)
                }
                return
            }
            val config = Config(s).apply {
                focusMode = Config.FocusMode.AUTO
                augmentedImageDatabase = db
            }
            s.configure(config)
            val ready: Session = s
            s = null // quyền sở hữu chuyển cho view
            finishSetup(ready, gen)
        } catch (e: UnavailableArcoreNotInstalledException) {
            fail("ARCore chưa được cài trên máy này", e)
        } catch (e: UnavailableUserDeclinedInstallationException) {
            fail("Bạn đã từ chối cài ARCore", e)
        } catch (e: UnavailableApkTooOldException) {
            fail("ARCore trên máy quá cũ, cần cập nhật", e)
        } catch (e: UnavailableSdkTooOldException) {
            fail("Ứng dụng dùng ARCore SDK quá cũ so với thiết bị", e)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            fail("Thiết bị này không hỗ trợ ARCore", e)
        } catch (e: Throwable) {
            fail("Không khởi tạo được AR: ${describe(e)}", e)
        } finally {
            s?.close()
        }
    }

    private fun describe(e: Throwable): String =
        e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    private fun fail(message: String, e: Throwable?) {
        if (e != null) Log.e(TAG, message, e) else Log.e(TAG, message)
        listener?.onStatus("error", message)
    }

    /** GL thread */
    private fun finishSetup(s: Session, gen: Int) {
        if (gen != loadGeneration) {
            s.close()
            return
        }
        session?.close()
        s.setCameraTextureName(backgroundRenderer.textureId)
        // Session dựng muộn, có thể SAU onSurfaceChanged -> phải set display geometry ở đây
        if (viewWidth > 0 && viewHeight > 0) s.setDisplayGeometry(getDisplayRotation(), viewWidth, viewHeight)
        session = s
        if (hostResumed) {
            try { s.resume() } catch (e: Exception) { Log.e(TAG, "resume after setup failed", e) }
        }
        listener?.onStatus("ready", null)
    }

    private fun cacheFileFor(targets: List<TargetSpec>): File {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("w=$physicalWidthMeters\n".toByteArray())
        for (t in targets.sortedBy { it.name }) md.update("${t.name}|${t.url}\n".toByteArray())
        val key = md.digest().joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "armobile_aug_$key.imgdb")
    }

    private fun loadCachedDatabase(s: Session, cacheFile: File): AugmentedImageDatabase? {
        return try {
            FileInputStream(cacheFile).use {
                val db = AugmentedImageDatabase.deserialize(s, it)
                Log.i(TAG, "Nạp database từ cache: ${db.numImages} ảnh")
                db
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được cache imgdb", e)
            null
        }
    }

    private fun buildDatabase(
        s: Session, targets: List<TargetSpec>, cacheFile: File,
        images: List<Pair<String, Bitmap>>, gen: Int,
    ): AugmentedImageDatabase? {
        val db = AugmentedImageDatabase(s)
        var added = 0
        for ((i, pair) in images.withIndex()) {
            if (gen != loadGeneration) return null
            val (name, bmp) = pair
            try {
                db.addImage(name, bmp, physicalWidthMeters)
                added++
            } catch (e: ImageInsufficientQualityException) {
                Log.w(TAG, "ARCore từ chối ảnh '$name': chất lượng feature quá thấp — bỏ qua")
            } catch (e: Exception) {
                Log.w(TAG, "addImage '$name' lỗi — bỏ qua", e)
            }
            bmp.recycle()
            if (i % 10 == 9) listener?.onStatus("loading", "Đang xử lý ảnh nhận diện ${i + 1}/${images.size}…")
        }
        Log.i(TAG, "Dựng database: $added/${targets.size} ảnh dùng được")
        if (added == 0) return null

        try {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            FileOutputStream(tmp).use { db.serialize(it) }
            cacheFile.parentFile?.listFiles { f -> f.name.startsWith("armobile_aug_") && f != tmp }?.forEach { it.delete() }
            tmp.renameTo(cacheFile)
        } catch (e: Exception) {
            Log.w(TAG, "Không lưu được cache imgdb (không ảnh hưởng chức năng)", e)
        }
        return db
    }

    private fun downloadAll(targets: List<TargetSpec>, gen: Int): List<Pair<String, Bitmap>> {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = targets.map { t ->
                pool.submit(Callable<Pair<String, Bitmap>?> {
                    if (gen != loadGeneration) null else loadTargetBitmap(t.url)?.let { t.name to it }
                })
            }
            val out = ArrayList<Pair<String, Bitmap>>()
            for ((i, f) in futures.withIndex()) {
                val r = try { f.get() } catch (e: Exception) { null }
                if (r != null) out.add(r)
                if (i % 10 == 9) listener?.onStatus("loading", "Đang tải ảnh nhận diện ${i + 1}/${targets.size}…")
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** Tải + giải mã + thu nhỏ ≤ MAX_TARGET_DIM + đè lên nền trắng (PNG trong suốt làm ARCore mất feature) */
    private fun loadTargetBitmap(source: String): Bitmap? {
        return try {
            val bytes = when {
                source.startsWith("http://") || source.startsWith("https://") -> {
                    val conn = URL(source).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.connect()
                    conn.inputStream.use { it.readBytes() }
                }
                source.startsWith("file://") -> File(source.removePrefix("file://")).readBytes()
                else -> context.assets.open(source).use { it.readBytes() } // fallback debug: tên asset cục bộ
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Không đọc được kích thước ảnh '$source'")
                return null
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_TARGET_DIM) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val k = MAX_TARGET_DIM.toFloat() / maxOf(raw.width, raw.height)
            val w = if (k < 1f) maxOf(1, (raw.width * k).toInt()) else raw.width
            val h = if (k < 1f) maxOf(1, (raw.height * k).toInt()) else raw.height
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(Bitmap.createScaledBitmap(raw, w, h, true), 0f, 0f, null)
            raw.recycle()
            flat
        } catch (e: Throwable) {
            Log.e(TAG, "Không tải được ảnh trigger '$source'", e)
            null
        }
    }

    // ---------------------------------------------------------------- vẽ mỗi frame

    private fun drawFrame() {
        val s = session ?: return
        if (!surfaceReady) return
        try {
            s.setCameraTextureName(backgroundRenderer.textureId)
            val frame: Frame = s.update()

            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            backgroundRenderer.updateTexCoords(frame)
            backgroundRenderer.draw()

            // Ảnh mới được thấy lần đầu xuất hiện trong "updated"; từ đó giữ tham chiếu và hỏi trạng thái mỗi frame
            // (ảnh chuyển LAST_KNOWN_POSE -> FULL_TRACKING có thể không nằm trong danh sách updated).
            // Không quét getAllTrackables vì database có thể tới 1000 ảnh.
            for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) knownImages[img.name] = img

            for ((name, img) in knownImages) {
                val state = img.trackingState
                val method = img.trackingMethod
                val key = state.ordinal * 8 + method.ordinal
                if (lastState[name] != key) {
                    lastState[name] = key
                    Log.i(TAG, "AugmentedImage $name: $state/$method")
                }
                val tracking = state == TrackingState.TRACKING && method == AugmentedImage.TrackingMethod.FULL_TRACKING
                if (tracking) {
                    val pose = img.centerPose
                    poses[name] = pose
                    if (trackedNames.add(name)) {
                        listener?.onAnchorFound(name)
                        if (name == activeTarget) overlay.setActive(true)
                    }
                    if (emitPose) {
                        listener?.onAnchorUpdated(
                            name,
                            pose.tx(), pose.ty(), pose.tz(),
                            pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                        )
                    }
                } else if (trackedNames.remove(name)) {
                    // PAUSED/STOPPED, hoặc TRACKING nhưng chỉ bằng extended tracking (LAST_KNOWN_POSE) -> coi như mất
                    poses.remove(name)
                    listener?.onAnchorRemoved(name)
                    if (name == activeTarget) overlay.setActive(false)
                }
            }

            val target = activeTarget
            val activePose = if (target != null) poses[target] else null
            if (activePose != null && overlay.hasItems()) {
                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)
                frame.camera.getViewMatrix(viewMatrix, 0)
                frame.camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
                overlay.draw(viewMatrix, projMatrix, activePose)
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
        } catch (e: Exception) {
            Log.e(TAG, "update() failed", e)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun onHostResume() {
        hostResumed = true
        overlay.resumeIfActive()
        try {
            session?.resume()
        } catch (e: Exception) {
            Log.e(TAG, "resume failed", e)
        }
        onResume()
    }

    fun onHostPause() {
        hostResumed = false
        overlay.pauseAll()
        onPause()
        session?.pause()
    }

    fun onHostDestroy() {
        loadGeneration++ // huỷ mọi việc tải/dựng đang chạy nền
        overlay.releaseAll()
        session?.close()
        session = null
    }

    /** Manager gọi khi React gỡ view */
    fun dispose() {
        onDispose?.invoke()
        onDispose = null
        onHostDestroy()
    }
}