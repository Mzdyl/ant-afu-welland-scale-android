package io.github.afuwellandscale.ble

import io.github.afuwellandscale.model.UserProfile
import io.github.afuwellandscale.util.bit
import io.github.afuwellandscale.util.setBit
import io.github.afuwellandscale.util.toBytes2
import io.github.afuwellandscale.util.toBytes4
import io.github.afuwellandscale.util.u16
import io.github.afuwellandscale.util.u32
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime

private const val PACKET_WEIGHT = 213
private const val PACKET_ADC = 214

sealed class ScalePacketEvent {
    data class Weight(
        val weightKg: Double,
        val stable: Boolean,
        val algType: Int,
    ) : ScalePacketEvent()

    data class Adc(
        val adcs: List<Double>,
        val impedances: List<Double>,
        val weightKg: Double?,
        val stable: Boolean,
        val algType: Int,
    ) : ScalePacketEvent()
}

class Scale27Protocol {
    fun decode(packet: ByteArray): List<ScalePacketEvent> {
        if (packet.size < 20) return emptyList()
        val first = packet[1].toInt() and 0xFF
        if (first.bit(7)) return emptyList()
        val body = packet.copyOfRange(2, 20)
        val checksum = body.copyOfRange(0, 17).sumOf { it.toInt() and 0xFF } and 0x1F
        if (checksum != ((body[17].toInt() and 0xFF) and 0x1F)) return emptyList()

        val packetType = body[16].toInt() and 0xFF
        val payload = ByteArrayOutputStream().apply {
            write(first)
            write(body.copyOfRange(0, 16))
            write((body[17].toInt() and 0xE0) shr 5)
        }.toByteArray()

        return when (packetType) {
            PACKET_WEIGHT -> decodeWeight(payload)
            PACKET_ADC -> decodeAdc(payload)
            else -> emptyList()
        }
    }

    fun encodeUserInfo(deviceType: Int, protocolVer: Int, profile: UserProfile): ByteArray {
        val now = OffsetDateTime.now()
        val utcQuarters = (now.offset.totalSeconds / 900) and 0xFF
        val userIndex = 1
        val peopleType = 0
        var profileWeight = float2int(0.0, 2)
        if (protocolVer in setOf(1, 2) && peopleType == 1) {
            profileWeight = setBit(profileWeight, 15)
        }
        val payload = ByteArrayOutputStream().apply {
            write(0xAC)
            write(deviceType and 0xFF)
            write(now.toEpochSecond().toBytes4())
            write(utcQuarters)
            write(profile.unitValue)
            write(userIndex)
            write(profile.heightCm)
            write(profileWeight.toBytes2())
            write(profile.age)
            write(profile.sexValueForProtocol)
            write(0.toBytes2())
            write(defaultFunctionFlags())
            write(0)
            write(0xD0)
        }.toByteArray()
        return splitOutgoing(payload)
    }

    private fun decodeWeight(payload: ByteArray): List<ScalePacketEvent> {
        if (payload.size < 8) return emptyList()
        val encoded = u32(payload, 1).toInt()
        val fields = weightFields(encoded)
        val algType = payload[payload.lastIndex - 1].toInt() and 0xFF
        return listOf(
            ScalePacketEvent.Weight(
                weightKg = fields.weightKg,
                stable = fields.state,
                algType = algType,
            ),
        )
    }

