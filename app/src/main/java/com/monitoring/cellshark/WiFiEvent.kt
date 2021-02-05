package com.monitoring.cellshark

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.text.format.Formatter
import android.util.Log

class WiFiEvent(connectionInfo: WifiInfo, scanResult: MutableList<ScanResult>, timestamp: String) {

    private var neighborApList: MutableList<ScanResult> = mutableListOf()
    private var connectedSSID: String = connectionInfo.ssid.replace("\"", "")
    private var connectedBSSID: String = connectionInfo.bssid.replace("\"", "")
    var csvLine: Array<String>
    private var ipAddress: String = Formatter.formatIpAddress(connectionInfo.ipAddress)

    init {

        val connectedChannel: Int = getChannel(connectionInfo.frequency)
        val signalStrength = if (connectionInfo.rssi > -90) connectionInfo.rssi else 0
        val linkSpeed: Int = if (connectionInfo.linkSpeed >= 0 ) connectionInfo.linkSpeed else 0

        // "WIFI", datetime, device_ip, ssid, bssid, signal_strength, linkspeed, connected_channel

        scanResult.forEach {
            if (it.SSID == connectedSSID && it.BSSID != connectedBSSID) neighborApList.add(it)
        }

        val tempLine: MutableList<String> = mutableListOf(
            WIFI,
            timestamp, ipAddress, connectedSSID, connectedBSSID,
            signalStrength.toString(), connectedChannel.toString(), linkSpeed.toString()
        )

        //===================NEIGHBOR LIST==============================//
        //NEIGHBOR ONE (CLOSEST NEIGHBOR)
        if (neighborApList.getOrNull(0) != null) {
            tempLine.add(neighborApList[0].BSSID)
            tempLine.add(neighborApList[0].level.toString())
            tempLine.add(getChannel(neighborApList[0].frequency).toString())
        } else {
            tempLine.add("0")
            tempLine.add("0")
            tempLine.add("0")
        }

        for (index in 1..4) {
            if (neighborApList.getOrNull(index) != null) {
                tempLine.add(neighborApList[index].BSSID)
                tempLine.add(neighborApList[index].level.toString())
            } else {
                tempLine.add("0")
                tempLine.add("0")
            }
        }

        csvLine = tempLine.toTypedArray()
//        Log.d("WiFiEvent Details", "$tempLine")

    }

    private fun getChannel(channel: Int?): Int {

        when (channel) {

            2412 -> return 1
            2417 -> return 2
            2422 -> return 3
            2427 -> return 4
            2432 -> return 5
            2437 -> return 6
            2442 -> return 7
            2447 -> return 8
            2452 -> return 9
            2457 -> return 10
            2462 -> return 11
            5180 -> return 36
            5190 -> return 38
            5200 -> return 40
            5210 -> return 42
            5220 -> return 44
            5230 -> return 46
            5240 -> return 48
            5250 -> return 50
            5260 -> return 52
            5270 -> return 54
            5280 -> return 56
            5290 -> return 58
            5300 -> return 60
            5310 -> return 62
            5320 -> return 64
            5500 -> return 100
            5510 -> return 102
            5520 -> return 104
            5530 -> return 106
            5540 -> return 108
            5550 -> return 110
            5560 -> return 112
            5570 -> return 114
            5580 -> return 116
            5590 -> return 118
            5600 -> return 120
            5610 -> return 122
            5620 -> return 124
            5630 -> return 126
            5640 -> return 128
            5650 -> return 130
            5660 -> return 132
            5670 -> return 134
            5680 -> return 136
            5690 -> return 138
            5700 -> return 140
            5710 -> return 142
            5720 -> return 144
            5745 -> return 149
            5755 -> return 151
            5765 -> return 153
            5775 -> return 155
            5785 -> return 157
            5795 -> return 159
            5805 -> return 161
            5825 -> return 165
            else -> return 0

        }

    }

}