package com.armobile.artracking

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser GLB (glTF 2.0 binary) tối giản, KHÔNG phụ thuộc OpenGL/Android bitmap để dễ test trên JVM.
 * Hỗ trợ: mesh tam giác tĩnh, node hierarchy (matrix hoặc TRS), vật liệu PBR (chỉ dùng baseColor +
 * emissiveFactor), alphaMode OPAQUE/MASK/BLEND, doubleSided, KHR_materials_unlit, texture nhúng
 * trong file (PNG/JPEG/WebP), sampler wrap. Component type float/int/short/byte (kể cả normalized).
 *
 * KHÔNG hỗ trợ (sẽ ghi vào warnings): nén Draco/meshopt, texture KTX2/Basis, sparse accessor,
 * animation, skinning (vẽ ở tư thế bind), buffer/ảnh ngoài file (uri), mode khác TRIANGLES.
 */
class GlbPrimitive(
    /** Xen kẽ mỗi đỉnh 8 float: px py pz nx ny nz u v */
    val vertices: FloatArray,
    val vertexCount: Int,
    /** null = vẽ tuần tự (không index) */
    val indices: IntArray?,
    /** Ma trận node → model, column-major 4x4 */
    val nodeMatrix: FloatArray,
    /** -1 = vật liệu mặc định (trắng) */
    val materialIndex: Int,
)

class GlbMaterial(
    val baseColor: FloatArray,
    val baseColorTexture: Int,
    val emissive: FloatArray,
    /** 0 = OPAQUE, 1 = MASK, 2 = BLEND */
    val alphaMode: Int,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
    val unlit: Boolean,
) {
    companion object {
        val DEFAULT = GlbMaterial(floatArrayOf(1f, 1f, 1f, 1f), -1, floatArrayOf(0f, 0f, 0f), 0, 0.5f, false, false)
    }
}

class GlbTexture(
    /** Byte ảnh gốc (PNG/JPEG/WebP) hoặc null nếu không dùng được */
    val image: ByteArray?,
    val wrapS: Int,
    val wrapT: Int,
)

class GlbData(
    val primitives: List<GlbPrimitive>,
    val materials: List<GlbMaterial>,
    val textures: List<GlbTexture>,
    val warnings: List<String>,
) {
    fun material(index: Int): GlbMaterial = materials.getOrNull(index) ?: GlbMaterial.DEFAULT

    /** AABB của toàn model trong đơn vị gốc của file: [minX, minY, minZ, maxX, maxY, maxZ] hoặc null nếu rỗng */
    fun bounds(): FloatArray? {
        var any = false
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in primitives) {
            val m = p.nodeMatrix
            for (i in 0 until p.vertexCount) {
                val x = p.vertices[i * 8]
                val y = p.vertices[i * 8 + 1]
                val z = p.vertices[i * 8 + 2]
                val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
                val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
                val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
                if (wx < b[0]) b[0] = wx
                if (wy < b[1]) b[1] = wy
                if (wz < b[2]) b[2] = wz
                if (wx > b[3]) b[3] = wx
                if (wy > b[4]) b[4] = wy
                if (wz > b[5]) b[5] = wz
                any = true
            }
        }
        return if (any) b else null
    }
}

object GlbParser {

    private const val MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    fun parse(bytes: ByteArray): GlbData {
        val warnings = ArrayList<String>()
        require(bytes.size >= 20) { "File quá ngắn, không phải GLB" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == MAGIC) { "Không phải file GLB (sai magic). File .gltf dạng text chưa được hỗ trợ" }
        require(bb.getInt(4) == 2) { "Chỉ hỗ trợ glTF 2.0" }

        var json: String? = null
        var binStart = -1
        var binLen = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val len = bb.getInt(offset)
            val type = bb.getInt(offset + 4)
            val start = offset + 8
            if (len < 0 || start + len > bytes.size) break
            if (type == CHUNK_JSON && json == null) {
                json = String(bytes, start, len, Charsets.UTF_8)
            } else if (type == CHUNK_BIN && binStart < 0) {
                binStart = start
                binLen = len
            }
            offset = start + ((len + 3) and 3.inv())
        }
        requireNotNull(json) { "GLB thiếu chunk JSON" }
        val root = JSONObject(json)

