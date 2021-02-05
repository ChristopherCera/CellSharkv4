package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.content.Context
import android.net.TrafficStats
import android.net.wifi.WifiManager
import java.text.SimpleDateFormat
import java.util.*
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.jetbrains.anko.doAsync
import java.io.*
import java.math.RoundingMode
import java.net.NetworkInterface


//New up to date endpoints
val axEndPoints = arrayOf("connectivitycheck.android.com", "echo.augmedix.com",
    "www.google.com", "mcu4.augmedix.com")


//New Variables
const val PINGv2                      = "PINGv2"
const val SystemBytes               = "SystemBytes"
const val BatteryState              = "BatteryState"

//Deprecating Soon
const val BATT_PERCENT              = "BATTERY_PERCENT"
const val BATT_CHARGING_STATE       = "BATTERY_CHARGING_STATE"
const val PING                      = "PING"

const val MINUTE: Int               = 60 //SECONDS
const val SUPPLICANT                = "SupplicantState"
const val WIFI                      = "WIFI"
const val PING_GATEWAY              = "Default_Gateway_Ping"
const val LTE                       = "LTEv2"
const val SYSTEM                    = "SYSTEM"
const val REBOOT_EVENT              = "REBOOT"
const val TIME_FORMAT_LOG           = "h:mm z"
const val DATE_FORMAT_FILE          = "Mddkkmmss"
const val DATE_FORMAT_LOG           = "M-dd"
const val DATE_FORMAT_SINGLE_EVENT  = "MM/dd/yyyy'T'HH:mm:ss.SSS"
const val TIME_FORMAT_MICRO         = "HH:mm:ss.SSS z"
const val ACTIVITY_INTENT_KEY       = "runService"
const val TRANSMITTED_BYTES         = "BytesTransmitted"
const val RECEIVED_BYTES            = "BytesReceived"
const val WIFI_CONNECTION_STATE     = "WiFiConnectionState"
const val LTE_CONNECTION_STATE      = "LteConnectionState"
const val INTERFACE_STATE           = "InterfaceState"
const val VERSION                   = "android_version"
const val MAC                       = "device_mac_address"

const val AX_DOC_VERSION            = "AX_version"
const val AX_PKG_NAME               = "com.augmedix.phone.prod"
const val startWorkWeekDay     = Calendar.MONDAY
const val endWorkWeekDay       = Calendar.FRIDAY
const val startWorkWeekHour         = 6
const val endWorkWeekHour           = 20
const val CHANNEL_ID                = "com.monitor.cellshark"

//FTP Variables
const val FTP_ADDRESS               = "cellshark.augmedix.com"
const val FTP_PORT                  = 1161
const val username                  = "cs"
const val password                  = "btQ3Q3RPu9"
var FTP_Timeout                     = 15000
var FTP_KeepAliveTimeout: Long      = 30000
var FTP_isUploading                 = false


// Global variable to keep UI elements accurate
var csRunning = false

object Util {
    var listLimit                 = 70
    private var eventList: MutableList<Array<String>> = mutableListOf()
    private var secondaryEventList: MutableList<Array<String>> = mutableListOf()

    var primaryListOccupied = false
    var FTP_SERVER_ACCESS = true
    private val receivedVal = mutableListOf<Long>()
    private val transmittedVal = mutableListOf<Long>()
    private var ftpCount: Int = 0

    //File Variables
    private val parentDir = File("/storage/emulated/0/Android/data/com.monitoring.cellshark/files").absolutePath
//    private val parentDir = File("/storage/emulated/0/Android/data/com.monitoring.cellguppie/files").absolutePath
    val dataDir: String = File(parentDir + File.separator + "Data").absolutePath

    fun updateFTPConnection(result: Boolean) {

        /*
        *   If unable to access FTP server after 1 hour, stop attempting every 30 seconds.
        *   Reset each day or reboot
         */
        Log.d("csDebug", "FTP Connection State: $FTP_SERVER_ACCESS \tFTP Failed Counts: $ftpCount")
        if (!result) {
            ftpCount++
            if (ftpCount >= 120) FTP_SERVER_ACCESS = false
        } else {
            FTP_SERVER_ACCESS = true
            ftpCount = 0
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

                addToEventList(arrayOf(SYSTEM, getTimeStamp(), RECEIVED_BYTES, receivedDif.toString()))
                addToEventList(arrayOf(SYSTEM, getTimeStamp(), TRANSMITTED_BYTES, transmittedDif.toString()))
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

    @SuppressLint("MissingPermission", "PrivateApi", "HardwareIds")
    fun getSerialNumber(context: Context): String? {
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

        if (serialNumber == "unknown") { serialNumber = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }

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

    fun isWiFiConnected(wm: WifiManager): Boolean {
        if(wm.connectionInfo.bssid != null && wm.connectionInfo.bssid != "02:00:00:00:00") {
            if(wm.connectionInfo.rssi > -100 && wm.connectionInfo.linkSpeed > 0) return true
        }
        return false
    }

}
