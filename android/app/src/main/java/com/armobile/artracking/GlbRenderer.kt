package com.armobile.artracking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "GlbRenderer"

class GpuPrim(
    val vbo: Int,
    val ibo: Int,
    /** Số index (nếu ibo != 0) hoặc số đỉnh (vẽ tuần tự) */
    val count: Int,
    val indexType: Int,
    val nodeMatrix: FloatArray,
    val material: GlbMaterial,
    val textureId: Int,
)

class GlbGpuModel(val prims: List<GpuPrim>, val textureIds: IntArray) {
    /** Gọi trên GL thread */
    fun release() {
        for (p in prims) {
            if (p.vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(p.vbo), 0)
            if (p.ibo != 0) GLES20.glDeleteBuffers(1, intArrayOf(p.ibo), 0)
        }
        if (textureIds.isNotEmpty()) GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
    }
}

/**
 * Vẽ model GLB đã parse (GlbParser) bằng GLES2 — dùng chung GL context + cùng frame với camera
 * nên không có độ trễ giữa hình camera và model (khác với overlay Filament ở view riêng).
 * Shading: baseColor (factor × texture) + ánh sáng đơn giản (hemisphere + 1 đèn hướng cố định trong
 * không gian thế giới ARCore, trục Y hướng lên). KHÔNG phải PBR — kim loại sẽ trông "nhựa".
 */
class GlbRenderer {

    private var program = 0
    private var aPos = 0
    private var aNor = 0
    private var aUv = 0
    private var uMvp = 0
    private var uNormalMat = 0
    private var uTex = 0
    private var uUseTex = 0
    private var uBase = 0
    private var uEmissive = 0
    private var uAlphaMode = 0
    private var uAlphaCutoff = 0
    private var uUnlit = 0
    private var uDoubleSided = 0
    private var uLightDir = 0
    private var uintIndices = false