        val required = root.optJSONArray("extensionsRequired")
        if (required != null) {
            for (i in 0 until required.length()) {
                val ext = required.optString(i)
                when (ext) {
                    "KHR_draco_mesh_compression" -> warnings.add("Model nén Draco — chưa hỗ trợ")
                    "EXT_meshopt_compression" -> warnings.add("Model nén meshopt — chưa hỗ trợ")
                    "KHR_texture_basisu" -> warnings.add("Texture KTX2/Basis — chưa hỗ trợ, bỏ qua texture")
                    "KHR_mesh_quantization", "KHR_materials_unlit", "KHR_texture_transform",
                    "KHR_materials_emissive_strength", "EXT_texture_webp" -> Unit
                    else -> warnings.add("Extension bắt buộc chưa hỗ trợ: $ext")
                }
            }
        }
        if ((root.optJSONArray("animations")?.length() ?: 0) > 0) warnings.add("Model có animation — bỏ qua, vẽ tư thế tĩnh")
        if ((root.optJSONArray("skins")?.length() ?: 0) > 0) warnings.add("Model có skinning — vẽ ở tư thế bind, có thể sai tỉ lệ/tư thế")

        val reader = BinaryReader(root, bytes, binStart, binLen, warnings)
        val materials = parseMaterials(root, warnings)
        val textures = parseTextures(root, reader, warnings)
        val prims = ArrayList<GlbPrimitive>()

