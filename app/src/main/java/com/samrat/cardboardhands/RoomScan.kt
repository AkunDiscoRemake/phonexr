package com.samrat.cardboardhands

import android.content.Context
import com.google.ar.core.Plane
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

/**
 * The room scan: ARCore's planes as grids the user sees laid over the floor, the table and the
 * walls, and the table remembered (in the VR home's world, which starts where 6DoF starts — like
 * [Boundary]) so the VR keyboard can lie on it.
 */
object RoomScan {
    /** A scanned surface: grid lines as pairs of world points (x, y, z), and what it is. */
    class Surface(val kind: Kind, val lines: FloatArray, val center: FloatArray, val yaw: Float, val halfX: Float, val halfZ: Float)

    enum class Kind { FLOOR, TABLE, WALL, OTHER }

    /** The table: its top's centre, which way its long side runs, and half its size. */
    data class Table(val x: Float, val y: Float, val z: Float, val yaw: Float, val halfX: Float, val halfZ: Float)

    private const val PREFS = "room_scan"
    private const val GRID = .2f

    @Volatile var table: Table? = null
        private set

    fun load(context: Context) {
        table = runCatching {
            val json = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("table", null) ?: return)
            Table(json.getDouble("x").toFloat(), json.getDouble("y").toFloat(), json.getDouble("z").toFloat(),
                json.getDouble("yaw").toFloat(), json.getDouble("hx").toFloat(), json.getDouble("hz").toFloat())
        }.getOrNull()
    }

    /** Remembers the biggest table seen at a table's height. */
    fun remember(context: Context, surfaces: List<Surface>) {
        val found = surfaces.filter { it.kind == Kind.TABLE }.maxByOrNull { it.halfX * it.halfZ } ?: return
        val old = table
        val same = old != null && abs(old.y - found.center[1]) < .08f &&
            abs(old.x - found.center[0]) < .6f && abs(old.z - found.center[2]) < .6f
        if (same && old!!.halfX * old.halfZ >= found.halfX * found.halfZ) return
        val next = Table(found.center[0], found.center[1], found.center[2], found.yaw, found.halfX, found.halfZ)
        table = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("table", JSONObject()
            .put("x", next.x).put("y", next.y).put("z", next.z).put("yaw", next.yaw).put("hx", next.halfX).put("hz", next.halfZ)
            .toString()).apply()
    }

    fun forget(context: Context) {
        table = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("table").apply()
    }

    /**
     * One ARCore plane as a grid: lines every [GRID] metres across its outline, and the outline
     * itself. [toWorld] turns an ARCore world point into the VR home's world.
     */
    fun surface(plane: Plane, toWorld: (Float, Float, Float) -> FloatArray): Surface? {
        val buffer = plane.polygon ?: return null
        buffer.rewind()
        val polygon = FloatArray(buffer.remaining()).also { buffer.get(it) }
        if (polygon.size < 6) return null
        val pose = plane.centerPose
        fun world(x: Float, z: Float): FloatArray {
            val p = pose.transformPoint(floatArrayOf(x, 0f, z))
            return toWorld(p[0], p[1], p[2])
        }
        val lines = ArrayList<Float>()
        fun segment(ax: Float, az: Float, bx: Float, bz: Float) {
            val a = world(ax, az); val b = world(bx, bz)
            lines.add(a[0]); lines.add(a[1]); lines.add(a[2]); lines.add(b[0]); lines.add(b[1]); lines.add(b[2])
        }
        val n = polygon.size / 2
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = polygon[i * 2]; val z = polygon[i * 2 + 1]
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
            val j = (i + 1) % n
            segment(x, z, polygon[j * 2], polygon[j * 2 + 1])
        }
        // Grid lines, clipped to the outline: where each line crosses the edges, in pairs.
        fun crossings(fixed: Float, alongX: Boolean): List<Float> {
            val hits = ArrayList<Float>()
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = polygon[i * 2]; val az = polygon[i * 2 + 1]; val bx = polygon[j * 2]; val bz = polygon[j * 2 + 1]
                val (a, b, ca, cb) = if (alongX) listOf(az, bz, ax, bx) else listOf(ax, bx, az, bz)
                if ((a <= fixed && b > fixed) || (b <= fixed && a > fixed)) hits += ca + (fixed - a) / (b - a) * (cb - ca)
            }
            return hits.sorted()
        }
        var gx = floor(minX / GRID) * GRID + GRID
        while (gx < maxX) {
            val hits = crossings(gx, alongX = false)
            for (k in 0 until hits.size / 2) segment(gx, hits[k * 2], gx, hits[k * 2 + 1])
            gx += GRID
        }
        var gz = floor(minZ / GRID) * GRID + GRID
        while (gz < maxZ) {
            val hits = crossings(gz, alongX = true)
            for (k in 0 until hits.size / 2) segment(hits[k * 2], gz, hits[k * 2 + 1], gz)
            gz += GRID
        }
        val center = world(0f, 0f)
        val axis = world(1f, 0f)
        val yaw = atan2(-(axis[2] - center[2]), axis[0] - center[0])
        val halfX = plane.extentX / 2; val halfZ = plane.extentZ / 2
        val kind = when {
            plane.type == Plane.Type.VERTICAL -> Kind.WALL
            plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING -> Kind.OTHER
            // The start is where the head was: a floor is well under it, a table about waist-high.
            center[1] < -1.15f -> Kind.FLOOR
            center[1] in -1.1f..-.25f && halfX * halfZ * 4 >= .12f -> Kind.TABLE
            else -> Kind.OTHER
        }
        return Surface(kind, lines.toFloatArray(), center, yaw, halfX, halfZ)
    }
}