    private fun decodeAdc(payload: ByteArray): List<ScalePacketEvent> {
        if (payload.size < 5 || ((payload[0].toInt() and 0xFF).bit(7))) return emptyList()
        val count = payload[1].toInt() and 0xFF
        var offset = 3
        val adcs = mutableListOf<Double>()
        repeat(count) {
            if (offset + 2 > payload.size) return emptyList()
            adcs += u16(payload, offset).toDouble()
            offset += 2
        }
        if (offset >= payload.size) return emptyList()
        val mode = payload[offset].toInt() and 0xFF
        offset += 1
        var weightKg: Double? = null
        var stable = false
        if (mode == 1 && offset + 4 <= payload.size) {
            val fields = weightFields(u32(payload, offset).toInt())
            weightKg = fields.weightKg
            stable = fields.state
        }
        val algType = payload[payload.lastIndex - 1].toInt() and 0xFF
        val impedances = normalizeImpedances(adcs, weightKg)
        return listOf(
            ScalePacketEvent.Adc(
                adcs = adcs,
                impedances = impedances,
                weightKg = weightKg,
                stable = stable,
                algType = algType,
            ),
        )
    }

    private data class WeightFields(
        val weightKg: Double,
        val state: Boolean,
    )

    private fun weightFields(encoded: Int): WeightFields {
        val grams = encoded and 0x3FFFF
        val kgDivision = (encoded and 0x1C0000) shr 18
        val precision = if (kgDivision in listOf(0, 1, 2)) 2 else 1
        val weightKg = g2kgGeneral(grams.toDouble(), kgDivision, precision)
        return WeightFields(
            weightKg = weightKg,
            state = encoded.bit(31),
        )
    }

    private fun normalizeImpedances(adcs: List<Double>, weightKg: Double?): List<Double> {
        if (adcs.size == 5) {
            return listOf(adcs[4], adcs[0], adcs[1], adcs[2], adcs[3]).map { round2(it) }
        }
        return adcs.map { value ->
            var adjusted = value
            if (adjusted >= 1500.0 && (weightKg ?: 0.0) > 0.0) {
                adjusted = (((adjusted - 1000.0) + (((weightKg ?: 0.0) * 10.0) * -0.4)) / 0.6) / 10.0
            }
            round2(adjusted)
        }
    }

    private fun g2kgGeneral(grams: Double, division: Int, precision: Int): Double {
        return gunitGeneral(grams / 1000.0, division, precision)
    }

    private fun gunitGeneral(value: Double, division: Int, precision: Int): Double {
        return when (division) {
            0 -> (((getInt(value * 1000.0) + 5) / 10).toInt()) / 100.0
            1 -> {
                var raw = getInt(value * 1000.0)
                if (raw % 10 == 9) raw += 10
                var scaled = raw / 10
                if (scaled % 2 != 0) scaled += 1
                scaled / 100.0
            }
            2 -> {
                val raw = ((getInt(value * 1000.0) + 20) / 10).toInt()
                val scaled = if (raw % 10 >= 5) ((raw / 10) * 10) + 5 else (raw / 10) * 10
                scaled / 100.0
            }
            3 -> (((getInt(value * 100.0) + 5) / 10).toInt()) / 10.0
            4 -> {
                var raw = getInt(value * 100.0)
                if (raw % 10 == 9) raw += 10
                var scaled = raw / 10
                if (scaled % 2 != 0) scaled += 1
                scaled / 10.0
            }
            else -> "%.${precision}f".format(value).toDouble()
        }
    }

    private fun getInt(value: Double): Int {
        val whole = value.toInt()
        return if (((value * 10.0).toInt() % 10) >= 9) whole + 1 else whole
    }

    private fun float2int(value: Double, precision: Int): Int {
        val factor = Math.pow(10.0, (precision + 1).toDouble()).toInt()
        var scaled = (value * factor).toInt()
        if ((value * factor) - scaled >= 0.8) scaled += 10
        return scaled / 10
    }

    private fun defaultFunctionFlags(): Int {
        var flags = 0
        flags = setBit(flags, 0)
        flags = setBit(flags, 1)
        return flags
    }

    private fun splitOutgoing(payload: ByteArray): ByteArray {
        val data = if (payload.size < 19) payload + ByteArray(19 - payload.size) else payload
        val checksum = data.copyOfRange(2, 19).sumOf { it.toInt() and 0xFF } and 0xFF
        return data + checksum.toByte()
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
}
