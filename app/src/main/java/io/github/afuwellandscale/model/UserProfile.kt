package io.github.afuwellandscale.model

data class UserProfile(
    val age: Int = 23,
    val sex: String = "male",
    val heightCm: Int = 170,
    val unit: String = "kg",
) {
    val isMale: Boolean
        get() = sex.trim().lowercase() in setOf("male", "m", "man", "男", "1")

    val sexValueForProtocol: Int
        get() = if (isMale) 1 else 2

    val unitValue: Int
        get() = when (unit.trim().lowercase()) {
            "lb", "lbs", "pound" -> 1
            "st" -> 2
            "jin", "斤" -> 3
            else -> 0
        }
}
