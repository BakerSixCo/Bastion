package com.bastion.terminal.map

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads a real map pack on the device and routes across real ground. Needs
 * the "sawtooths" pack (`tools/map-build.ps1 -Name sawtooths -Bbox
 * -115.10,44.06,-114.85,44.24`) in BASTION's maps folder; **skipped**
 * without it. Output: "MapEval" log lines.
 */
@RunWith(AndroidJUnit4::class)
class MapPackTest {

    @Test
    fun packOpensAndRoutesAcrossRealTerrain() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MapPacks.get(ctx)
        store.rescan()
        val pack = store.packs.value.firstOrNull { it.name == "sawtooths" }
        assumeTrue("needs the sawtooths map pack (found ${store.packs.value.map { it.name }}, problems ${store.problems.value}) — skipped", pack != null)
        pack!!
        Log.i(TAG, "pack ${pack.name} built ${pack.built} topo=${pack.topo} land=${pack.land} elevZoom=${pack.elevZoom} layers=${pack.layers.map { "${it.id}:grid${it.grid}" }}")
        assertNotNull(pack.topo); assertNotNull(pack.elevZoom)

        // A tile in the middle of the pack exists and is an image.
        val z = pack.topo!!.maxZoom
        val midLat = (pack.south + pack.north) / 2; val midLon = (pack.west + pack.east) / 2
        val tile = pack.tile("topo", z, Geo.tileX(midLon, z), Geo.tileY(midLat, z))
        assertNotNull("topo tile at the centre", tile)
        assertNotNull(android.graphics.BitmapFactory.decodeByteArray(tile, 0, tile!!.size))

        val t0 = System.nanoTime()
        val dem = pack.dem(pack.west, pack.south, pack.east, pack.north)
        assertNotNull("elevation", dem); dem!!
        Log.i(TAG, "dem ${dem.w}x${dem.h} at zoom ${dem.zoom} in ${(System.nanoTime() - t0) / 1_000_000} ms")

        // Known ground: Stanley, Idaho sits at about 1,900 m; Redfish Lake at about 1,996 m.
        val stanley = dem.elevation(44.2163, -114.9369)!!
        val redfish = dem.elevation(44.1178, -114.9300)!!
        Log.i(TAG, "elevation Stanley=$stanley m, Redfish Lake=$redfish m")
        assertEquals(1900f, stanley, 40f)
        assertEquals(1996f, redfish, 15f)


        // Redfish Lake lodge up to the Bench Lakes: a real hike with a trail and about 250 m of climb.
        val t1 = System.nanoTime()
        val r = store.routeBetween(44.1447, -114.9170, 44.1227, -114.9560)
        val ms = (System.nanoTime() - t1) / 1_000_000
        assertNotNull("route", r); r!!
        Log.i(TAG, "ROUTE lodge→Bench Lakes in $ms ms: ${r.summary()}")
        r.legs.forEachIndexed { i, leg -> Log.i(TAG, "  " + leg.text(i + 1)) }
        assertTrue("3–9 km", r.meters in 3000.0..9000.0)
        assertTrue("climbs 150–500 m, was ${r.climbM}", r.climbM in 150..500)
        assertTrue("1–5 h", r.seconds in 3600..18000)

        // Across the valley with no trail mask: still finds a way, slower.
        val r2 = TerrainRoute.route(dem, 44.1447, -114.9170, 44.1227, -114.9560, null)
        assertNotNull(r2)
        Log.i(TAG, "ROUTE same, ignoring trails: ${r2!!.summary()}")
        Log.i(TAG, "SUMMARY ok")
    }

    private companion object { const val TAG = "MapEval" }
}
