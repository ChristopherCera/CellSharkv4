package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.CellInfoLte
import androidx.appcompat.app.AppCompatActivity
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.ViewModelProvider
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.sdk27.coroutines.onClick
import org.w3c.dom.Text
import java.io.File
import java.lang.Exception
import java.lang.reflect.Method
import java.util.*
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {

    private var a8Permissions = arrayOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.WAKE_LOCK
    )

    private var a9Permissions = arrayOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    @SuppressLint("InlinedApi")
    private var a10Permissions = arrayOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var uiUpdateRunnable: Runnable
//    private val cellSharkService = Intent(this, CellSharkService::class.java)
    private val handler = Handler()

    @SuppressLint("MissingPermission", "HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CellSharkExceptionHandler(this))
        setContentView(R.layout.activity_main)
        createDirectories()
        createNotificationChannel()

        val cellSharkService = Intent(this, CellSharkService::class.java)

        //Handle any Intents Here
        val extras = intent.extras
        val extraString = extras?.getString(ACTIVITY_INTENT_KEY)
        if(extraString != null) {
            when(extraString) {
                "run" -> {
                    if(!csRunning) startForegroundService(cellSharkService)
                    Timer("intentGoBackTimer", false).schedule(2500) {
                        minimizeApp()
                    }
                }
                "voidTimeRestriction" -> {
                    //Remove time restrictions
                }
                "ChangeListLimit" -> {
                    Util.listLimit += 50
                    Log.d("IntentCSRunner", "Intent received, increasing limit.\nConfirmed, new list size: ${Util.listLimit}")
                    Timer("intentGoBackTimer", false).schedule(2500) {
                        minimizeApp()
                    }
                }
                "ResetListLimit" -> {
                    Util.listLimit = 70
                    Timer("intentGoBackTimer", false).schedule(2500) {
                        minimizeApp()
                    }
                }
                "TurnOff" -> {
                    stopService(cellSharkService)
                    Timer("intentGoBackTimer", false).schedule(2500) {
                        minimizeApp()
                    }
                }
                "RemoveFTPTimeouts" -> {
                    FTP_Timeout = 60000
                    FTP_KeepAliveTimeout = 0
                    Timer("intentGoBackTimer", false).schedule(2500) {
                        minimizeApp()
                    }
                }
                "ResetFTPTimeouts" -> {
                    FTP_Timeout = 10000
                    FTP_KeepAliveTimeout = 20000
                    Timer("intentGoBackTimer", false).schedule(2500) {
                        minimizeApp()
                    }
                }
                "LogBoth" -> {
                    //Do Nothing... For Now
                }

                else -> {
                    minimizeApp()
                }
            }
        }

        //Model
        val model = this.run { ViewModelProvider(this).get(LiveDataModel::class.java) }

        //Get Serial
        val textSn: TextView = findViewById(R.id.device_sn)
        textSn.text = Util.getSerialNumber(applicationContext)

        //Get UI Elements
        val recordingButton: Button = findViewById(R.id.recording_button)
        val macLabel: TextView = findViewById(R.id.deviceMac_Label)
        val lteStateLabel: TextView = findViewById(R.id.dateState_Label)
        val lteSimState: TextView = findViewById(R.id.simState_label)
        val ssidLabel: TextView = findViewById(R.id.connectedSSID_label)
        val bssidLabel: TextView = findViewById(R.id.connectedBSSID_label)
        val rssiLabel: TextView = findViewById(R.id.rssiLabel)
        val linkRateLabel: TextView = findViewById(R.id.linkRateLabel)
        val ipAddressLabel: TextView = findViewById(R.id.deviceIP_label)
        val neighborApNumLabel: TextView = findViewById(R.id.neighborAPNumLabel)
        val rsrpLabel: TextView = findViewById(R.id.rsrp_label)
        val rsrqLabel: TextView = findViewById(R.id.rsrq_label)
        val bandLabel: TextView = findViewById(R.id.band_lbl)
        val logSwitch: SwitchCompat = findViewById(R.id.logBothConnectionSwitch)

        //Telephony Object & Creating LTE Listener
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager

        //Update Static Info
        macLabel.text = Util.getWifiMacAddress()
        lteSimState.text = getSimState(telephonyManager.simState)

        logSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                //Do Nothing... For Now
            }
        }

        model.getLteData().observe(this, {

            lteStateLabel.text = getDataState(telephonyManager.dataState)
            it.allCellInfo.forEach { event ->
                when (event) {
                    is CellInfoLte -> {
                        if (event.isRegistered) {
                            rsrpLabel.text = event.cellSignalStrength.rsrp.toString()
                            rsrqLabel.text = event.cellSignalStrength.rsrq.toString()
                            bandLabel.text = getCellBand(event.cellIdentity.earfcn)
                        }
                    }
                }
            }

        })
        model.getWiFiData().observe(this, {

            ssidLabel.text = it.ssid
            bssidLabel.text = it.bssid
            rssiLabel.text = it.rssi.toString()
            linkRateLabel.text = "${it.linkSpeed} Mbps"
            ipAddressLabel.text = Formatter.formatIpAddress(it.ipAddress)

            var count = 0

            wm.scanResults.forEach {_it ->
                if (_it.SSID == wm.connectionInfo.ssid && _it.BSSID != wm.connectionInfo.bssid) count++
            }

            neighborApNumLabel.text = count.toString()

        })

        uiUpdateRunnable = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
