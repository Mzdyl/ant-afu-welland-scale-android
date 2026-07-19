package io.github.afuwellandscale.storage

import android.content.Context
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.model.UserProfile

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun loadUserProfile(): UserProfile {
        return UserProfile(
            age = prefs.getInt("age", 23),
            sex = prefs.getString("sex", "male") ?: "male",
            heightCm = prefs.getInt("height_cm", 170),
            unit = prefs.getString("unit", "kg") ?: "kg",
        )
    }

    fun saveUserProfile(profile: UserProfile) {
        prefs.edit()
            .putInt("age", profile.age)
            .putString("sex", profile.sex)
            .putInt("height_cm", profile.heightCm)
            .putString("unit", profile.unit)
            .apply()
    }

    fun saveDevice(device: ScaleDevice) {
        prefs.edit()
            .putString("device_address", device.address)
            .putString("device_name", device.name)
            .putString("device_mac", device.actualMac)
            .putInt("device_subtype", device.deviceSubtype)
            .putInt("device_protocol_ver", device.protocolVer)
            .putInt("device_protocol_device_type", device.protocolDeviceType)
            .apply()
    }

    fun loadSavedDeviceAddress(): String? = prefs.getString("device_address", null)

    fun loadSavedDevice(): ScaleDevice? {
        val address = prefs.getString("device_address", null) ?: return null
        return ScaleDevice(
            address = address,
            name = prefs.getString("device_name", "AFU-WL-TZ-A1") ?: "AFU-WL-TZ-A1",
            rssi = null,
            manufacturerDataHex = "",
            actualMac = prefs.getString("device_mac", null),
            deviceSubtype = prefs.getInt("device_subtype", 7),
            protocolVer = prefs.getInt("device_protocol_ver", 1),
            protocolDeviceType = prefs.getInt("device_protocol_device_type", 0x27),
        )
    }
}
