package com.armobile.artracking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.google.ar.core.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "OverlayRenderer"

/**
 * Mô tả 1 asset overlay. position/scale đã quy đổi sang MÉT ở phía JS (tương đối × marker width),
 * rotation là độ. scale.x = bề rộng quad (mét); chiều cao tự theo tỉ lệ ảnh/video × scale.y.
 */
class OverlaySpec(
    val id: String,
    val url: String,
    val kind: String, // "image" | "video"
    val position: FloatArray,
    val rotationDeg: FloatArray,
    val scale: FloatArray,
)

fun parseOverlaySpecs(json: String?): List<OverlaySpec> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        fun vec(o: JSONObject, k: String): FloatArray {
            val a = o.getJSONArray(k)
            return floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
        }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OverlaySpec(
                o.getString("id"), o.getString("url"), o.getString("kind"),
                vec(o, "position"), vec(o, "rotation"), vec(o, "scale"),
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "assetsJson không hợp lệ", e)
        emptyList()
    }
}

/** Cấu hình overlay của 1 target: {"target": "<tên ảnh trigger>", "assets": [...]} */
class OverlayConfig(val target: String?, val specs: List<OverlaySpec>)

fun parseOverlayConfig(json: String?): OverlayConfig {
    if (json.isNullOrBlank()) return OverlayConfig(null, emptyList())
    return try {
        val o = JSONObject(json)
        val target = o.optString("target").ifEmpty { null }
        OverlayConfig(target, parseOverlaySpecs(o.optJSONArray("assets")?.toString()))
    } catch (e: Exception) {
        Log.e(TAG, "overlayJson không hợp lệ", e)
        OverlayConfig(null, emptyList())
    }
}

/**
 * Vẽ ảnh + video lên marker bằng quad có texture (GLES2), dùng chung GL context với ArCameraView.
 * Video dùng MediaPlayer -> SurfaceTexture -> external OES texture (không cần thêm dependency).
 * Hỗ trợ image, video và model 3D .glb (GlbRenderer).
 */
class OverlayRenderer(private val requestGl: (Runnable) -> Unit) {

    private class Item(val spec: OverlaySpec) {
        var textureId = 0
        var external = false
        @Volatile var aspect = 1f // width / height
        @Volatile var ready = false
        @Volatile var released = false
        @Volatile var prepared = false
        var hasFrame = false
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        @Volatile var player: MediaPlayer? = null
        @Volatile var model: GlbGpuModel? = null
        val frameAvailable = AtomicBoolean(false)
        val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }

    private class Prog(val id: Int) {
        val position = GLES20.glGetAttribLocation(id, "a_Position")
        val texCoord = GLES20.glGetAttribLocation(id, "a_TexCoord")
        val mvp = GLES20.glGetUniformLocation(id, "u_MVP")
        val texMatrix = GLES20.glGetUniformLocation(id, "u_TexMatrix")
        val sampler = GLES20.glGetUniformLocation(id, "u_Texture")
    }

    private val items = CopyOnWriteArrayList<Item>()
    @Volatile private var active = false
    private var flatProg: Prog? = null
    private var oesProg: Prog? = null
    private val glb = GlbRenderer()