//                Log.d("Cellshark", "Is TM Null? ${telephonyManager.allCellInfo == null}")
                model.addData(telephonyManager, wm)
                handler.postDelayed(this, 3000)
            }
        }

        recordingButton.onClick {
//            Log.d("buttonClicked", "csRunning: $csRunning \t${recordingButton.text}")

            if(csRunning && recordingButton.text.toString() == "Begin Service") {
                recordingButton.text = getString(R.string.stop_recording)
                handler.post(uiUpdateRunnable)
            } else if (!csRunning && recordingButton.text.toString() == "Begin Service") {
                startForegroundService(cellSharkService)
                recordingButton.text = getString(R.string.stop_recording)
                handler.post(uiUpdateRunnable)
            }
            else {
                stopService(cellSharkService)
                recordingButton.text = getString(R.string.start_recording)
                handler.removeCallbacks(uiUpdateRunnable)
            }
        }
    }

    private fun getDataState(value: Int): String {
        return when(value) {
            TelephonyManager.DATA_DISCONNECTED -> return "Disconnected"
            TelephonyManager.DATA_DISCONNECTING -> return "Disconnected"
            TelephonyManager.DATA_SUSPENDED -> return "Suspended"
            TelephonyManager.DATA_UNKNOWN -> return "Unknown"
            TelephonyManager.DATA_CONNECTING -> return "Connecting"
            TelephonyManager.DATA_CONNECTED -> return "Connected"
            else -> "N/A"
        }


    }

    private fun getCellBand(value: Int): String {
        return when (value) {
            //in 0..599 -> cellBand = 1
            in 600..1199 -> "2"
            in 1950..2399 -> "4"
            in 2400..2649 -> "5"
            in 5180..5279 -> "13"
            in 66436..67335 -> "66"
            else -> "N/A"
        }
    }

    private fun getSimState(value: Int): String {
        when(value) {

            TelephonyManager.SIM_STATE_ABSENT ->            { return "Absent" }
            TelephonyManager.SIM_STATE_CARD_IO_ERROR ->     { return "IO Error" }
            TelephonyManager.SIM_STATE_CARD_RESTRICTED ->   { return "Restricted" }
            TelephonyManager.SIM_STATE_NETWORK_LOCKED ->    { return "Network Locked" }
            TelephonyManager.SIM_STATE_NOT_READY ->         { return "Not Ready" }
            TelephonyManager.SIM_STATE_PERM_DISABLED ->     { return "Permanently Disabled" }
            TelephonyManager.SIM_STATE_PIN_REQUIRED ->      { return "PIN Required" }
            TelephonyManager.SIM_STATE_PUK_REQUIRED ->      { return "PUK Required" }
            TelephonyManager.SIM_STATE_READY ->             { return "Ready" }
            TelephonyManager.SIM_STATE_UNKNOWN ->           { return "Unknown" }
            else -> { return "N/A"}


        }
    }

    private fun getWiFiStandard(value: Int): String {
        when(value) {

            ScanResult.WIFI_STANDARD_11AC ->    {   return "11AC" }
            ScanResult.WIFI_STANDARD_11AX ->    {   return "11AX" }
            ScanResult.WIFI_STANDARD_11N ->     {   return "11N" }
            ScanResult.WIFI_STANDARD_LEGACY ->  {   return "LEGACY" }
            ScanResult.WIFI_STANDARD_UNKNOWN -> {   return "UNKNOWN" }
            else -> { return "N/A" }

        }
    }

    private fun createDirectories() {

        val rootDir = getExternalFilesDir(null)!!
        File(rootDir.absolutePath + File.separator + "Data" ).mkdirs()
        File(rootDir.absolutePath + File.separator + "Logs" ).mkdirs()

    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = NotificationManager.IMPORTANCE_LOW
        val notificationChannel = NotificationChannel(CHANNEL_ID, "CellShark", importance)
        notificationChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        notificationManager.createNotificationChannel(notificationChannel)
    }

    private fun enableSSL() {
        /*
        *   This function will allow SSL FTP connectivity on Android 9/10
        *   Not needed for Android 8, since class is accessible for that version
        */

        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclaredMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)

        val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
        val setHiddenApiExemptions = getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf(arrayOf<String>()::class.java)) as Method

        val vmRuntime = getRuntime.invoke(null)
        setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L"))
    }

    private fun minimizeApp() {

        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)

    }

    override fun onResume() {
        Log.d("uiUpdater", "onResume")
        if(csRunning) handler.post(uiUpdateRunnable)
        super.onResume()
    }

    override fun onPause() {
//        Log.d("uiUpdater", "onPause")
        handler.removeCallbacks(uiUpdateRunnable)
        super.onPause()
    }

    override fun onDestroy() {
//        Log.d("uiUpdater", "onDestroy")
        handler.removeCallbacks(uiUpdateRunnable)
//        stopService(cellSharkService)
        super.onDestroy()
    }

    override fun onStart() {
        Log.d("CellShark", "Buiild Version: ${Build.VERSION.SDK_INT}")
        when (Build.VERSION.SDK_INT) {
            Build.VERSION_CODES.Q -> {
                enableSSL()
                requestPermissions(a10Permissions, 0)
            }
            Build.VERSION_CODES.P -> {
                enableSSL()
                requestPermissions(a9Permissions, 0)
            }
            else -> {
                requestPermissions(a8Permissions, 0)
            }
        }

        super.onStart()
    }
}