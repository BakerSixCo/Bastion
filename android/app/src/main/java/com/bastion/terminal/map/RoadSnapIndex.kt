package com.bastion.terminal.map

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Spatial index of the edges already published by a road graph. Coordinates are integer microdegrees. */
internal class RoadSnapIndex {
    private val levels = arrayOfNulls<Level>(Int.SIZE_BITS - BASE_SHIFT)

    private class Bucket(val x: Int, val y: Int) {
        val edges = RoadGraph.IntArrayList()
    }

    private class Level {
        private val byCell = LongIntMap()
        val buckets = ArrayList<Bucket>()

        fun get(x: Int, y: Int): Bucket? = buckets.getOrNull(byCell[key(x, y)])

        fun getOrAdd(x: Int, y: Int): Bucket {
            get(x, y)?.let { return it }
            val bucket = Bucket(x, y)
            byCell[key(x, y)] = buckets.size
            buckets += bucket
            return bucket
        }
    }

    fun add(edge: Int, fromLat: Int, fromLon: Int, toLat: Int, toLon: Int, shape: IntArray) {
        var south = minOf(fromLat, toLat)
        var north = maxOf(fromLat, toLat)
        var west = minOf(fromLon, toLon)
        var east = maxOf(fromLon, toLon)
        for (p in shape.indices step 2) {
            south = minOf(south, shape[p])
            north = maxOf(north, shape[p])
            west = minOf(west, shape[p + 1])
            east = maxOf(east, shape[p + 1])
        }
        var shift = BASE_SHIFT
        // Use coarse cells for large bounding boxes. The entire edge takes at most four references,
        // even for a long diagonal or a winding edge that leaves the tile that provided its bytes.
        while ((east shr shift) - (west shr shift) > 1 || (north shr shift) - (south shr shift) > 1) shift++
        val levelIndex = shift - BASE_SHIFT
        val level = levels[levelIndex] ?: Level().also { levels[levelIndex] = it }
        for (x in (west shr shift)..(east shr shift)) {
            for (y in (south shr shift)..(north shr shift)) level.getOrAdd(x, y).edges.add(edge)
        }
    }

    /** Conservative bbox lookup using the same metres-per-degree scales as the segment projection. */
    fun nearby(lat: Double, lon: Double, maxM: Double, kx: Double, ky: Double): IntArray {
        if (!lat.isFinite() || !lon.isFinite() || !(maxM >= 0.0)) return IntArray(0)
        val lonRadius = if (kx == 0.0) Double.POSITIVE_INFINITY else maxM / abs(kx)
        val latRadius = maxM / ky
        // Pad by one microdegree to keep inclusive hits at a cell boundary despite floating rounding.
        val west = floor((lon - lonRadius) * 1e6 - 1).toInt()
        val east = ceil((lon + lonRadius) * 1e6 + 1).toInt()
        val south = floor((lat - latRadius) * 1e6 - 1).toInt()
        val north = ceil((lat + latRadius) * 1e6 + 1).toInt()
        val matches = RoadGraph.IntArrayList()
        fun collect(bucket: Bucket) {
            for (i in 0 until bucket.edges.size) matches.add(bucket.edges[i])
        }
        for ((index, level) in levels.withIndex()) {
            if (level == null) continue
            val shift = BASE_SHIFT + index
            val xs = (west shr shift)..(east shr shift)
            val ys = (south shr shift)..(north shr shift)
            val area = (xs.last - xs.first + 1L) * (ys.last - ys.first + 1L)
            if (area <= level.buckets.size.toLong()) {
                for (x in xs) for (y in ys) level.get(x, y)?.let(::collect)
            } else {
                // A wide query (or one close to a pole) must not enumerate mostly empty cells.
                for (bucket in level.buckets) if (bucket.x in xs && bucket.y in ys) collect(bucket)
            }
        }
        if (matches.size == 0) return IntArray(0)
        val sorted = IntArray(matches.size) { matches[it] }
        sorted.sort()
        // The full scan used publication order. Preserve it for equally near edges across buckets.
        var unique = 1
        for (i in 1 until sorted.size) if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
        return if (unique == sorted.size) sorted else sorted.copyOf(unique)
    }

    private companion object {
        const val BASE_SHIFT = 15 // 0.032768° per base cell

        fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffff_ffffL)
    }
}
