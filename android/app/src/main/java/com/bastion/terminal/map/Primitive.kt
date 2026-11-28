package com.bastion.terminal.map

/**
 * Hash map from Long to Int without boxing (open addressing, linear probing). About 24 bytes an entry
 * against ~80 for HashMap<Long, Int>: a state's road network has hundreds of thousands of roads and the
 * route search millions of states, so this decides whether routing fits in a phone's memory.
 * Keys may be any Long; [get] returns [MISSING] for an absent key, so callers must not store it.
 */
internal class LongIntMap(capacity: Int = 16) {
    private var keys = LongArray(tableSize(capacity))
    private var vals = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0; private set

    operator fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return MISSING
    }

    operator fun set(key: Long, value: Int) {
        require(value != MISSING)
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) { vals[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = key; vals[i] = value; size++
    }

    fun containsKey(key: Long) = get(key) != MISSING

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = IntArray(keys.size); used = BooleanArray(keys.size); size = 0
        for (i in ok.indices) if (ou[i]) set(ok[i], ov[i])
    }

    companion object {
        const val MISSING = Int.MIN_VALUE
        private fun tableSize(n: Int): Int { var s = 16; while (s < n * 2) s = s shl 1; return s }
        /** Spreads the bits so sequential ids (OSM ids, packed states) do not cluster. */
        private fun mix(k: Long): Int { var h = k * -0x61c8864680b583ebL; h = h xor (h ushr 32); return h.toInt() }
    }
}

/** Binary min-heap of (priority, g, slot) in parallel primitive arrays, for the route search queue. */
internal class StateHeap(capacity: Int = 1024) {
    private var f = DoubleArray(capacity); private var g = DoubleArray(capacity); private var s = IntArray(capacity)
    var size = 0; private set
    fun isNotEmpty() = size > 0

    fun add(pf: Double, pg: Double, slot: Int) {
        if (size == f.size) { f = f.copyOf(size * 2); g = g.copyOf(size * 2); s = s.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (f[p] <= pf) break
            f[i] = f[p]; g[i] = g[p]; s[i] = s[p]; i = p
        }
        f[i] = pf; g[i] = pg; s[i] = slot
    }

    // The top entry; read these before [pop].
    val topF get() = f[0]
    val topG get() = g[0]
    val topSlot get() = s[0]

    fun pop() {
        val n = --size
        if (n == 0) return
        val lf = f[n]; val lg = g[n]; val ls = s[n]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= n) break
            if (c + 1 < n && f[c + 1] < f[c]) c++
            if (f[c] >= lf) break
            f[i] = f[c]; g[i] = g[c]; s[i] = s[c]; i = c
        }
        f[i] = lf; g[i] = lg; s[i] = ls
    }
}
