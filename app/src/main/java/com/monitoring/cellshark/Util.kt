package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.File
import java.math.RoundingMode
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*


// FTP Globals
var FTP_Timeout                = FTP_DEFAULT_TIMEOUT
var FTP_KeepAliveTimeout: Long      = FTP_DEFAULT_KEEP_ALIVE
var FTP_isUploading                 = false
var FTP_SERVER_ACCESS               = true

// Global variable to keep UI elements accurate
var csRunning                       = false
var listLimit                  = LIST_DEFAULT_SIZE
var logBothMetrics                  = true


object Util {

    private var eventList: MutableList<Array<String>> = mutableListOf()
    private var secondaryEventList: MutableList<Array<String>> = mutableListOf()
    private var primaryListOccupied = false
    private val receivedVal = mutableListOf<Long>()
    private val transmittedVal = mutableListOf<Long>()
    private var totalFailedConnectionAttempts: Int = 0


    //File Variables
    private val parentDir = File("/storage/emulated/0/Android/data/com.monitoring.cellshark/files").absolutePath
    private val dataDir: String = File(parentDir + File.separator + "Data").absolutePath

    fun updateFailedConnectionAttempts(connectionResult: Boolean) {

        /*
        *   If unable to access FTP server after 2 hour, stop attempting every 30 seconds.
        *   Reset each day or reboot
         */

        Log.d("csDebug", "FTP Connection State: $FTP_SERVER_ACCESS \tFTP Failed Counts: $totalFailedConnectionAttempts")
        if (!connectionResult) {
            totalFailedConnectionAttempts++
            if (totalFailedConnectionAttempts >= CON_ATTEMPT_LIMIT) FTP_SERVER_ACCESS = false
        } else {
            FTP_SERVER_ACCESS = true
            totalFailedConnectionAttempts = 0
        }

    }

    fun addToEventList(eventLine: Array<String>) {
        if (!primaryListOccupied) {
            try {
                eventList.add(eventLine)
                checkListSize()
            } catch (e: Exception) {
                secondaryEventList.add(eventLine)
                e.printStackTrace()
            }
        } else {
            secondaryEventList.add(eventLine)
        }
    }

    private fun checkListSize() {
        //Keep list under the limit size
        if(eventList.size > listLimit) {
            val count = eventList.size - listLimit
            for(x in 1..count){
                eventList.removeAt(0)
            }
        }
    }

    fun getTimeStamp(): String {
        val time = SimpleDateFormat(DATE_FORMAT_SINGLE_EVENT, Locale.getDefault())
        time.timeZone = TimeZone.getTimeZone("UTC")
        return time.format(Date())
    }

    fun getEventList(): MutableIterator<Array<String>> {
        primaryListOccupied = true
        return eventList.iterator()
    }

    fun disOccupy() {
        secondaryEventList.forEach {
            eventList.add(it)
        }
        primaryListOccupied = false
        secondaryEventList.clear()
    }

    @SuppressLint("PrivateApi")


    fun saveTrafficStats() {

        try {
            val currentReceivedValue = TrafficStats.getTotalRxBytes()
            val currentTransValue = TrafficStats.getTotalTxBytes()

            if(receivedVal.isEmpty() || transmittedVal.isEmpty()) {
                receivedVal.add(0, currentReceivedValue)
                transmittedVal.add(0, currentTransValue)
            } else {
                val prevReceived = receivedVal.removeAt(0)
                val receivedDif = ((currentReceivedValue - prevReceived) / 1024.0).toBigDecimal().setScale(2, RoundingMode.UP).toDouble()
                receivedVal.add(0, currentReceivedValue)

                val prevTrans = transmittedVal.removeAt(0)
                val transmittedDif = ((currentTransValue - prevTrans) / 1024.0).toBigDecimal().setScale(2, RoundingMode.UP).toDouble()
                transmittedVal.add(0, currentTransValue)

                val newAr = arrayOf(SystemBytes, getTimeStamp(), receivedDif.toString(), transmittedDif.toString())

                addToEventList(newAr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun formatIP(ip: Int): String {
        return String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
        )
    }

    fun getPingTime(line: String): Double? {

        val timePatternRx = "(?<=time=)[0-9]?[0-9]?[0-9]?[0-9]?[0-9]".toRegex()
        val timeMatch = timePatternRx.find(line)
        return timeMatch?.value?.toDouble()

    }

    fun getWifiMacAddress(): String {
        try {
            val interfaceName = "wlan0"
            val interfaces: List<NetworkInterface> =
                Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.name.equals(interfaceName, true)) continue
                val mac: ByteArray = intf.hardwareAddress ?: return ""
                val buf = StringBuilder()
                for (aMac in mac) {
                    buf.append(String.format("%02X:", aMac))
                }
                if (buf.isNotEmpty()) {
                    buf.deleteCharAt(buf.length - 1)
                }
                return buf.toString()
            }
        } catch (ex: java.lang.Exception) {
        } // for now eat exceptions
        return ""
    }

    private fun getMACSerial(): String {

        var mac = getWifiMacAddress()
        mac = mac.replace(":", "")
        return mac

    }

    @SuppressLint("MissingPermission", "PrivateApi", "HardwareIds")
    fun getSerialNumber(): String? {
        var serialNumber: String?

        try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)

            serialNumber = get.invoke(c, "gsm.sn1") as String
            if (serialNumber == "")
                serialNumber = get.invoke(c, "ril.serialnumber") as String
            if (serialNumber == "")
                serialNumber = get.invoke(c, "ro.serialno") as String
            if (serialNumber == "")
                serialNumber = get.invoke(c, "sys.serialnumber") as String
            if (serialNumber == "")
                serialNumber = Build.SERIAL
            if (serialNumber == "")
                serialNumber = Build.getSerial()

            // If none of the methods above worked
            if (serialNumber == "")
                serialNumber = null

        } catch (e: Exception) {
            e.printStackTrace()
            serialNumber = try {
                Build.getSerial()
            }catch (e: java.lang.Exception) {
                e.printStackTrace()
                null
            }
        }

        if (serialNumber == "unknown") { serialNumber = getMACSerial() }

        return serialNumber
    }

    fun deleteAllFiles() {
        try {
            File(dataDir).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            saveLogData("File Deletion Attempt Crashed--StackTrace\n${e.stackTraceToString()}")
            e.printStackTrace()
        }
    }

    fun saveLogData(data: String) {

        //Get Date, Each file will be separated
        var dateObj = SimpleDateFormat(DATE_FORMAT_LOG, Locale.getDefault())
        val dateStr = dateObj.format(Date())
        dateObj = SimpleDateFormat(TIME_FORMAT_LOG, Locale.getDefault())
        val timeStr = dateObj.format(Date())
        val fileName = "$dateStr.txt"

        val newData = "Time: $timeStr\nLog Details\n-------------------------------\n$data\n"

        try {
            val path = File(parentDir + File.separator + "Logs").absolutePath
            File(path, fileName).appendText("\n$newData")
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    fun isWiFiNotNull(wm: WifiManager): Boolean {
        if(wm.connectionInfo.bssid != null && wm.connectionInfo.bssid != "02:00:00:00:00") {
            if(wm.connectionInfo.rssi > -100 && wm.connectionInfo.linkSpeed > 0) return true
        }
        return false
    }

}