        val nodes = root.optJSONArray("nodes")
        val meshes = root.optJSONArray("meshes")
        if (nodes != null && meshes != null) {
            val rootNodes = sceneRootNodes(root, nodes)
            for (n in rootNodes) visitNode(n, identity(), 0, nodes, meshes, reader, prims, warnings)
        } else if (meshes != null) {
            // Không có node: vẽ mọi mesh với ma trận đơn vị
            for (m in 0 until meshes.length()) addMesh(meshes.getJSONObject(m), identity(), reader, prims, warnings)
        }
        if (prims.isEmpty()) warnings.add("Không có mesh nào vẽ được")
        return GlbData(prims, materials, textures, warnings)
    }

    // ---------------------------------------------------------------- scene graph

    private fun sceneRootNodes(root: JSONObject, nodes: JSONArray): List<Int> {
        val scenes = root.optJSONArray("scenes")
        val scene = scenes?.optJSONObject(root.optInt("scene", 0))
        val list = scene?.optJSONArray("nodes")
        if (list != null) return (0 until list.length()).map { list.getInt(it) }
        // Không khai báo scene: lấy các node không phải con của node nào
        val isChild = BooleanArray(nodes.length())
        for (i in 0 until nodes.length()) {
            val ch = nodes.getJSONObject(i).optJSONArray("children") ?: continue
            for (c in 0 until ch.length()) {
                val idx = ch.getInt(c)
                if (idx in isChild.indices) isChild[idx] = true
            }
        }
        return (0 until nodes.length()).filter { !isChild[it] }
    }

    private fun visitNode(
        index: Int, parent: FloatArray, depth: Int,
        nodes: JSONArray, meshes: JSONArray, reader: BinaryReader,
        out: MutableList<GlbPrimitive>, warnings: MutableList<String>,
    ) {
        if (depth > 64 || index !in 0 until nodes.length()) return
        val node = nodes.getJSONObject(index)
        val world = multiply(parent, localMatrix(node))
        if (node.has("mesh")) {
            val mi = node.getInt("mesh")
            if (mi in 0 until meshes.length()) addMesh(meshes.getJSONObject(mi), world, reader, out, warnings)
        }
        val ch = node.optJSONArray("children")
        if (ch != null) for (c in 0 until ch.length()) visitNode(ch.getInt(c), world, depth + 1, nodes, meshes, reader, out, warnings)
    }

    private fun localMatrix(node: JSONObject): FloatArray {
        val m = node.optJSONArray("matrix")
        if (m != null && m.length() == 16) return FloatArray(16) { m.getDouble(it).toFloat() }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.getDouble(0)?.toFloat() ?: 0f
        val ty = t?.getDouble(1)?.toFloat() ?: 0f
        val tz = t?.getDouble(2)?.toFloat() ?: 0f
        var qx = r?.getDouble(0)?.toFloat() ?: 0f
        var qy = r?.getDouble(1)?.toFloat() ?: 0f
        var qz = r?.getDouble(2)?.toFloat() ?: 0f
        var qw = r?.getDouble(3)?.toFloat() ?: 1f
        val sx = s?.getDouble(0)?.toFloat() ?: 1f
        val sy = s?.getDouble(1)?.toFloat() ?: 1f
        val sz = s?.getDouble(2)?.toFloat() ?: 1f
        val len = Math.sqrt((qx * qx + qy * qy + qz * qz + qw * qw).toDouble()).toFloat()
        if (len > 0f) { qx /= len; qy /= len; qz /= len; qw /= len }
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        return floatArrayOf(
            (1 - 2 * (yy + zz)) * sx, (2 * (xy + wz)) * sx, (2 * (xz - wy)) * sx, 0f,
            (2 * (xy - wz)) * sy, (1 - 2 * (xx + zz)) * sy, (2 * (yz + wx)) * sy, 0f,
            (2 * (xz + wy)) * sz, (2 * (yz - wx)) * sz, (1 - 2 * (xx + yy)) * sz, 0f,
            tx, ty, tz, 1f,
        )
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** out = a * b (column-major) */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = sum
        }
        return o
    }

    // ---------------------------------------------------------------- mesh

    private fun addMesh(
        mesh: JSONObject, world: FloatArray, reader: BinaryReader,
        out: MutableList<GlbPrimitive>, warnings: MutableList<String>,
    ) {
        val prims = mesh.optJSONArray("primitives") ?: return
        for (pi in 0 until prims.length()) {
            val prim = prims.getJSONObject(pi)
            if (prim.optInt("mode", 4) != 4) { warnings.add("Primitive không phải TRIANGLES (mode=${prim.optInt("mode")}) — bỏ qua"); continue }
            if (prim.optJSONObject("extensions")?.has("KHR_draco_mesh_compression") == true) {
                warnings.add("Primitive nén Draco — bỏ qua"); continue
            }
            val attrs = prim.optJSONObject("attributes") ?: continue
            if (!attrs.has("POSITION")) continue
            val pos = reader.readFloats(attrs.getInt("POSITION"), 3) ?: continue
            val n = pos.size / 3
            if (n == 0) continue
            var nor = if (attrs.has("NORMAL")) reader.readFloats(attrs.getInt("NORMAL"), 3) else null
            val uv = if (attrs.has("TEXCOORD_0")) reader.readFloats(attrs.getInt("TEXCOORD_0"), 2) else null
            val idx = if (prim.has("indices")) reader.readIndices(prim.getInt("indices")) else null
            if (prim.has("indices") && idx == null) continue
            if (idx != null) {
                var bad = false
                for (v in idx) if (v < 0 || v >= n) { bad = true; break }
                if (bad) { warnings.add("Index vượt quá số đỉnh — bỏ qua primitive"); continue }
            }
            if (nor == null || nor.size != n * 3) nor = computeNormals(pos, n, idx)
            val verts = FloatArray(n * 8)
            for (i in 0 until n) {
                verts[i * 8] = pos[i * 3]; verts[i * 8 + 1] = pos[i * 3 + 1]; verts[i * 8 + 2] = pos[i * 3 + 2]
                verts[i * 8 + 3] = nor[i * 3]; verts[i * 8 + 4] = nor[i * 3 + 1]; verts[i * 8 + 5] = nor[i * 3 + 2]
                if (uv != null && uv.size >= (i + 1) * 2) { verts[i * 8 + 6] = uv[i * 2]; verts[i * 8 + 7] = uv[i * 2 + 1] }
            }
            out.add(GlbPrimitive(verts, n, idx, world, prim.optInt("material", -1)))
        }
    }

    private fun computeNormals(pos: FloatArray, n: Int, idx: IntArray?): FloatArray {
        val nor = FloatArray(n * 3)
        val triCount = (idx?.size ?: n) / 3
        for (t in 0 until triCount) {
            val a = idx?.get(t * 3) ?: (t * 3)
            val b = idx?.get(t * 3 + 1) ?: (t * 3 + 1)
            val c = idx?.get(t * 3 + 2) ?: (t * 3 + 2)
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { nor[v * 3] += nx; nor[v * 3 + 1] += ny; nor[v * 3 + 2] += nz }
        }
        for (i in 0 until n) {
            val l = Math.sqrt((nor[i * 3] * nor[i * 3] + nor[i * 3 + 1] * nor[i * 3 + 1] + nor[i * 3 + 2] * nor[i * 3 + 2]).toDouble()).toFloat()
            if (l > 1e-12f) { nor[i * 3] /= l; nor[i * 3 + 1] /= l; nor[i * 3 + 2] /= l } else nor[i * 3 + 1] = 1f
        }
        return nor
    }

    // ---------------------------------------------------------------- materials / textures

    private fun parseMaterials(root: JSONObject, warnings: MutableList<String>): List<GlbMaterial> {
        val arr = root.optJSONArray("materials") ?: return emptyList()
        val list = ArrayList<GlbMaterial>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val pbr = m.optJSONObject("pbrMetallicRoughness")
            val bcArr = pbr?.optJSONArray("baseColorFactor")
            val base = floatArrayOf(
                bcArr?.optDouble(0, 1.0)?.toFloat() ?: 1f, bcArr?.optDouble(1, 1.0)?.toFloat() ?: 1f,
                bcArr?.optDouble(2, 1.0)?.toFloat() ?: 1f, bcArr?.optDouble(3, 1.0)?.toFloat() ?: 1f,
            )
            val bct = pbr?.optJSONObject("baseColorTexture")
            val texIndex = bct?.optInt("index", -1) ?: -1
            if (bct != null && bct.optInt("texCoord", 0) != 0) warnings.add("Vật liệu $i dùng TEXCOORD_1 — chưa hỗ trợ")
            val efArr = m.optJSONArray("emissiveFactor")
            val emissive = floatArrayOf(
                efArr?.optDouble(0, 0.0)?.toFloat() ?: 0f, efArr?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                efArr?.optDouble(2, 0.0)?.toFloat() ?: 0f,
            )
            val mode = when (m.optString("alphaMode", "OPAQUE")) { "MASK" -> 1; "BLEND" -> 2; else -> 0 }
            list.add(
                GlbMaterial(
                    base, texIndex, emissive, mode, m.optDouble("alphaCutoff", 0.5).toFloat(),
                    m.optBoolean("doubleSided", false),
                    m.optJSONObject("extensions")?.has("KHR_materials_unlit") == true,
                )
            )
        }
        return list
    }

    private fun parseTextures(root: JSONObject, reader: BinaryReader, warnings: MutableList<String>): List<GlbTexture> {
        val texArr = root.optJSONArray("textures") ?: return emptyList()
        val images = root.optJSONArray("images")
        val samplers = root.optJSONArray("samplers")
        val list = ArrayList<GlbTexture>()
        for (i in 0 until texArr.length()) {
            val t = texArr.getJSONObject(i)
            var source = if (t.has("source")) t.getInt("source") else -1
            if (source < 0) {
                val ext = t.optJSONObject("extensions")
                if (ext?.has("EXT_texture_webp") == true) source = ext.getJSONObject("EXT_texture_webp").optInt("source", -1)
            }
            val sampler = if (t.has("sampler")) samplers?.optJSONObject(t.getInt("sampler")) else null
            val wrapS = sampler?.optInt("wrapS", 10497) ?: 10497
            val wrapT = sampler?.optInt("wrapT", 10497) ?: 10497
            var data: ByteArray? = null
            val img = if (source >= 0) images?.optJSONObject(source) else null
            if (img != null) {
                if (img.has("bufferView")) data = reader.readBufferViewBytes(img.getInt("bufferView"))
                else warnings.add("Ảnh texture $i dùng uri ngoài file — chưa hỗ trợ")
            }
            list.add(GlbTexture(data, wrapS, wrapT))
        }
        return list
    }

    // ---------------------------------------------------------------- binary access

    private class BinaryReader(
        val root: JSONObject, val bytes: ByteArray, val binStart: Int, val binLen: Int,
        val warnings: MutableList<String>,
    ) {
        private val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val bufferViews: JSONArray? = root.optJSONArray("bufferViews")
        private val accessors: JSONArray? = root.optJSONArray("accessors")
        private var warnedBuffer = false

        private fun compSize(ct: Int) = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> 0 }
        private fun numComps(type: String) = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 0
        }

        /** Trả về [start, length, stride] tuyệt đối trong `bytes` của bufferView, hoặc null nếu không đọc được */
        private fun viewRange(bvIndex: Int): IntArray? {
            val bv = bufferViews?.optJSONObject(bvIndex) ?: return null
            if (bv.optInt("buffer", 0) != 0 || binStart < 0) {
                if (!warnedBuffer) { warnings.add("Buffer ngoài file hoặc thiếu BIN chunk — chưa hỗ trợ"); warnedBuffer = true }
                return null
            }
            if (bv.optJSONObject("extensions")?.has("EXT_meshopt_compression") == true) {
                warnings.add("bufferView nén meshopt — chưa hỗ trợ"); return null
            }
            val start = binStart + bv.optInt("byteOffset", 0)
            val len = bv.getInt("byteLength")
            if (start < binStart || start + len > binStart + binLen) { warnings.add("bufferView vượt quá BIN chunk"); return null }
            return intArrayOf(start, len, bv.optInt("byteStride", 0))
        }

        fun readBufferViewBytes(bvIndex: Int): ByteArray? {
            val r = viewRange(bvIndex) ?: return null
            return bytes.copyOfRange(r[0], r[0] + r[1])
        }

        private fun readValue(pos: Int, ct: Int, normalized: Boolean): Float = when (ct) {
            5126 -> bb.getFloat(pos)
            5120 -> bb.get(pos).toFloat().let { if (normalized) Math.max(it / 127f, -1f) else it }
            5121 -> (bb.get(pos).toInt() and 0xFF).toFloat().let { if (normalized) it / 255f else it }
            5122 -> bb.getShort(pos).toFloat().let { if (normalized) Math.max(it / 32767f, -1f) else it }
            5123 -> (bb.getShort(pos).toInt() and 0xFFFF).toFloat().let { if (normalized) it / 65535f else it }
            5125 -> (bb.getInt(pos).toLong() and 0xFFFFFFFFL).toFloat()
            else -> 0f
        }

        fun readFloats(accIdx: Int, expectComps: Int): FloatArray? {
            val acc = accessors?.optJSONObject(accIdx) ?: return null
            val count = acc.getInt("count")
            val ct = acc.getInt("componentType")
            val comps = numComps(acc.getString("type"))
            val cs = compSize(ct)
            if (comps != expectComps || cs == 0) { warnings.add("Accessor $accIdx sai kiểu (cần $expectComps thành phần)"); return null }
            val out = FloatArray(count * comps)
            if (!acc.has("bufferView")) return out
            if (acc.has("sparse")) warnings.add("Accessor $accIdx dùng sparse — bỏ qua phần sparse")
            val r = viewRange(acc.getInt("bufferView")) ?: return null
            val stride = if (r[2] > 0) r[2] else comps * cs
            val base = r[0] + acc.optInt("byteOffset", 0)
            if (count > 0 && base + (count - 1) * stride + comps * cs > r[0] + r[1]) { warnings.add("Accessor $accIdx vượt quá bufferView"); return null }
            val normalized = acc.optBoolean("normalized", false)
            for (i in 0 until count) for (c in 0 until comps) out[i * comps + c] = readValue(base + i * stride + c * cs, ct, normalized)
            return out
        }

        fun readIndices(accIdx: Int): IntArray? {
            val acc = accessors?.optJSONObject(accIdx) ?: return null
            val count = acc.getInt("count")
            val ct = acc.getInt("componentType")
            if (ct != 5121 && ct != 5123 && ct != 5125) { warnings.add("Index componentType $ct không hợp lệ"); return null }
            if (!acc.has("bufferView")) return IntArray(count)
            val r = viewRange(acc.getInt("bufferView")) ?: return null
            val cs = compSize(ct)
            val stride = if (r[2] > 0) r[2] else cs
            val base = r[0] + acc.optInt("byteOffset", 0)
            if (count > 0 && base + (count - 1) * stride + cs > r[0] + r[1]) { warnings.add("Index accessor vượt quá bufferView"); return null }
            val out = IntArray(count)
            for (i in 0 until count) {
                val p = base + i * stride
                out[i] = when (ct) {
                    5121 -> bb.get(p).toInt() and 0xFF
                    5123 -> bb.getShort(p).toInt() and 0xFFFF
                    else -> bb.getInt(p)
                }
            }
            return out
        }
    }
}
