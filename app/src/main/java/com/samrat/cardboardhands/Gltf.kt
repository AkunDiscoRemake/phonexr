package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A skinned glTF 2.0 binary (.glb): nodes, one skin, triangle meshes with base-colour textures.
 * Enough for a character exported from Blender, Mixamo, Avaturn, VRoid (VRM) or Sketchfab.
 */
class Gltf private constructor(json: JSONObject, private val bin: ByteBuffer) {
    class Node(
        val name: String,
        val children: IntArray,
        val mesh: Int,
        /** Rest pose: translation, rotation (x, y, z, w), scale. */
        val translation: FloatArray,
        val rotation: FloatArray,
        val scale: FloatArray,
        /** A node given as a matrix keeps it here instead of TRS. */
        val matrix: FloatArray?,
    ) {
        var parent = -1
    }

    class Primitive(
        val positions: FloatArray,
        val normals: FloatArray,
        val uvs: FloatArray,
        /** Four joints and four weights per vertex; empty for a rigid mesh. */
        val joints: IntArray,
        val weights: FloatArray,
        val indices: IntArray,
        /** Index into [images], or -1 for a flat colour. */
        val image: Int,
        val color: FloatArray,
    ) {
        val vertexCount get() = positions.size / 3
    }

    val nodes: List<Node>
    val roots: IntArray
    val primitives = ArrayList<Primitive>()
    /** Which mesh each of [primitives] belongs to. */
    val primitiveMesh = ArrayList<Int>()
    /** Joint node indices of the (first) skin. */
    val joints: IntArray
    /** Inverse bind matrices, 16 floats per joint. */
    val inverseBind: FloatArray
    val images: List<Bitmap?>

