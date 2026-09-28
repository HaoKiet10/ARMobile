package com.armobile.artracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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
import java.net.HttpURLConnection
import java.net.URL
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
}

private const val TAG = "ArCameraView"

/**
 * View camera AR thuần (port từ VBTest), không qua react-viro. Khác VBTest ở chỗ
 * ảnh trigger KHÔNG bundle sẵn trong assets — mà tải động theo `targetImageUrl`
 * (project.triggerImageUrl từ backend, khác nhau theo từng project). Việc tải
 * ảnh chạy đồng bộ trên GL thread lúc setup session — CHẤP NHẬN ĐƯỢC cho MVP vì
 * GL thread không phải main thread, nhưng nên chuyển sang async + loading state
 * ở bước polish sau (TODO).
 */
class ArCameraView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    var listener: ArTrackingListener? = null
    /** URL công khai của trigger image (http/https), hoặc file://, hoặc tên asset cục bộ (fallback debug) */
    var targetImageUrl: String? = null
    var targetName: String = "target"
    var physicalWidthMeters: Float = 0.15f

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val cubeRenderer = CubeRenderer()
    private var trackingActive = false
    private var surfaceReady = false
    private var latestPose: Pose? = null
    /** Kích thước cube overlay placeholder — sẽ thay bằng model thật (Filament) sau khi validate flow xong */
    var overlayBoxSizeMeters: Float = 0.05f

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                backgroundRenderer.createOnGlThread()
                cubeRenderer.createOnGlThread()
                surfaceReady = true
                setupSessionIfNeeded()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
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

    private var installRequested = false

    /** Tải bitmap trigger image: http(s) URL, file:// local path, hoặc fallback tên asset bundle */
    private fun loadTargetBitmap(source: String): Bitmap? {
        return try {
            when {
                source.startsWith("http://") || source.startsWith("https://") -> {
                    val conn = URL(source).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.doInput = true
                    conn.connect()
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }
                source.startsWith("file://") -> {
                    BitmapFactory.decodeFile(source.removePrefix("file://"))
                }
                else -> {
                    // fallback: coi như tên file trong android/app/src/main/assets (debug/test)
                    context.assets.open(source).use { BitmapFactory.decodeStream(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không tải được target image từ '$source'", e)
            null
        }
    }

    @Synchronized
    private fun setupSessionIfNeeded() {
        if (session != null) return
        try {
            val activity = context as? android.app.Activity
            if (activity != null) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        // Đang mở dialog cài/update "Google Play Services for AR" —
                        // installRequested=true để lần gọi lại sau không hỏi lại vô hạn.
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
            }

            val s = Session(context)
            val config = Config(s).apply {
                focusMode = Config.FocusMode.AUTO
                val db = AugmentedImageDatabase(s)
                targetImageUrl?.let { source ->
                    val bmp = loadTargetBitmap(source)
                    if (bmp == null) {
                        Log.e(TAG, "Không decode được target image '$source' — bỏ qua augmented image tracking")
                    } else {
                        Log.i(TAG, "Decoded trigger image: ${bmp.width}x${bmp.height}px, physicalWidth=$physicalWidthMeters m")
                        val index = db.addImage(targetName, bmp, physicalWidthMeters)
                        Log.i(TAG, "Đã add '$targetName' vào AugmentedImageDatabase, index=$index")
                    }
                }
                augmentedImageDatabase = db
            }
            s.configure(config)
            s.setCameraTextureName(backgroundRenderer.textureId)
            session = s
        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.e(TAG, "ARCore chưa được cài trên máy này", e)
        } catch (e: UnavailableUserDeclinedInstallationException) {
            Log.e(TAG, "User từ chối cài ARCore", e)
        } catch (e: UnavailableApkTooOldException) {
            Log.e(TAG, "ARCore APK quá cũ, cần update", e)
        } catch (e: UnavailableSdkTooOldException) {
            Log.e(TAG, "App build bằng ARCore SDK quá cũ so với thiết bị", e)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.e(TAG, "Thiết bị này KHÔNG hỗ trợ ARCore", e)
        } catch (e: Exception) {
            Log.e(TAG, "Session init failed", e)
        }
    }

    private fun drawFrame() {
        val s = session ?: return
        if (!surfaceReady) return
        try {
            s.setCameraTextureName(backgroundRenderer.textureId)
            val frame: Frame = s.update()

            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            backgroundRenderer.updateTexCoords(frame)
            backgroundRenderer.draw()

            val updatedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
            for (img in updatedImages) {
                val isReallyTracking =
                    img.trackingState == TrackingState.TRACKING &&
                        img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING

                when {
                    isReallyTracking -> {
                        if (!trackingActive) {
                            trackingActive = true
                            listener?.onAnchorFound(targetName)
                        }
                        val pose = img.centerPose
                        latestPose = pose
                        listener?.onAnchorUpdated(
                            targetName,
                            pose.tx(), pose.ty(), pose.tz(),
                            pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                        )
                    }
                    else -> {
                        // TrackingState.PAUSED/STOPPED, hoặc TRACKING nhưng chỉ bằng
                        // extended tracking (LAST_KNOWN_POSE) -> coi như đã mất track.
                        if (trackingActive) {
                            trackingActive = false
                            latestPose = null
                            listener?.onAnchorRemoved(targetName)
                        }
                    }
                }
            }

            // Placeholder overlay (cube đỏ) — sẽ thay bằng Filament render layer thật sau.
            val pose = latestPose
            if (trackingActive && pose != null) {
                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)
                frame.camera.getViewMatrix(viewMatrix, 0)
                frame.camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)

                val modelMatrix = FloatArray(16)
                pose.toMatrix(modelMatrix, 0)
                Matrix.scaleM(modelMatrix, 0, overlayBoxSizeMeters, overlayBoxSizeMeters, overlayBoxSizeMeters)

                val vpMatrix = FloatArray(16)
                Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
                val mvpMatrix = FloatArray(16)
                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

                cubeRenderer.draw(mvpMatrix)
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
        } catch (e: Exception) {
            Log.e(TAG, "update() failed", e)
        }
    }

    fun onHostResume() {
        setupSessionIfNeeded()
        try {
            session?.resume()
        } catch (e: Exception) {
            Log.e(TAG, "resume failed", e)
        }
        onResume()
    }

    fun onHostPause() {
        onPause()
        session?.pause()
    }

    fun onHostDestroy() {
        session?.close()
        session = null
    }
}
