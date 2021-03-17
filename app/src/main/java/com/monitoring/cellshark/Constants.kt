package com.monitoring.cellshark

import java.util.*

//New up to date endpoints
val axEndPoints = arrayOf("connectivitycheck.android.com", "echo.augmedix.com",
        "www.google.com", "mcu4.augmedix.com")

//New Variables
const val DATA_DIRECTORY_NAME       = "Data"
const val LOGS_DIRECTORY_NAME       = "Logs"
const val APPLICATION_NAME          = "CellShark"
const val LIST_DEFAULT_SIZE         = 30
const val FTP_DEFAULT_TIMEOUT       = 15000
const val FTP_DEFAULT_KEEP_ALIVE= 30000.toLong()
const val WIFI_INT                  = "WiFi"
const val LTE_INT                   = "LTE"
const val BATTERY_INFO              = "BatteryInfo"
const val BATTERY_USAGE_RATE        = "BatteryRate"
const val FILE_SIZE_LIMIT           = 250
const val TOTAL_FILE_LIMIT          = 30
const val CELLSHARK_HTTP_LINK       = "http://cellshark.augmedix.com:1200/upload"
const val CON_ATTEMPT_LIMIT         = 60

//Current
const val PINGv2                    = "PINGv2"
const val SystemBytes               = "SystemBytes"
const val BatteryState              = "BatteryState"
const val SystemMac                 = "Version_MAC"
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
const val ACTIVITY_INTENT_KEY       = "runService"
const val WIFI_CONNECTION_STATE     = "WiFiConnectionState"
const val LTE_CONNECTION_STATE      = "LteConnectionState"
const val INTERFACE_STATE           = "InterfaceState"
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