    companion object {
        private const val MAX_TEX_DIM = 2048

        private const val VS = """
            uniform mat4 u_MVP;
            uniform mat4 u_NormalMat;
            attribute vec3 a_Position;
            attribute vec3 a_Normal;
            attribute vec2 a_TexCoord;
            varying vec3 v_Normal;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_MVP * vec4(a_Position, 1.0);
                v_Normal = (u_NormalMat * vec4(a_Normal, 0.0)).xyz;
                v_TexCoord = a_TexCoord;
            }
        """
        private const val FS = """
            precision mediump float;
            uniform sampler2D u_Tex;
            uniform float u_UseTex;
            uniform vec4 u_Base;
            uniform vec3 u_Emissive;
            uniform float u_AlphaMode;   // 0 opaque, 1 mask, 2 blend
            uniform float u_AlphaCutoff;
            uniform float u_Unlit;
            uniform float u_DoubleSided;
            uniform vec3 u_LightDir;     // hướng TỚI nguồn sáng, không gian thế giới
            varying vec3 v_Normal;
            varying vec2 v_TexCoord;
            void main() {
                vec4 t = vec4(1.0);
                if (u_UseTex > 0.5) {
                    t = texture2D(u_Tex, v_TexCoord);
                    // Bitmap Android là premultiplied -> trả về straight alpha
                    if (t.a > 0.001) t.rgb /= t.a;
                }
                vec4 base = u_Base * t;
                if (u_AlphaMode < 0.5) {
                    base.a = 1.0;
                } else if (u_AlphaMode < 1.5) {
                    if (base.a < u_AlphaCutoff) discard;
                    base.a = 1.0;
                }
                vec3 color = base.rgb;
                if (u_Unlit < 0.5) {
                    vec3 n = normalize(v_Normal);
                    if (u_DoubleSided > 0.5 && !gl_FrontFacing) n = -n;
                    float ndl = max(dot(n, normalize(u_LightDir)), 0.0);
                    float hemi = 0.5 + 0.5 * n.y;
                    color = base.rgb * (0.35 + 0.25 * hemi + 0.65 * ndl) + u_Emissive;
                }
                gl_FragColor = vec4(color * base.a, base.a);   // premultiplied, khớp glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)
            }
        """

        /** Chạy ở thread nền: giải mã ảnh, thu nhỏ ≤ 2048 và đưa về kích thước lũy thừa 2 (GLES2 cần để REPEAT + mipmap) */
        fun decodeTexture(bytes: ByteArray): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > MAX_TEX_DIM * 2 || bounds.outHeight / sample > MAX_TEX_DIM * 2) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val w = nearestPow2(bmp.width)
                val h = nearestPow2(bmp.height)
                if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true).also { if (it !== bmp) bmp.recycle() }
            } catch (e: Throwable) {
                Log.e(TAG, "Không giải mã được texture", e)
                null
            }
        }

        private fun nearestPow2(v: Int): Int {
            var p = 1
            while (p * 2 <= v) p *= 2
            // chọn lũy thừa 2 gần nhất (làm tròn lên nếu v gần mức trên hơn), tối đa MAX_TEX_DIM
            val up = if (v - p > p * 2 - v) p * 2 else p
            return Math.min(up, MAX_TEX_DIM)
        }
    }

    fun createOnGlThread() {
        fun shader(type: Int, code: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, code)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader compile lỗi: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VS))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FS))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) Log.e(TAG, "Link program lỗi: ${GLES20.glGetProgramInfoLog(program)}")

        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        aNor = GLES20.glGetAttribLocation(program, "a_Normal")
        aUv = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "u_MVP")
        uNormalMat = GLES20.glGetUniformLocation(program, "u_NormalMat")
        uTex = GLES20.glGetUniformLocation(program, "u_Tex")
        uUseTex = GLES20.glGetUniformLocation(program, "u_UseTex")
        uBase = GLES20.glGetUniformLocation(program, "u_Base")
        uEmissive = GLES20.glGetUniformLocation(program, "u_Emissive")
        uAlphaMode = GLES20.glGetUniformLocation(program, "u_AlphaMode")
        uAlphaCutoff = GLES20.glGetUniformLocation(program, "u_AlphaCutoff")
        uUnlit = GLES20.glGetUniformLocation(program, "u_Unlit")
        uDoubleSided = GLES20.glGetUniformLocation(program, "u_DoubleSided")
        uLightDir = GLES20.glGetUniformLocation(program, "u_LightDir")
        uintIndices = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_OES_element_index_uint") == true
    }

    // ---------------------------------------------------------------- upload (GL thread)

    fun upload(data: GlbData, bitmaps: List<Bitmap?>): GlbGpuModel {
        val texIds = IntArray(data.textures.size)
        for (i in data.textures.indices) {
            val bmp = bitmaps.getOrNull(i)
            if (bmp != null) texIds[i] = uploadTexture(bmp, data.textures[i].wrapS, data.textures[i].wrapT)
        }
        val prims = ArrayList<GpuPrim>()
        for (p in data.primitives) {
            val mat = data.material(p.materialIndex)
            val tex = if (mat.baseColorTexture in texIds.indices) texIds[mat.baseColorTexture] else 0
            makeGpuPrim(p, mat, tex)?.let { prims.add(it) }
        }
        return GlbGpuModel(prims, texIds.filter { it != 0 }.toIntArray())
    }

    private fun makeGpuPrim(p: GlbPrimitive, mat: GlbMaterial, tex: Int): GpuPrim? {
        var vertices = p.vertices
        var indices = p.indices
        var useIbo = indices != null
        var type = GLES20.GL_UNSIGNED_SHORT
        var count: Int

        if (indices != null) {
            val tri = (indices.size / 3) * 3
            var maxIdx = 0
            for (i in 0 until tri) if (indices[i] > maxIdx) maxIdx = indices[i]
            if (maxIdx >= 65536 && !uintIndices) {
                // Thiết bị không có GL_OES_element_index_uint -> bung thành mảng đỉnh tuần tự
                val flat = FloatArray(tri * 8)
                for (i in 0 until tri) System.arraycopy(vertices, indices[i] * 8, flat, i * 8, 8)
                vertices = flat
                indices = null
                useIbo = false
                count = tri
            } else {
                if (maxIdx >= 65536) type = GLES20.GL_UNSIGNED_INT
                count = tri
            }
        } else {
            count = (p.vertexCount / 3) * 3
        }
        if (count == 0) return null

        val ids = IntArray(2)
        GLES20.glGenBuffers(if (useIbo) 2 else 1, ids, 0)
        val vb = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(vertices).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES20.GL_STATIC_DRAW)
        var ibo = 0
        if (useIbo && indices != null) {
            ibo = ids[1]
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
            if (type == GLES20.GL_UNSIGNED_SHORT) {
                val ib = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                for (i in 0 until count) ib.put(indices[i].toShort())
                ib.position(0)
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, count * 2, ib, GLES20.GL_STATIC_DRAW)
            } else {
                val ib = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
                for (i in 0 until count) ib.put(indices[i])
                ib.position(0)
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, count * 4, ib, GLES20.GL_STATIC_DRAW)
            }
        }
        // QUAN TRỌNG: các renderer khác (quad, cube, nền camera) dùng con trỏ client-side -> không được để buffer đang bind
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        return GpuPrim(ids[0], ibo, count, type, p.nodeMatrix, mat, tex)
    }

    private fun uploadTexture(bmp: Bitmap, wrapS: Int, wrapT: Int): Int {
        fun wrap(w: Int) = when (w) {
            33071 -> GLES20.GL_CLAMP_TO_EDGE
            33648 -> GLES20.GL_MIRRORED_REPEAT
            else -> GLES20.GL_REPEAT
        }
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap(wrapS))
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap(wrapT))
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return id[0]
    }

    // ---------------------------------------------------------------- draw (GL thread)

    /**
     * @param instance model matrix của cả model (pose × position × xoay × scale), column-major
     * @param vp projection × view
     */
    fun draw(model: GlbGpuModel, instance: FloatArray, vp: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLightDir, 0.35f, 1.0f, 0.3f)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Lượt 0: OPAQUE + MASK (ghi depth). Lượt 1: BLEND (không ghi depth, vẽ sau cùng)
        for (pass in 0..1) {
            if (pass == 1) GLES20.glDepthMask(false)
            for (p in model.prims) {
                if ((p.material.alphaMode == 2) != (pass == 1)) continue
                drawPrim(p, instance, vp)
            }
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glFrontFace(GLES20.GL_CCW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private val world = FloatArray(16)
    private val mvp = FloatArray(16)
    private val inv = FloatArray(16)
    private val normalMat = FloatArray(16)

    private fun drawPrim(p: GpuPrim, instance: FloatArray, vp: FloatArray) {
        Matrix.multiplyMM(world, 0, instance, 0, p.nodeMatrix, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, world, 0)
        if (!Matrix.invertM(inv, 0, world, 0)) return
        Matrix.transposeM(normalMat, 0, inv, 0)

        // Scale âm (lật gương) làm đảo chiều winding -> đảo front face để culling vẫn đúng
        val det = world[0] * (world[5] * world[10] - world[6] * world[9]) -
            world[1] * (world[4] * world[10] - world[6] * world[8]) +
            world[2] * (world[4] * world[9] - world[5] * world[8])
        GLES20.glFrontFace(if (det < 0f) GLES20.GL_CW else GLES20.GL_CCW)
        if (p.material.doubleSided) {
            GLES20.glDisable(GLES20.GL_CULL_FACE)
        } else {
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
        }

        val m = p.material
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uNormalMat, 1, false, normalMat, 0)
        GLES20.glUniform4f(uBase, m.baseColor[0], m.baseColor[1], m.baseColor[2], m.baseColor[3])
        GLES20.glUniform3f(uEmissive, m.emissive[0], m.emissive[1], m.emissive[2])
        GLES20.glUniform1f(uAlphaMode, m.alphaMode.toFloat())
        GLES20.glUniform1f(uAlphaCutoff, m.alphaCutoff)
        GLES20.glUniform1f(uUnlit, if (m.unlit) 1f else 0f)
        GLES20.glUniform1f(uDoubleSided, if (m.doubleSided) 1f else 0f)
        if (p.textureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.textureId)
            GLES20.glUniform1f(uUseTex, 1f)
        } else {
            GLES20.glUniform1f(uUseTex, 0f)
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 32, 24)

        if (p.ibo != 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.ibo)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.count, p.indexType, 0)
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.count)
        }

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNor)
        GLES20.glDisableVertexAttribArray(aUv)
    }
}
