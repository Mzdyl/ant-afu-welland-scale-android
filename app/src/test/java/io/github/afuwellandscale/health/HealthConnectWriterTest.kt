package io.github.afuwellandscale.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HealthConnectWriterTest {
    @Test
    fun clientRecordIdIsStableForRepeatedSync() {
        val first = healthClientRecordId(1_784_456_573_294, "weight")
        val second = healthClientRecordId(1_784_456_573_294, "weight")

        assertEquals(first, second)
        assertEquals("afu-welland-1784456573294-weight", first)
    }

    @Test
    fun clientRecordIdSeparatesMeasurementsAndRecordTypes() {
        val weight = healthClientRecordId(1_000, "weight")

        assertNotEquals(weight, healthClientRecordId(1_000, "body-fat"))
        assertNotEquals(weight, healthClientRecordId(2_000, "weight"))
    }
}
