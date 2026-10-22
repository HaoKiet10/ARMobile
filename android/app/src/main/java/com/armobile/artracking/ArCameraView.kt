package com.armobile.artracking

import android.content.Context
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
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

/** Database nhận diện dựng sẵn trên server (GET /public/trigger-db) */
class TriggerDbSpec(val version: String, val url: String, val sha256: String)

/**
 * View camera AR thuần, không qua react-viro. Tự nhận diện NHIỀU ảnh trigger cùng lúc:
 *  - `triggerDbJson`: {version, url, sha256} — database .imgdb do server dựng sẵn (name = projectId).
 *    Tải 1 lần, kiểm tra sha256 rồi mới lưu cache; lần mở app sau chỉ deserialize, không tải lại.
 *  - Mỗi ảnh có trạng thái tracking riêng; báo found/removed theo `name`.
 *  - `overlayJson`: {"target": name, "assets": [...]} — overlay của project đang "active", chỉ vẽ khi
 *    ảnh của target đó đang được track thật (FULL_TRACKING).
 */
class ArCameraView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    var listener: ArTrackingListener? = null
    /** Bắn event pose mỗi frame qua bridge — chỉ bật khi cần debug (60 event/giây/marker) */
    var emitPose: Boolean = false
    /** Gọi khi view bị gỡ (manager dùng để bỏ lifecycle listener) */
    var onDispose: (() -> Unit)? = null

    /** JSON {"version": "...", "url": "...", "sha256": "..."} từ GET /public/trigger-db */
    var triggerDbJson: String? = null
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
            restartTargets(triggerDbJson)
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

    private fun parseTriggerDb(json: String?): TriggerDbSpec? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val spec = TriggerDbSpec(o.optString("version"), o.optString("url"), o.optString("sha256"))
            if (spec.url.isEmpty() || spec.sha256.isEmpty()) null else spec
        } catch (e: Exception) {
            Log.e(TAG, "triggerDbJson không hợp lệ", e)
            null
        }
    }

    private fun restartTargets(json: String?) {
        loadGeneration++
        val gen = loadGeneration
        Log.i(TAG, "restartTargets view=${System.identityHashCode(this)} gen=$gen")
        queueEvent { releaseSession() }
        if (json == null) return
        val spec = parseTriggerDb(json)
        if (spec == null) {
            listener?.onStatus("error", "Không có database nhận diện để tải")
            return
        }
        Thread { prepareTargets(gen, spec, retried = false) }.start()
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
     * Thread nền: CHỈ tải file database (khi chưa có trong cache). Việc tạo Session + nạp database chạy ở GL
     * thread, nơi ARCore đã hoạt động ổn từ trước — tạo Session ở thread nền thuần làm ARCore ném FatalException.
     */
    private fun prepareTargets(gen: Int, spec: TriggerDbSpec, retried: Boolean) {
        try {
            val cacheFile = cacheFileFor(spec)
            // File cache chỉ xuất hiện sau khi đã tải đủ và khớp sha256 (xem downloadDatabase)
            if (!cacheFile.exists()) {
                listener?.onStatus("loading", "Đang tải database nhận diện…")
                downloadDatabase(spec, cacheFile)
            }
            if (gen != loadGeneration) return
            queueEvent { createSessionOnGl(gen, spec, cacheFile, retried) }
        } catch (e: Throwable) {
            fail("Không tải được database nhận diện: ${describe(e)}", e)
        }
    }

    /** GL thread */
    private fun createSessionOnGl(gen: Int, spec: TriggerDbSpec, cacheFile: File, retried: Boolean) {
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
            val db = loadCachedDatabase(s, cacheFile)
            if (gen != loadGeneration) return
            if (db == null || db.numImages == 0) {
                cacheFile.delete()
                if (db == null && !retried) {
                    // Cache không đọc được -> xoá, tải lại đúng 1 lần (không lặp vô hạn nếu file trên server hỏng)
                    Log.w(TAG, "Cache imgdb không đọc được, tải lại")
                    Thread { prepareTargets(gen, spec, retried = true) }.start()
                } else {
                    fail(if (db == null) "Database nhận diện không đọc được" else "Database nhận diện không có ảnh nào", null)
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

    /** Tên file theo sha256 nội dung: file khác nội dung không bao giờ trùng tên */
    private fun cacheFileFor(spec: TriggerDbSpec): File {
        val key = spec.sha256.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(32)
        return File(context.cacheDir, "armobile_trigger_$key.imgdb")
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

    /**
     * Tải về file tạm, kiểm tra HTTP 200 + sha256 rồi mới đổi tên thành [dest] — file trong cache luôn là
     * bản đầy đủ, đúng như server dựng. Tải lỗi/cụt/hỏng thì ném lỗi, không để lại gì.
     */
    private fun downloadDatabase(spec: TriggerDbSpec, dest: File) {
        // Tên tạm riêng cho mỗi lần tải: 2 view cùng tải không ghi đè/xoá file của nhau
        val tmp = File(dest.parentFile, "${dest.name}.${System.nanoTime()}.tmp")
        try {
            val conn = URL(spec.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (!sha.equals(spec.sha256, ignoreCase = true)) {
                    throw IOException("File tải về bị hỏng (sha256 không khớp, $total bytes)")
                }
            } finally {
                conn.disconnect()
            }
            if (!tmp.renameTo(dest) && !dest.exists()) throw IOException("Không lưu được database vào cache")
            // Chỉ giữ bản mới nhất (gồm cả cache armobile_aug_* của bản app cũ); không động vào file .tmp đang tải
            dest.parentFile?.listFiles { f ->
                f.name.startsWith("armobile_") && f.name.endsWith(".imgdb") && f != dest
            }?.forEach { it.delete() }
        } finally {
            tmp.delete()
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