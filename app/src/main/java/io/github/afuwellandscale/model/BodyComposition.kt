package io.github.afuwellandscale.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class BodyComposition(
    val model: String,
    val resistanceOhm: Double?,
    val bodyFatPercent: Double,
    val fatMassKg: Double,
    val fatFreeMassKg: Double,
    val musclePercent: Double,
    val muscleMassKg: Double,
    val waterPercent: Double,
    val waterMassKg: Double,
    val proteinPercent: Double,
    val proteinMassKg: Double,
    val bonePercent: Double,
    val boneMassKg: Double,
    val skeletalMusclePercent: Double,
    val skeletalMuscleMassKg: Double,
    val subcutaneousFatPercent: Double,
    val subcutaneousFatMassKg: Double,
)

object BodyCompositionEstimator {
    fun estimate(
        weightKg: Double,
        heightCm: Int,
        age: Int,
        sex: String,
        impedances: List<Double>,
    ): BodyComposition? {
        if (weightKg <= 0.0 || heightCm <= 0 || age <= 0) return null
        val isMale = sex.trim().lowercase() in setOf("male", "m", "man", "男", "1")
        val resistance = impedances.firstOrNull { it in 100.0..1500.0 }
        val (baseBodyFat, model, ffm) = if (resistance != null) {
            val (rawFfm, rawModel) = segalFfm(weightKg, heightCm.toDouble(), age, isMale, resistance)
            val clampedFfm = rawFfm.coerceIn(weightKg * 0.35, weightKg * 0.97)
            Triple(100.0 * (weightKg - clampedFfm) / weightKg, rawModel, clampedFfm)
        } else {
            val bmi = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
            val sexValue = if (isMale) 1 else 0
            val fat = (1.20 * bmi) + (0.23 * age) - (10.8 * sexValue) - 5.4
            Triple(fat, "deurenberg_bmi_fallback", weightKg * (1.0 - fat / 100.0))
        }

        val bodyFat = baseBodyFat.coerceIn(if (isMale) 3.0 else 8.0, 60.0)
        val fatMass = weightKg * bodyFat / 100.0
        val fatFreeMass = weightKg - fatMass
        val bonePercent = if (isMale) 4.5 else 4.0
        val musclePercent = (100.0 - bodyFat - bonePercent).coerceIn(20.0, 95.0)
        val leanPercent = 100.0 - bodyFat
        val waterPercent = (leanPercent * 0.70).coerceIn(25.0, 80.0)
        val proteinPercent = (leanPercent * 0.238).coerceIn(5.0, 35.0)
        val skeletalPercent = (musclePercent * 0.527).coerceIn(10.0, 70.0)
        val subcutaneousPercent = (bodyFat * 0.72).coerceIn(1.0, bodyFat)

        return BodyComposition(
            model = model,
            resistanceOhm = resistance?.round1(),
            bodyFatPercent = bodyFat.round1(),
            fatMassKg = kg(weightKg, bodyFat),
            fatFreeMassKg = fatFreeMass.round1(),
            musclePercent = musclePercent.round1(),
            muscleMassKg = kg(weightKg, musclePercent),
            waterPercent = waterPercent.round1(),
            waterMassKg = kg(weightKg, waterPercent),
            proteinPercent = proteinPercent.round1(),
            proteinMassKg = kg(weightKg, proteinPercent),
            bonePercent = bonePercent.round1(),
            boneMassKg = kg(weightKg, bonePercent),
            skeletalMusclePercent = skeletalPercent.round1(),
            skeletalMuscleMassKg = kg(weightKg, skeletalPercent),
            subcutaneousFatPercent = subcutaneousPercent.round1(),
            subcutaneousFatMassKg = kg(weightKg, subcutaneousPercent),
        )
    }

    private fun segalFfm(
        weightKg: Double,
        heightCm: Double,
        age: Int,
        isMale: Boolean,
        resistanceOhm: Double,
    ): Pair<Double, String> {
        val heightSq = heightCm * heightCm
        if (isMale) {
            var ffm = 9.33285 + (0.00066360 * heightSq) - (0.02117 * resistanceOhm) +
                (0.62854 * weightKg) - (0.12380 * age)
            val fat = 100.0 * (weightKg - ffm) / weightKg
            if (fat < 20.0) return ffm to "segal_3a_male_lt20"
            ffm = 14.52435 + (0.00088580 * heightSq) - (0.02999 * resistanceOhm) +
                (0.42688 * weightKg) - (0.07002 * age)
            return ffm to "segal_3b_male_ge20"
        }

        var ffm = 10.43485 + (0.00064602 * heightSq) - (0.01397 * resistanceOhm) +
            (0.42087 * weightKg)
        val fat = 100.0 * (weightKg - ffm) / weightKg
        if (fat < 30.0) return ffm to "segal_3c_female_lt30"
        ffm = 9.37938 + (0.00091186 * heightSq) - (0.01466 * resistanceOhm) +
            (0.29990 * weightKg) - (0.07012 * age)
        return ffm to "segal_3d_female_ge30"
    }

    private fun kg(weight: Double, percent: Double): Double = (weight * percent / 100.0).round1()

    private fun Double.round1(): Double = round(this * 10.0) / 10.0
}
