package io.github.afuwellandscale.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import io.github.afuwellandscale.model.Measurement
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectWriter(private val context: Context) {
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
    )

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    fun clientOrNull(): HealthConnectClient? {
        return if (availability() == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    suspend fun hasPermissions(): Boolean {
        val client = clientOrNull() ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun write(measurement: Measurement) {
        val client = clientOrNull() ?: error("Health Connect 不可用")
        client.insertRecords(recordsFor(measurement))
    }

    suspend fun writeAll(measurements: List<Measurement>) {
        val client = clientOrNull() ?: error("Health Connect 不可用")
        measurements.flatMap(::recordsFor).chunked(100).forEach { records ->
            client.insertRecords(records)
        }
    }

    private fun recordsFor(measurement: Measurement): List<Record> {
        val time = Instant.ofEpochMilli(measurement.timeMillis)
        val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(time)
        val records = mutableListOf<Record>(
            WeightRecord(
                metadata = metadata(measurement, "weight"),
                weight = Mass.kilograms(measurement.weightKg),
                time = time,
                zoneOffset = zoneOffset,
            ),
        )

        val composition = measurement.composition
        if (composition != null) {
            records += BodyFatRecord(
                metadata = metadata(measurement, "body-fat"),
                percentage = Percentage(composition.bodyFatPercent),
                time = time,
                zoneOffset = zoneOffset,
            )
            records += LeanBodyMassRecord(
                metadata = metadata(measurement, "lean-mass"),
                mass = Mass.kilograms(composition.fatFreeMassKg),
                time = time,
                zoneOffset = zoneOffset,
            )
            records += BodyWaterMassRecord(
                metadata = metadata(measurement, "body-water"),
                mass = Mass.kilograms(composition.waterMassKg),
                time = time,
                zoneOffset = zoneOffset,
            )
            records += BoneMassRecord(
                metadata = metadata(measurement, "bone-mass"),
                mass = Mass.kilograms(composition.boneMassKg),
                time = time,
                zoneOffset = zoneOffset,
            )
        }

        return records
    }

    private fun metadata(measurement: Measurement, type: String): Metadata {
        return Metadata.activelyRecorded(
            device = Device(
                Device.TYPE_SCALE,
                "Ant A-Fu Welland",
                "AFU-WL-TZ-A1",
            ),
            clientRecordId = healthClientRecordId(measurement.timeMillis, type),
            clientRecordVersion = 1,
        )
    }

    companion object {
        fun requestPermissionContract() = PermissionController.createRequestPermissionResultContract()
    }
}

internal fun healthClientRecordId(timeMillis: Long, type: String): String {
    return "afu-welland-$timeMillis-$type"
}
