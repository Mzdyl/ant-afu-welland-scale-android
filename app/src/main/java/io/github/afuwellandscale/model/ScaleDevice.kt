package io.github.afuwellandscale.model

data class ScaleDevice(
    val address: String,
    val name: String,
    val rssi: Int?,
    val manufacturerDataHex: String,
    val actualMac: String?,
    val deviceSubtype: Int,
    val protocolVer: Int,
    val protocolDeviceType: Int,
)
