package app.vela.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSR cell index behind the ALPR layer, exercised through the same gzipped-TSV parse the app
 * uses. The generation-swap cases exist because the index was once published as six separate
 * fields, which could TEAR against a concurrent viewport scan during refresh()'s hot-swap (new
 * `cellKeys` binary-searched against old `cellStart` = index out of bounds, an app crash with the
 * camera layer on). A single published snapshot cannot tear; these tests pin the behaviour that
 * refactor must keep: queries are correct before, between, and after swaps, and a swap to a LARGER
 * dataset leaves every query in bounds.
 */
class FlockCamerasTest {

    @After fun reset() = FlockCameras.resetForTest()

    private fun gzTsv(rows: List<Triple<Double, Double, String>>): ByteArrayInputStream {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter().use { w ->
            rows.forEach { (la, lo, op) -> w.write("$la\t$lo\t$op\n") }
        }
        return ByteArrayInputStream(bos.toByteArray())
    }

    @Test
    fun `unloaded queries are empty, not crashes`() {
        assertTrue(FlockCameras.inBox(-90.0, -180.0, 90.0, 180.0).isEmpty())
        assertEquals(0, FlockCameras.size)
    }

    @Test
    fun `inBox finds exactly the cameras inside the box`() {
        FlockCameras.loadFromForTest(
            gzTsv(
                listOf(
                    Triple(40.0, -75.0, "opA"),
                    Triple(40.05, -75.05, "opB"),
                    Triple(41.0, -75.0, "far-north"),
                    Triple(40.0, -76.0, "far-west"),
                ),
            ),
        )
        assertEquals(4, FlockCameras.size)
        val hit = FlockCameras.inBox(39.9, -75.2, 40.2, -74.9)
        assertEquals(setOf("opA", "opB"), hit.map { it.operator }.toSet())
    }

    @Test
    fun `cameras straddling many grid cells are all found`() {
        // 0.1 deg cells: place one camera per cell across a 3x3 block and query the whole block.
        val rows = ArrayList<Triple<Double, Double, String>>()
        for (i in 0..2) for (j in 0..2) rows.add(Triple(10.05 + i * 0.1, 20.05 + j * 0.1, "c$i$j"))
        FlockCameras.loadFromForTest(gzTsv(rows))
        val hit = FlockCameras.inBox(10.0, 20.0, 10.3, 20.3)
        assertEquals(9, hit.size)
    }

    @Test
    fun `swap to a larger generation keeps every query in bounds and correct`() {
        FlockCameras.loadFromForTest(gzTsv(listOf(Triple(40.0, -75.0, "old"))))
        assertEquals(1, FlockCameras.inBox(39.0, -76.0, 41.0, -74.0).size)
        // The refresh() path: a bigger dataset with MORE occupied cells replaces the index. Under
        // the torn-fields publish this was the crash shape (new keys, old starts); with a snapshot
        // it must simply answer from the new generation.
        val rows = (0 until 500).map { Triple(30.0 + it * 0.01, -100.0 + it * 0.01, "new$it") }
        FlockCameras.loadFromForTest(gzTsv(rows))
        assertEquals(500, FlockCameras.size)
        assertTrue(FlockCameras.inBox(39.0, -76.0, 41.0, -74.0).isEmpty()) // "old" is gone
        assertEquals(500, FlockCameras.inBox(29.0, -101.0, 36.0, -94.0).size)
    }

    @Test
    fun `along finds cameras near the route and only those`() {
        FlockCameras.loadFromForTest(
            gzTsv(
                listOf(
                    Triple(40.0005, -75.0, "on-route"), // ~55 m off the segment
                    Triple(40.05, -75.0, "far"),        // ~5.5 km off
                ),
            ),
        )
        val poly = listOf(
            app.vela.core.model.LatLng(40.0, -75.01),
            app.vela.core.model.LatLng(40.0, -74.99),
        )
        assertEquals(listOf("on-route"), FlockCameras.along(poly, meters = 120.0).map { it.operator })
    }

    @Test
    fun `malformed lines are skipped, not fatal`() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter().use { w ->
            w.write("not-a-number\t-75.0\topX\n")
            w.write("40.0\n")
            w.write("40.0\t-75.0\topGood\n")
        }
        FlockCameras.loadFromForTest(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(1, FlockCameras.size)
        assertEquals("opGood", FlockCameras.inBox(39.0, -76.0, 41.0, -74.0).single().operator)
    }
}
