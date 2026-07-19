package io.github.afuwellandscale.storage

import android.content.Context
import io.github.afuwellandscale.model.Measurement
import org.json.JSONArray
import org.json.JSONObject

class MeasurementStore(context: Context) {
    private val appContext = context.applicationContext

    fun save(measurement: Measurement) {
        appContext.openFileOutput("measurements.jsonl", Context.MODE_APPEND).bufferedWriter().use { writer ->
            writer.append(toJson(measurement).toString())
            writer.newLine()
        }
    }

    fun latestLines(limit: Int = 20): List<String> {
        val file = appContext.getFileStreamPath("measurements.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(limit)
    }

    fun all(): List<Measurement> {
        val file = appContext.getFileStreamPath("measurements.jsonl")
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { fromJson(JSONObject(line)) }.getOrNull() }
                .toList()
                .asReversed()
        }
    }

    fun latest(): Measurement? {
        val file = appContext.getFileStreamPath("measurements.jsonl")
        if (!file.exists()) return null
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { fromJson(JSONObject(line)) }.getOrNull() }
                .lastOrNull()
        }
    }

    fun delete(timeMillis: Long) {
        rewrite(all().filterNot { it.timeMillis == timeMillis }.asReversed())
    }

    fun clear() {
        appContext.deleteFile("measurements.jsonl")
    }

    private fun toJson(measurement: Measurement): JSONObject {
        val obj = JSONObject()
            .put("timeMillis", measurement.timeMillis)
            .put("weightKg", measurement.weightKg)
            .put("bmi", measurement.bmi)
            .put("stable", measurement.stable)
            .put("rawAdc", JSONArray(measurement.rawAdc))
            .put("impedances", JSONArray(measurement.impedances))
            .put("algType", measurement.algType)

        measurement.composition?.let {
            obj.put(
                "composition",
                JSONObject()
                    .put("model", it.model)
                    .put("resistanceOhm", it.resistanceOhm)
                    .put("bodyFatPercent", it.bodyFatPercent)
                    .put("fatMassKg", it.fatMassKg)
                    .put("fatFreeMassKg", it.fatFreeMassKg)
                    .put("musclePercent", it.musclePercent)
                    .put("muscleMassKg", it.muscleMassKg)
                    .put("waterPercent", it.waterPercent)
                    .put("waterMassKg", it.waterMassKg)
                    .put("proteinPercent", it.proteinPercent)
                    .put("proteinMassKg", it.proteinMassKg)
                    .put("bonePercent", it.bonePercent)
                    .put("boneMassKg", it.boneMassKg)
                    .put("skeletalMusclePercent", it.skeletalMusclePercent)
                    .put("skeletalMuscleMassKg", it.skeletalMuscleMassKg)
                    .put("subcutaneousFatPercent", it.subcutaneousFatPercent)
                    .put("subcutaneousFatMassKg", it.subcutaneousFatMassKg),
            )
        }
        return obj
    }

    private fun fromJson(obj: JSONObject): Measurement {
        return Measurement(
            timeMillis = obj.getLong("timeMillis"),
            weightKg = obj.getDouble("weightKg"),
            bmi = obj.getDouble("bmi"),
            stable = obj.optBoolean("stable", true),
            rawAdc = obj.optJSONArray("rawAdc").toDoubleList(),
            impedances = obj.optJSONArray("impedances").toDoubleList(),
            algType = obj.optInt("algType", 0),
            composition = obj.optJSONObject("composition")?.let(::compositionFromJson),
        )
    }

    private fun compositionFromJson(obj: JSONObject): io.github.afuwellandscale.model.BodyComposition {
        return io.github.afuwellandscale.model.BodyComposition(
            model = obj.getString("model"),
            resistanceOhm = obj.optNullableDouble("resistanceOhm"),
            bodyFatPercent = obj.getDouble("bodyFatPercent"),
            fatMassKg = obj.getDouble("fatMassKg"),
            fatFreeMassKg = obj.getDouble("fatFreeMassKg"),
            musclePercent = obj.getDouble("musclePercent"),
            muscleMassKg = obj.getDouble("muscleMassKg"),
            waterPercent = obj.getDouble("waterPercent"),
            waterMassKg = obj.getDouble("waterMassKg"),
            proteinPercent = obj.getDouble("proteinPercent"),
            proteinMassKg = obj.getDouble("proteinMassKg"),
            bonePercent = obj.getDouble("bonePercent"),
            boneMassKg = obj.getDouble("boneMassKg"),
            skeletalMusclePercent = obj.getDouble("skeletalMusclePercent"),
            skeletalMuscleMassKg = obj.getDouble("skeletalMuscleMassKg"),
            subcutaneousFatPercent = obj.getDouble("subcutaneousFatPercent"),
            subcutaneousFatMassKg = obj.getDouble("subcutaneousFatMassKg"),
        )
    }

    private fun JSONArray?.toDoubleList(): List<Double> {
        if (this == null) return emptyList()
        return (0 until length()).map { getDouble(it) }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return getDouble(name)
    }

    private fun rewrite(measurements: List<Measurement>) {
        if (measurements.isEmpty()) {
            clear()
            return
        }
        appContext.openFileOutput("measurements.jsonl", Context.MODE_PRIVATE).bufferedWriter().use { writer ->
            measurements.forEach { measurement ->
                writer.append(toJson(measurement).toString())
                writer.newLine()
            }
        }
    }
}
