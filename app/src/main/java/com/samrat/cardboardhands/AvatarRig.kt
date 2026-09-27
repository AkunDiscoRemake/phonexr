package com.samrat.cardboardhands

import android.opengl.Matrix
import com.samrat.orangehanding.FusionBody
import kotlin.math.sqrt

/**
 * Puts a tracked body on a glTF character. Each limb bone is swung so it points where the
 * person's limb points (MediaPipe's metric 3D landmarks), the hips and chest are turned from the
 * hip and shoulder lines, and the head from the ears. The character then keeps its feet on the
 * floor and slides sideways with the person. Skinning happens on the CPU, which is ample for a
 * character of a few tens of thousands of vertices.
 */
class AvatarRig(val model: Gltf) {
    private val count = model.nodes.size
    private val restQ = Array(count) { FloatArray(4) }
    private val restT = Array(count) { FloatArray(3) }
    private val restS = FloatArray(count)
    private val localQ = Array(count) { FloatArray(4) }
    private val localT = Array(count) { FloatArray(3) }
    private val globalQ = Array(count) { FloatArray(4) }
    private val globalP = Array(count) { FloatArray(3) }
    private val globalS = FloatArray(count)
    private val order = ArrayList<Int>()
    private val restGlobalP = Array(count) { FloatArray(3) }
    /** Where the tracker says the bones should be, and where they are drawn now (eased toward it). */
    private val targetQ = Array(count) { FloatArray(4) }
    private val targetT = Array(count) { FloatArray(3) }
    private val shownQ = Array(count) { FloatArray(4) }
    private val shownT = Array(count) { FloatArray(3) }
    private val bones = HashMap<String, Int>()

    /** Skinned vertices of every primitive, rewritten by [skin]. */
    val positions = model.primitives.map { it.positions.copyOf() }
    val normals = model.primitives.map { it.normals.copyOf() }
    private val jointMatrices = FloatArray(model.joints.size * 16)
    private val scratch = FloatArray(16)

    /** Lowest rest point of the feet: the floor. */
    private val floor: Float
    /** Height of the rest skeleton, from the floor to the top of the head. */
    val height: Float

    init {
        for (i in 0 until count) {
            val node = model.nodes[i]
            val matrix = node.matrix
            if (matrix != null) decompose(matrix, restT[i], restQ[i]).also { restS[i] = it }
            else {
                node.translation.copyInto(restT[i]); node.rotation.copyInto(restQ[i]); restS[i] = node.scale[0]
            }
            normalize(restQ[i])
        }
        fun visit(i: Int) { order += i; model.nodes[i].children.forEach(::visit) }
        model.roots.forEach(::visit)
        for (i in 0 until count) {
            val clean = model.nodes[i].name.substringAfterLast(':').replace(Regex("_\\d+$"), "")
            bones.putIfAbsent(clean, i)
        }
        // A VRM avatar (VRoid) names its bones differently: take them from its human-bone map,
        // under the Mixamo names the rig drives (Avaturn and Mixamo characters have those already).
        for ((vrm, mixamo) in VRM_TO_MIXAMO) model.humanBones[vrm]?.let { bones[mixamo] = it }
        if ("Spine2" !in bones) (model.humanBones["upperChest"] ?: model.humanBones["chest"])?.let { bones["Spine2"] = it }
        reset()
        update()
        for (i in 0 until count) globalP[i].copyInto(restGlobalP[i])
        keepTarget()
        for (i in 0 until count) { targetQ[i].copyInto(shownQ[i]); targetT[i].copyInto(shownT[i]) }
        floor = listOf("LeftFoot", "RightFoot", "LeftToeBase", "RightToeBase").mapNotNull { bones[it] }
            .minOfOrNull { restGlobalP[it][1] } ?: 0f
        height = (bones["HeadTop_End"] ?: bones["Head"])?.let { restGlobalP[it][1] - floor + .02f } ?: 1.75f
    }

    private fun bone(name: String) = bones[name] ?: -1

    fun reset() {
        for (i in 0 until count) { restQ[i].copyInto(localQ[i]); restT[i].copyInto(localT[i]) }
    }

    /** Global transforms from the local ones, parents first. */
    private fun update() {
        for (i in order) {
            val parent = model.nodes[i].parent
            if (parent < 0) {
                localQ[i].copyInto(globalQ[i]); localT[i].copyInto(globalP[i]); globalS[i] = restS[i]
            } else {
                mul(globalQ[parent], localQ[i], globalQ[i])
                val t = rotate(globalQ[parent], localT[i])
                for (c in 0..2) globalP[i][c] = globalP[parent][c] + t[c] * globalS[parent]
                globalS[i] = globalS[parent] * restS[i]
            }
        }
    }

