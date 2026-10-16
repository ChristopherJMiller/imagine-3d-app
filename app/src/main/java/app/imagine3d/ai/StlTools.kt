package app.imagine3d.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MeshCheck(val triangles: Int, val size: List<Double>, val components: Int)

/** Small binary-STL analysis used to sanity-check generated parts. */
object StlTools {
    fun analyze(stl: ByteArray): MeshCheck? {
        if (stl.size < 84) return null
        val buf = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN)
        val n = buf.getInt(80)
        if (n <= 0 || 84L + n * 50L > stl.size) return null
        val min = DoubleArray(3) { Double.MAX_VALUE }
        val max = DoubleArray(3) { -Double.MAX_VALUE }
        val ids = HashMap<Long, Int>(n * 2)
        val parent = IntArray(n * 3)
        var nVerts = 0
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for (t in 0 until n) {
            val base = 84 + t * 50 + 12
            val tri = IntArray(3)
            for (v in 0 until 3) {
                val x = buf.getFloat(base + v * 12).toDouble()
                val y = buf.getFloat(base + v * 12 + 4).toDouble()
                val z = buf.getFloat(base + v * 12 + 8).toDouble()
                val p = doubleArrayOf(x, y, z)
                for (k in 0 until 3) { if (p[k] < min[k]) min[k] = p[k]; if (p[k] > max[k]) max[k] = p[k] }
                // Quantise to 1 µm so shared vertices of neighbouring triangles hash together.
                val key = (Math.round(x * 1000) * 73856093L) xor (Math.round(y * 1000) * 19349663L) xor (Math.round(z * 1000) * 83492791L)
                tri[v] = ids.getOrPut(key) { parent[nVerts] = nVerts; nVerts++ }
            }
            union(tri[0], tri[1]); union(tri[1], tri[2])
        }
        val roots = HashSet<Int>()
        for (i in 0 until nVerts) roots += find(i)
        return MeshCheck(n, (0 until 3).map { max[it] - min[it] }, roots.size)
    }
}
