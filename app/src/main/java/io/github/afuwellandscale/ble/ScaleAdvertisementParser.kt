package io.github.afuwellandscale.ble

import android.bluetooth.le.ScanRecord
import androidx.core.util.size
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.util.hex
import java.util.Locale
import java.util.UUID

object ScaleAdvertisementParser {
    private const val PREFIX = "AFU-WL"
    private const val EXPECTED_SUBTYPE = 7

    data class ParseOutcome(
        val device: ScaleDevice?,
        val reason: String,
        val summary: String,
    )

    fun parse(name: String?, address: String, rssi: Int, record: ScanRecord?): ScaleDevice? {
        return parseDetailed(name, address, rssi, record).device
    }

    fun parseDetailed(name: String?, address: String, rssi: Int, record: ScanRecord?): ParseOutcome {
        val raw = RawAdvertisement.from(record)
        val normalizedName = (name?.trim().orEmpty()).ifBlank { raw.localName.orEmpty() }
        val summary = raw.summary()

        if (!normalizedName.startsWith(PREFIX, ignoreCase = true)) {
            return ParseOutcome(null, "名称不匹配: '${normalizedName.ifBlank { "unknown" }}'", summary)
        }

        val manufacturer = raw.candidates.let { candidates ->
            if (candidates.size == 2) listOf(candidates[1]) else candidates
        }.firstOrNull { it.size >= 2 && (it[0].toInt() and 0xFF) == 0xAC }

        if (manufacturer == null) {
            return ParseOutcome(null, "名称匹配但没有 AC 广播数据", summary)
        }

        val flags = manufacturer.getOrNull(1)?.toInt()?.and(0xFF)
            ?: return ParseOutcome(null, "AC 广播数据太短: ${manufacturer.hex()}", summary)
        val category = (flags shr 4) and 0x07
        val subtype = flags and 0x0F
        if (category != 2 || subtype != EXPECTED_SUBTYPE) {
            return ParseOutcome(null, "设备类型不匹配: flags=0x%02X category=%d subtype=%d".format(flags, category, subtype), summary)
        }

        val actualMac = if (manufacturer.size >= 8) {
            byteArrayOf(
                manufacturer[7],
                manufacturer[6],
                manufacturer[5],
                manufacturer[4],
                manufacturer[3],
                manufacturer[2],
            ).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        } else {
            null
        }

        return ParseOutcome(
            device = ScaleDevice(
                address = address,
                name = normalizedName,
                rssi = rssi,
                manufacturerDataHex = manufacturer.hex(),
                actualMac = actualMac?.uppercase(Locale.US),
                deviceSubtype = subtype,
                protocolVer = manufacturer.getOrNull(8)?.toInt()?.and(0xFF) ?: 1,
                protocolDeviceType = flags,
            ),
            reason = "匹配成功: AC=${manufacturer.hex()}",
            summary = summary,
        )
    }

    private data class RawAdvertisement(
        val localName: String?,
        val candidates: List<ByteArray>,
        val rawHex: String,
    ) {
        fun summary(): String {
            val candidateText = candidates.joinToString(prefix = "[", postfix = "]") { it.hex() }
            return "raw=$rawHex candidates=$candidateText"
        }

        companion object {
            fun from(record: ScanRecord?): RawAdvertisement {
                if (record == null) return RawAdvertisement(null, emptyList(), "")

                val candidates = mutableListOf<ByteArray>()
                for (index in 0 until record.manufacturerSpecificData.size) {
                    val companyId = record.manufacturerSpecificData.keyAt(index)
                    val payload = record.manufacturerSpecificData.valueAt(index) ?: continue
                    val prefix = byteArrayOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte())
                    candidates += prefix + payload
                    if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0xAC) {
                        candidates += payload
                    }
                }

                val serviceData = record.serviceData
                if (serviceData != null) {
                    for ((uuid, payload) in serviceData) {
                        val uuid16 = uuid16FromUuid(uuid.uuid) ?: continue
                        val keyHex = "%04X".format(uuid16)
                        if (!keyHex.contains("AC")) continue
                        candidates += reconstructServiceData(uuid16, payload)
                    }
                }

                val rawBytes = record.bytes ?: ByteArray(0)
                val parsedRaw = parseRaw(rawBytes)
                candidates += parsedRaw.candidates
                return RawAdvertisement(
                    localName = record.deviceName ?: parsedRaw.localName,
                    candidates = candidates.distinctBy { it.hex() },
                    rawHex = rawBytes.hex(),
                )
            }

            private fun parseRaw(bytes: ByteArray): RawAdvertisement {
                var offset = 0
                var localName: String? = null
                val candidates = mutableListOf<ByteArray>()

                while (offset < bytes.size) {
                    val length = bytes[offset].toInt() and 0xFF
                    if (length == 0) break
                    val end = offset + 1 + length
                    if (end > bytes.size) break
                    val type = bytes[offset + 1].toInt() and 0xFF
                    val dataStart = offset + 2
                    val dataEnd = end
                    when (type) {
                        0x08, 0x09 -> {
                            if (dataEnd > dataStart) {
                                localName = bytes.copyOfRange(dataStart, dataEnd).toString(Charsets.UTF_8)
                            }
                        }
                        0x16 -> {
                            if (dataEnd - dataStart >= 2) {
                                val uuid16 = (bytes[dataStart].toInt() and 0xFF) or
                                    ((bytes[dataStart + 1].toInt() and 0xFF) shl 8)
                                val keyHex = "%04X".format(uuid16)
                                if (keyHex.contains("AC")) {
                                    candidates += reconstructServiceData(uuid16, bytes.copyOfRange(dataStart + 2, dataEnd))
                                }
                            }
                        }
                        0xFF -> {
                            if (dataEnd - dataStart >= 2) {
                                candidates += bytes.copyOfRange(dataStart, dataEnd)
                            }
                        }
                    }
                    offset = end
                }

                return RawAdvertisement(localName, candidates, bytes.hex())
            }
        }
    }

    private fun uuid16FromUuid(uuid: UUID): Int? {
        val text = uuid.toString().lowercase(Locale.US)
        if (text.length >= 8 && text.substring(4, 8) == "0000") return null
        if (text.startsWith("0000") && text.contains("-0000-1000-8000-00805f9b34fb")) {
            return text.substring(4, 8).toIntOrNull(16)
        }
        return null
    }

    private fun reconstructServiceData(uuid16: Int, payload: ByteArray): ByteArray {
        val prefix = byteArrayOf((uuid16 and 0xFF).toByte(), ((uuid16 shr 8) and 0xFF).toByte())
        val keyHex = "%04X".format(uuid16)
        if (keyHex.contains("2C")) {
            val defaults = byteArrayOf(0x33, 0x91.toByte(), 0x1E, 0x1A, 0x0A, 0x01, 0xFF.toByte(), 0x00, 0x00, 0x44, 0x00)
            if (payload.size <= 6) {
                return prefix + payload + defaults
            }
            if (payload.size > 6 && payload[6].toInt() == 0) {
                return prefix + payload.copyOfRange(0, 6) + defaults
            }
        }
        return prefix + payload
    }

}