    /**
     * Poses the character from [pose]. [sideways] moves it left or right (metres), as the person
     * moves in the picture.
     */
    fun pose(pose: FusionBody.Pose, sideways: Float) {
        reset()
        update()
        fun w(i: Int) = floatArrayOf(pose.wx(i), -pose.wy(i), -pose.wz(i))
        fun seen(vararg i: Int) = i.all { pose.visibility(it) > .35f }
        fun mid(a: FloatArray, b: FloatArray) = floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

        val hips = mid(w(23), w(24))
        val shoulders = mid(w(11), w(12))
        // Hips: hip line and the way up the back.
        turn(bone("Hips"), sub(w(23), w(24)), sub(shoulders, hips), rest(bone("LeftUpLeg"), bone("RightUpLeg")), rest(bone("Neck"), bone("Hips")))
        // Chest: the shoulder line may twist against the hips.
        if (seen(11, 12)) turn(bone("Spine2"), sub(w(11), w(12)), sub(shoulders, hips), rest(bone("LeftArm"), bone("RightArm")), rest(bone("Neck"), bone("Hips")))
        // Head: ears for the tilt and turn, nose to decide which way is forward.
        if (seen(7, 8)) {
            val ears = mid(w(7), w(8))
            turn(bone("Head"), sub(w(7), w(8)), sub(ears, shoulders), rest(bone("LeftEye"), bone("RightEye")), rest(bone("Head"), bone("Neck")))
        }
        // Limbs: shoulder → elbow → wrist → hand, hip → knee → ankle → toes.
        val limbs = arrayOf(
            Triple("LeftArm", 11, 13), Triple("LeftForeArm", 13, 15), Triple("LeftHand", 15, -19),
            Triple("RightArm", 12, 14), Triple("RightForeArm", 14, 16), Triple("RightHand", 16, -20),
            Triple("LeftUpLeg", 23, 25), Triple("LeftLeg", 25, 27), Triple("LeftFoot", 27, 31),
            Triple("RightUpLeg", 24, 26), Triple("RightLeg", 26, 28), Triple("RightFoot", 28, 32),
        )
        for ((name, from, toRaw) in limbs) {
            val node = bone(name)
            if (node < 0) continue
            // A negative end is a hand: aim between the index and pinky knuckles.
            val to = if (toRaw < 0) -toRaw else toRaw
            if (!seen(from, to)) continue
            val target = if (toRaw < 0) sub(mid(w(to), w(to - 2)), w(from)) else sub(w(to), w(from))
            aim(node, target)
        }
        // Keep the feet on the floor and follow the person sideways.
        val root = model.roots.firstOrNull() ?: return
        val lowest = listOf("LeftFoot", "RightFoot", "LeftToeBase", "RightToeBase").mapNotNull { bones[it] }.minOfOrNull { globalP[it][1] } ?: floor
        localT[root][0] += sideways
        localT[root][1] += floor - lowest
        update()
        keepTarget()
    }

    /** No one in view: ease back to the rest pose. */
    fun rest() {
        reset()
        keepTarget()
    }

    private fun keepTarget() {
        for (i in 0 until count) { localQ[i].copyInto(targetQ[i]); localT[i].copyInto(targetT[i]) }
    }