    // Quad đơn vị 1x1, tâm gốc, nằm trong mặt XY. (0,0)=góc dưới-trái của ảnh.
    private val quad: FloatBuffer = directBuffer(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
    private val quadTex: FloatBuffer = directBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private fun directBuffer(d: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(d); position(0) }

    fun hasItems() = items.isNotEmpty()

    companion object {
        private const val VS = """
            uniform mat4 u_MVP;
            uniform mat4 u_TexMatrix;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_MVP * a_Position;
                v_TexCoord = (u_TexMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FS_2D = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_Texture;
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
        """
        private const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
        """
    }

    fun createOnGlThread() {
        flatProg = Prog(buildProgram(VS, FS_2D))
        oesProg = Prog(buildProgram(VS, FS_OES))
        glb.createOnGlThread()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun shader(type: Int, code: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, code)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader compile lỗi: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    /** Gọi từ main thread khi prop assetsJson đổi. Thay toàn bộ overlay hiện có. */
    fun setAssets(specs: List<OverlaySpec>) {
        val old = items.toList()
        items.clear()
        requestGl(Runnable { old.forEach { releaseItem(it, deleteGl = true) } })
        for (spec in specs) {
            when (spec.kind) {
                "image" -> startImage(Item(spec).also { items.add(it) })
                "video" -> startVideo(Item(spec).also { items.add(it) })
                "model" -> startModel(Item(spec).also { items.add(it) })
                else -> Log.w(TAG, "Bỏ qua asset '${spec.id}' kind=${spec.kind} (chưa hỗ trợ)")
            }
        }
    }

    // ---------------- Ảnh ----------------

    private fun loadBitmap(source: String): Bitmap? = try {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            val conn = URL(source).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.connect()
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } else {
            BitmapFactory.decodeFile(source.removePrefix("file://"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Không tải được ảnh '$source'", e)
        null
    }

    private fun startImage(item: Item) {
        Thread {
            var bmp = loadBitmap(item.spec.url)
            if (bmp == null || item.released) return@Thread
            val maxDim = 2048
            if (bmp.width > maxDim || bmp.height > maxDim) {
                val k = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            }
            val b = bmp!!
            requestGl(Runnable {
                if (item.released) { b.recycle(); return@Runnable }
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                item.textureId = tex[0]
                item.aspect = b.width.toFloat() / b.height
                // Bitmap upload dòng đầu = trên cùng (t=0), quad của mình (0,0)=dưới-trái -> lật v
                Matrix.setIdentityM(item.texMatrix, 0)
                item.texMatrix[5] = -1f
                item.texMatrix[13] = 1f
                item.hasFrame = true
                item.ready = true
                b.recycle()
                Log.i(TAG, "Ảnh '${item.spec.id}' sẵn sàng (${item.aspect})")
            })
        }.start()
    }

    // ---------------- Model 3D (.glb) ----------------

    private fun loadBytes(source: String): ByteArray? = try {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            val conn = URL(source).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.connect()
            conn.inputStream.use { it.readBytes() }
        } else {
            java.io.File(source.removePrefix("file://")).readBytes()
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Không tải được model '$source'", e)
        null
    }

    private fun startModel(item: Item) {
        Thread {
            val bytes = loadBytes(item.spec.url)
            if (bytes == null || item.released) return@Thread
            val data = try {
                GlbParser.parse(bytes)
            } catch (e: Throwable) {
                Log.e(TAG, "Không đọc được model '${item.spec.url}'", e)
                return@Thread
            }
            data.warnings.forEach { Log.w(TAG, "Model '${item.spec.id}': $it") }
            if (data.primitives.isEmpty()) return@Thread
            data.bounds()?.let {
                Log.i(TAG, "Model '${item.spec.id}': ${data.primitives.size} mesh, kích thước gốc " +
                    "%.3f x %.3f x %.3f (đơn vị file)".format(it[3] - it[0], it[4] - it[1], it[5] - it[2]))
            }
            val bitmaps = data.textures.map { t -> t.image?.let { GlbRenderer.decodeTexture(it) } }
            if (item.released) return@Thread
            requestGl(Runnable {
                if (item.released) { bitmaps.forEach { it?.recycle() }; return@Runnable }
                item.model = glb.upload(data, bitmaps)
                item.hasFrame = true
                item.ready = true
                Log.i(TAG, "Model '${item.spec.id}' sẵn sàng")
            })
        }.start()
    }

    // ---------------- Video ----------------

    private fun startVideo(item: Item) {
        requestGl(Runnable {
            if (item.released) return@Runnable
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            item.textureId = tex[0]
            item.external = true
            val st = SurfaceTexture(tex[0])
            st.setOnFrameAvailableListener { item.frameAvailable.set(true) }
            item.surfaceTexture = st
            val surface = Surface(st)
            item.surface = surface

            // setDataSource(url) có thể chặn mạng -> chạy ở thread nền
            Thread {
                try {
                    val mp = MediaPlayer()
                    item.player = mp
                    mp.setSurface(surface)
                    mp.setDataSource(item.spec.url)
                    mp.isLooping = true
                    mp.setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) {
                            item.aspect = w.toFloat() / h
                            item.ready = true
                        }
                    }
                    mp.setOnPreparedListener {
                        item.prepared = true
                        if (active && !item.released) it.start()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "Video '${item.spec.id}' lỗi what=$what extra=$extra")
                        true
                    }
                    mp.prepareAsync()
                    if (item.released) mp.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Không mở được video '${item.spec.url}'", e)
                }
            }.start()
        })
    }

    // ---------------- Điều khiển ----------------

    /** Marker đang được track thật -> phát video; mất track -> tạm dừng. */
    fun setActive(a: Boolean) {
        active = a
        for (i in items) {
            if (i.spec.kind != "video" || !i.prepared) continue
            try { if (a) i.player?.start() else i.player?.pause() } catch (_: IllegalStateException) {}
        }
    }

    fun pauseAll() {
        for (i in items) if (i.spec.kind == "video" && i.prepared) {
            try { i.player?.pause() } catch (_: IllegalStateException) {}
        }
    }

    fun resumeIfActive() { if (active) setActive(true) }

    fun releaseAll() {
        val old = items.toList()
        items.clear()
        old.forEach { releaseItem(it, deleteGl = false) }
    }

    private fun releaseItem(item: Item, deleteGl: Boolean) {
        item.released = true
        item.ready = false
        try { item.player?.release() } catch (_: Exception) {}
        item.player = null
        item.surface?.release()
        item.surfaceTexture?.release()
        if (deleteGl) {
            item.model?.release()
            item.model = null
        }
        if (deleteGl && item.textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(item.textureId), 0)
    }

    // ---------------- Vẽ ----------------

    fun draw(view: FloatArray, proj: FloatArray, pose: Pose) {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val poseM = FloatArray(16)
        pose.toMatrix(poseM, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // bitmap upload dạng premultiplied

        for (item in items) {
            if (!item.ready || item.released) continue
            if (item.external && item.frameAvailable.getAndSet(false)) {
                try {
                    item.surfaceTexture?.updateTexImage()
                    item.surfaceTexture?.getTransformMatrix(item.texMatrix)
                    item.hasFrame = true
                } catch (e: Exception) {
                    Log.e(TAG, "updateTexImage lỗi", e)
                }
            }
            if (!item.hasFrame) continue

            // Editor của designer: marker là mặt phẳng XY (x phải, y lên, z ra khỏi mặt phẳng), quad
            // mặc định quay mặt về +Z. ARCore: marker nằm trong mặt XZ (x phải, y ra khỏi mặt, z xuống dưới).
            // C = xoay -90° quanh X đổi hệ editor -> hệ marker: (x, y, z) -> (x, z, -y).
            // model = pose * T(position đã đổi hệ ở JS) * C * R_editor * S
            // A-Frame áp rotation theo Euler 'YXZ' (aframe/src/components/rotation.js) -> R = Ry * Rx * Rz,
            // tức gọi rotate theo thứ tự y, x, z.
            val model = poseM.copyOf()
            val s = item.spec
            Matrix.translateM(model, 0, s.position[0], s.position[1], s.position[2])
            Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, s.rotationDeg[1], 0f, 1f, 0f)
            Matrix.rotateM(model, 0, s.rotationDeg[0], 1f, 0f, 0f)
            Matrix.rotateM(model, 0, s.rotationDeg[2], 0f, 0f, 1f)

            if (s.kind == "model") {
                // scale (x, y, z) = tỉ lệ editor × marker width, áp lên kích thước gốc của file (đơn vị mét theo glTF)
                val glbModel = item.model ?: continue
                Matrix.scaleM(model, 0, s.scale[0], s.scale[1], s.scale[2])
                glb.draw(glbModel, model, vp)
                continue
            }

            val prog = (if (item.external) oesProg else flatProg) ?: continue
            val target = if (item.external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            Matrix.scaleM(model, 0, s.scale[0], s.scale[1] / item.aspect, s.scale[2])
            val mvp = FloatArray(16)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            GLES20.glUseProgram(prog.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, item.textureId)
            GLES20.glUniform1i(prog.sampler, 0)
            GLES20.glUniformMatrix4fv(prog.mvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(prog.texMatrix, 1, false, item.texMatrix, 0)

            quad.position(0)
            GLES20.glVertexAttribPointer(prog.position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(prog.position)
            quadTex.position(0)
            GLES20.glVertexAttribPointer(prog.texCoord, 2, GLES20.GL_FLOAT, false, 0, quadTex)
            GLES20.glEnableVertexAttribArray(prog.texCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(prog.position)
            GLES20.glDisableVertexAttribArray(prog.texCoord)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