    /**
     * A VRM avatar's (VRoid) human bones by their VRM names ("hips", "leftUpperArm", …) → node:
     * VRM 0.x keeps them in extensions.VRM, VRM 1.0 in extensions.VRMC_vrm. Empty for plain glTF.
     */
    val humanBones: Map<String, Int> = runCatching {
        val extensions = json.optJSONObject("extensions") ?: return@runCatching emptyMap()
        val bones = HashMap<String, Int>()
        extensions.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")?.optJSONObject("humanBones")?.let { map ->
            for (key in map.keys()) map.optJSONObject(key)?.optInt("node", -1)?.takeIf { it >= 0 }?.let { bones[key] = it }
        }
        extensions.optJSONObject("VRM")?.optJSONObject("humanoid")?.optJSONArray("humanBones")?.let { list ->
            for (i in 0 until list.length()) {
                val bone = list.optJSONObject(i) ?: continue
                val node = bone.optInt("node", -1)
                if (node >= 0) bones.putIfAbsent(bone.optString("bone"), node)
            }
        }
        bones
    }.getOrDefault(emptyMap())

    init {
        val accessors = json.getJSONArray("accessors")
        val views = json.getJSONArray("bufferViews")

        fun readAccessor(index: Int): FloatArray {
            val accessor = accessors.getJSONObject(index)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val count = accessor.getInt("count")
            val components = when (accessor.getString("type")) {
                "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1
            }
            val type = accessor.getInt("componentType")
            val size = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val normalized = accessor.optBoolean("normalized", false)
            val stride = view.optInt("byteStride", 0).takeIf { it > 0 } ?: (components * size)
            val start = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val out = FloatArray(count * components)
            for (i in 0 until count) for (c in 0 until components) {
                val at = start + i * stride + c * size
                out[i * components + c] = when (type) {
                    5126 -> bin.getFloat(at)
                    5121 -> (bin.get(at).toInt() and 0xff).let { if (normalized) it / 255f else it.toFloat() }
                    5120 -> bin.get(at).toFloat().let { if (normalized) (it / 127f).coerceAtLeast(-1f) else it }
                    5123 -> (bin.getShort(at).toInt() and 0xffff).let { if (normalized) it / 65535f else it.toFloat() }
                    5122 -> bin.getShort(at).toFloat().let { if (normalized) (it / 32767f).coerceAtLeast(-1f) else it }
                    5125 -> (bin.getInt(at).toLong() and 0xffffffffL).toFloat()
                    else -> 0f
                }
            }
            return out
        }

        fun floats(array: JSONArray?, fallback: FloatArray) =
            array?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } ?: fallback

        val nodeArray = json.getJSONArray("nodes")
        nodes = List(nodeArray.length()) { i ->
            val n = nodeArray.getJSONObject(i)
            val children = n.optJSONArray("children")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
            val matrix = n.optJSONArray("matrix")?.let { floats(it, FloatArray(16)) }
            Node(
                n.optString("name"), children, n.optInt("mesh", -1),
                floats(n.optJSONArray("translation"), floatArrayOf(0f, 0f, 0f)),
                floats(n.optJSONArray("rotation"), floatArrayOf(0f, 0f, 0f, 1f)),
                floats(n.optJSONArray("scale"), floatArrayOf(1f, 1f, 1f)),
                matrix,
            )
        }
        nodes.forEachIndexed { i, node -> node.children.forEach { nodes[it].parent = i } }
        val scene = json.optJSONArray("scenes")?.getJSONObject(json.optInt("scene", 0))?.optJSONArray("nodes")
        roots = scene?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: nodes.indices.filter { nodes[it].parent < 0 }.toIntArray()

        val skin = json.optJSONArray("skins")?.optJSONObject(0)
        joints = skin?.getJSONArray("joints")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
        inverseBind = skin?.optInt("inverseBindMatrices", -1)?.takeIf { it >= 0 }?.let { readAccessor(it) }
            ?: FloatArray(joints.size * 16).also { m -> for (j in joints.indices) Matrix.setIdentityM(m, j * 16) }

        val textures = json.optJSONArray("textures")
        val materials = json.optJSONArray("materials")
        val imageArray = json.optJSONArray("images")
        images = List(imageArray?.length() ?: 0) { i ->
            val image = imageArray!!.getJSONObject(i)
            val view = views.getJSONObject(image.optInt("bufferView", -1).takeIf { it >= 0 } ?: return@List null)
            val bytes = ByteArray(view.getInt("byteLength"))
            bin.position(view.optInt("byteOffset", 0))
            bin.get(bytes)
            bin.position(0)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        for (m in 0 until meshes.length()) {
            val list = meshes.getJSONObject(m).getJSONArray("primitives")
            for (p in 0 until list.length()) {
                val primitive = list.getJSONObject(p)
                if (primitive.optInt("mode", 4) != 4) continue
                val attributes = primitive.getJSONObject("attributes")
                val positions = readAccessor(attributes.getInt("POSITION"))
                val count = positions.size / 3
                val normals = if (attributes.has("NORMAL")) readAccessor(attributes.getInt("NORMAL")) else FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f }
                val uvs = if (attributes.has("TEXCOORD_0")) readAccessor(attributes.getInt("TEXCOORD_0")) else FloatArray(count * 2)
                val jointsOf = if (attributes.has("JOINTS_0")) readAccessor(attributes.getInt("JOINTS_0")).let { f -> IntArray(f.size) { f[it].toInt() } } else IntArray(0)
                val weights = if (attributes.has("WEIGHTS_0")) readAccessor(attributes.getInt("WEIGHTS_0")) else FloatArray(0)
                val indices = if (primitive.has("indices")) readAccessor(primitive.getInt("indices")).let { f -> IntArray(f.size) { f[it].toInt() } }
                else IntArray(count) { it }
                var image = -1
                var color = floatArrayOf(1f, 1f, 1f, 1f)
                materials?.optJSONObject(primitive.optInt("material", -1))?.optJSONObject("pbrMetallicRoughness")?.let { pbr ->
                    pbr.optJSONArray("baseColorFactor")?.let { color = floats(it, color) }
                    pbr.optJSONObject("baseColorTexture")?.let { image = textures?.optJSONObject(it.getInt("index"))?.optInt("source", -1) ?: -1 }
                }
                primitives += Primitive(positions, normals, uvs, jointsOf, weights, indices, image, color)
                primitiveMesh += m
            }
        }
    }

    companion object {
        fun load(stream: InputStream): Gltf {
            val bytes = stream.readBytes()
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(data.getInt(0) == 0x46546C67) { "Не файл .glb" }
            var offset = 12
            var json: JSONObject? = null
            var bin: ByteBuffer? = null
            while (offset + 8 <= bytes.size) {
                val length = data.getInt(offset)
                val type = data.getInt(offset + 4)
                if (type == 0x4E4F534A) json = JSONObject(String(bytes, offset + 8, length, Charsets.UTF_8))
                if (type == 0x004E4942) bin = ByteBuffer.wrap(bytes, offset + 8, length).slice().order(ByteOrder.LITTLE_ENDIAN)
                offset += 8 + length
            }
            return Gltf(json ?: error("В .glb нет описания"), bin ?: ByteBuffer.allocate(0))
        }
    }
}