    /**
     * Moves the drawn pose a share [amount] (0..1) of the way to the tracked one and updates the
     * skeleton. Called every screen frame, so the body glides at the display's rate however
     * seldom the tracker answers. Returns false when there was nothing left to move.
     */
    fun ease(amount: Float): Boolean {
        var moved = 0f
        for (i in 0 until count) {
            val a = shownQ[i]; val b = targetQ[i]
            // Take the short way round.
            val sign = if (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3] < 0f) -1f else 1f
            for (c in 0..3) {
                val next = a[c] + (b[c] * sign - a[c]) * amount
                moved = maxOf(moved, kotlin.math.abs(next - a[c]))
                a[c] = next
            }
            normalize(a)
            for (c in 0..2) {
                val next = shownT[i][c] + (targetT[i][c] - shownT[i][c]) * amount
                moved = maxOf(moved, kotlin.math.abs(next - shownT[i][c]))
                shownT[i][c] = next
            }
            a.copyInto(localQ[i]); shownT[i].copyInto(localT[i])
        }
        if (moved < 1e-4f) return false
        update()
        return true
    }

    private fun rest(a: Int, b: Int): FloatArray =
        if (a < 0 || b < 0) floatArrayOf(0f, 1f, 0f)
        else floatArrayOf(restGlobalP[a][0] - restGlobalP[b][0], restGlobalP[a][1] - restGlobalP[b][1], restGlobalP[a][2] - restGlobalP[b][2])

    /** Turns [node] (in world terms) so the rest frame (right0, up0) lines up with (right, up). */
    private fun turn(node: Int, right: FloatArray, up: FloatArray, right0: FloatArray, up0: FloatArray) {
        if (node < 0) return
        val target = basis(right, up) ?: return
        val start = basis(right0, up0) ?: return
        val delta = mul(target, conjugate(start))
        // The rest global rotation of the node, turned by delta, becomes its new global rotation.
        val parent = model.nodes[node].parent
        val restGlobal = if (parent < 0) restQ[node] else mul(restGlobalOf(parent), restQ[node])
        setGlobal(node, mul(delta, restGlobal))
    }

    private fun restGlobalOf(node: Int): FloatArray {
        var q = restQ[node].copyOf()
        var p = model.nodes[node].parent
        while (p >= 0) { q = mul(restQ[p], q); p = model.nodes[p].parent }
        return q
    }

    /** Swings [node] so its first child lies along [target] (world direction). */
    private fun aim(node: Int, target: FloatArray) {
        val child = model.nodes[node].children.firstOrNull() ?: return
        val along = rotate(globalQ[node], restT[child])
        val swing = fromTo(along, target) ?: return
        setGlobal(node, mul(swing, globalQ[node]))
    }

    private fun setGlobal(node: Int, global: FloatArray) {
        val parent = model.nodes[node].parent
        val local = if (parent < 0) global else mul(conjugate(globalQ[parent]), global)
        normalize(local)
        local.copyInto(localQ[node])
        update()
    }

    /** Skins every primitive for the current pose into [positions] and [normals]. */
    fun skin() {
        val joints = model.joints
        for (j in joints.indices) {
            val n = joints[j]
            compose(globalP[n], globalQ[n], globalS[n], scratch)
            Matrix.multiplyMM(jointMatrices, j * 16, scratch, 0, model.inverseBind, j * 16)
        }
        val m = jointMatrices
        model.primitives.forEachIndexed { index, primitive ->
            val out = positions[index]
            val outN = normals[index]
            val src = primitive.positions
            val srcN = primitive.normals
            val js = primitive.joints
            val ws = primitive.weights
            if (js.isEmpty()) return@forEachIndexed
            for (v in 0 until primitive.vertexCount) {
                var x = 0f; var y = 0f; var z = 0f; var nx = 0f; var ny = 0f; var nz = 0f
                val px = src[v * 3]; val py = src[v * 3 + 1]; val pz = src[v * 3 + 2]
                val qx = srcN[v * 3]; val qy = srcN[v * 3 + 1]; val qz = srcN[v * 3 + 2]
                for (k in 0..3) {
                    val weight = ws[v * 4 + k]
                    if (weight == 0f) continue
                    val o = js[v * 4 + k] * 16
                    x += weight * (m[o] * px + m[o + 4] * py + m[o + 8] * pz + m[o + 12])
                    y += weight * (m[o + 1] * px + m[o + 5] * py + m[o + 9] * pz + m[o + 13])
                    z += weight * (m[o + 2] * px + m[o + 6] * py + m[o + 10] * pz + m[o + 14])
                    nx += weight * (m[o] * qx + m[o + 4] * qy + m[o + 8] * qz)
                    ny += weight * (m[o + 1] * qx + m[o + 5] * qy + m[o + 9] * qz)
                    nz += weight * (m[o + 2] * qx + m[o + 6] * qy + m[o + 10] * qz)
                }
                out[v * 3] = x; out[v * 3 + 1] = y; out[v * 3 + 2] = z
                outN[v * 3] = nx; outN[v * 3 + 1] = ny; outN[v * 3 + 2] = nz
            }
        }
    }

    companion object {
        // Quaternions are (x, y, z, w).
        fun mul(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(4)): FloatArray {
            val x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
            val y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0]
            val z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3]
            val w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
            out[0] = x; out[1] = y; out[2] = z; out[3] = w
            return out
        }

        /** VRM human bones and the Mixamo names the rig uses for them. */
        private val VRM_TO_MIXAMO = listOf(
            "hips" to "Hips", "spine" to "Spine", "chest" to "Spine1", "upperChest" to "Spine2", "neck" to "Neck", "head" to "Head",
            "leftEye" to "LeftEye", "rightEye" to "RightEye",
            "leftShoulder" to "LeftShoulder", "leftUpperArm" to "LeftArm", "leftLowerArm" to "LeftForeArm", "leftHand" to "LeftHand",
            "rightShoulder" to "RightShoulder", "rightUpperArm" to "RightArm", "rightLowerArm" to "RightForeArm", "rightHand" to "RightHand",
            "leftUpperLeg" to "LeftUpLeg", "leftLowerLeg" to "LeftLeg", "leftFoot" to "LeftFoot", "leftToes" to "LeftToeBase",
            "rightUpperLeg" to "RightUpLeg", "rightLowerLeg" to "RightLeg", "rightFoot" to "RightFoot", "rightToes" to "RightToeBase",
        )

        fun conjugate(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

        fun normalize(q: FloatArray) {
            val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (l > 1e-8f) for (i in 0..3) q[i] /= l else { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f }
        }

        fun rotate(q: FloatArray, v: FloatArray): FloatArray {
            val (x, y, z, w) = q
            val tx = 2 * (y * v[2] - z * v[1]); val ty = 2 * (z * v[0] - x * v[2]); val tz = 2 * (x * v[1] - y * v[0])
            return floatArrayOf(v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))
        }

        /** Shortest rotation taking direction [a] to direction [b]; null when either is zero. */
        fun fromTo(a: FloatArray, b: FloatArray): FloatArray? {
            val la = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val lb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (la < 1e-6f || lb < 1e-6f) return null
            val ax = a[0] / la; val ay = a[1] / la; val az = a[2] / la
            val bx = b[0] / lb; val by = b[1] / lb; val bz = b[2] / lb
            val dot = ax * bx + ay * by + az * bz
            if (dot < -.9999f) {
                // Opposite: half a turn around any axis across a.
                val axis = if (kotlin.math.abs(ax) < .9f) floatArrayOf(0f, -az, ay) else floatArrayOf(-az, 0f, ax)
                val q = floatArrayOf(axis[0], axis[1], axis[2], 0f); normalize(q); return q
            }
            val q = floatArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx, 1f + dot)
            normalize(q)
            return q
        }

        /** Rotation whose x axis is [right] and y axis is [up] (made perpendicular). */
        fun basis(right: FloatArray, up: FloatArray): FloatArray? {
            val r = unit(right) ?: return null
            val d = r[0] * up[0] + r[1] * up[1] + r[2] * up[2]
            val u = unit(floatArrayOf(up[0] - r[0] * d, up[1] - r[1] * d, up[2] - r[2] * d)) ?: return null
            val f = floatArrayOf(r[1] * u[2] - r[2] * u[1], r[2] * u[0] - r[0] * u[2], r[0] * u[1] - r[1] * u[0])
            return fromMatrix(r, u, f)
        }

        private fun unit(v: FloatArray): FloatArray? {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return if (l < 1e-6f) null else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        /** Quaternion of the rotation matrix with columns x, y, z. */
        private fun fromMatrix(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
            val m00 = x[0]; val m10 = x[1]; val m20 = x[2]
            val m01 = y[0]; val m11 = y[1]; val m21 = y[2]
            val m02 = z[0]; val m12 = z[1]; val m22 = z[2]
            val trace = m00 + m11 + m22
            val q = FloatArray(4)
            if (trace > 0) {
                val s = sqrt(trace + 1f) * 2
                q[3] = .25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2
                q[3] = (m21 - m12) / s; q[0] = .25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2
                q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = .25f * s; q[2] = (m12 + m21) / s
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2
                q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = .25f * s
            }
            normalize(q)
            return q
        }

        /** Column-major matrix T · R · S. */
        fun compose(t: FloatArray, q: FloatArray, s: Float, out: FloatArray) {
            val (x, y, z, w) = q
            out[0] = (1 - 2 * (y * y + z * z)) * s; out[1] = 2 * (x * y + z * w) * s; out[2] = 2 * (x * z - y * w) * s; out[3] = 0f
            out[4] = 2 * (x * y - z * w) * s; out[5] = (1 - 2 * (x * x + z * z)) * s; out[6] = 2 * (y * z + x * w) * s; out[7] = 0f
            out[8] = 2 * (x * z + y * w) * s; out[9] = 2 * (y * z - x * w) * s; out[10] = (1 - 2 * (x * x + y * y)) * s; out[11] = 0f
            out[12] = t[0]; out[13] = t[1]; out[14] = t[2]; out[15] = 1f
        }

        /** Splits a TRS matrix into translation and rotation; returns the (uniform) scale. */
        private fun decompose(m: FloatArray, t: FloatArray, q: FloatArray): Float {
            t[0] = m[12]; t[1] = m[13]; t[2] = m[14]
            val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]).coerceAtLeast(1e-6f)
            val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]).coerceAtLeast(1e-6f)
            val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]).coerceAtLeast(1e-6f)
            fromMatrix(floatArrayOf(m[0] / sx, m[1] / sx, m[2] / sx), floatArrayOf(m[4] / sy, m[5] / sy, m[6] / sy),
                floatArrayOf(m[8] / sz, m[9] / sz, m[10] / sz)).copyInto(q)
            return sx
        }
    }
}
