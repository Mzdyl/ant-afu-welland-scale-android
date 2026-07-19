package io.github.afuwellandscale.model

data class Measurement(
    val timeMillis: Long,
    val weightKg: Double,
    val bmi: Double,
    val stable: Boolean,
    val rawAdc: List<Double> = emptyList(),
    val impedances: List<Double> = emptyList(),
    val algType: Int = 0,
    val composition: BodyComposition? = null,
) {
    fun summary(): String {
        val lines = mutableListOf<String>()
        lines += if (stable) {
            "体重稳定: %.2f kg，BMI %.2f".format(weightKg, bmi)
        } else {
            "测量中: %.2f kg，BMI %.2f".format(weightKg, bmi)
        }
        if (rawAdc.isNotEmpty()) {
            lines += "阻抗/ADC: raw=${rawAdc.joinToString(prefix = "[", postfix = "]") { "%.0f".format(it) }}，阻抗=${impedances.joinToString(prefix = "[", postfix = "]") { "%.1f".format(it) }}"
        }
        composition?.let {
            lines += "体脂估算: %.1f%% / %.1f kg（%s）".format(it.bodyFatPercent, it.fatMassKg, it.model)
            lines += "肌肉率: %.1f%% / %.1f kg；骨骼肌: %.1f%% / %.1f kg".format(
                it.musclePercent,
                it.muscleMassKg,
                it.skeletalMusclePercent,
                it.skeletalMuscleMassKg,
            )
            lines += "水分: %.1f%% / %.1f kg；蛋白质: %.1f%% / %.1f kg；骨量: %.1f%% / %.1f kg".format(
                it.waterPercent,
                it.waterMassKg,
                it.proteinPercent,
                it.proteinMassKg,
                it.bonePercent,
                it.boneMassKg,
            )
            lines += "皮下脂肪: %.1f%% / %.1f kg".format(it.subcutaneousFatPercent, it.subcutaneousFatMassKg)
        }
        return lines.joinToString("\n")
    }
}
