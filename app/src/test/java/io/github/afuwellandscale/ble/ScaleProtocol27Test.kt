package io.github.afuwellandscale.ble

import io.github.afuwellandscale.model.BodyCompositionEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScaleProtocol27Test {
    @Test
    fun decodesKnownStableWeightPacket() {
        val events = Scale27Protocol().decode(hex("AC298068E2C2020005400000000000000029D511"))
        val event = events.single() as ScalePacketEvent.Weight

        assertEquals(58.05, event.weightKg, 0.001)
        assertEquals(true, event.stable)
        assertEquals(41, event.algType)
    }

    @Test
    fun decodesKnownAdcAndEstimatesComposition() {
        val events = Scale27Protocol().decode(hex("AC290200020501D8018068E2C20000000029D60E"))
        val event = events.single() as ScalePacketEvent.Adc

        assertEquals(listOf(517.0, 472.0), event.adcs)
        assertEquals(58.05, event.weightKg!!, 0.001)
        val composition = BodyCompositionEstimator.estimate(
            weightKg = event.weightKg!!,
            heightCm = 170,
            age = 23,
            sex = "male",
            impedances = event.impedances,
        )
        assertNotNull(composition)
        assertEquals(11.8, composition!!.bodyFatPercent, 0.1)
    }

    private fun hex(value: String): ByteArray {
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
