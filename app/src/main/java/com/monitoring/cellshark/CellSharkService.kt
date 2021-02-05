package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.CellInfoLte
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.apache.commons.net.PrintCommandListener
import org.apache.commons.net.ftp.FTP
import java.io.*
import java.lang.Runnable
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

class CellSharkService: Service() {

    private var counter = 0
    private val ftpThread = HandlerThread("ftpThread")
    private val loggingThread = HandlerThread("loggingThread")
    private val supplicantStateReceiver = SupplicantReceiver()
    private val mIntentFilter = IntentFilter()

    private var tm: TelephonyManager? = null
    private lateinit var ss: PhoneStateListener
    private lateinit var pm: PowerManager
    private lateinit var w1: PowerManager.WakeLock
    private lateinit var wm: WifiManager
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var ftpHandler: Handler
    private lateinit var loggingHandler: Handler
    private lateinit var loggingRunnable: Runnable
    private lateinit var uploadRunnable: Runnable
    private var fileQueue = arrayListOf<String>()

    override fun onCreate() {
        csRunning = true
        pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        w1 = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CellShark: Wakelock")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CellShark: WiFiLock")
        wifiLock.acquire()
        w1.acquire()
        ftpThread.start()
        loggingThread.start()
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ss = PhoneStateListener()
        tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        tm?.listen(ss, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)

        mIntentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        applicationContext.registerReceiver(supplicantStateReceiver, mIntentFilter)

        ftpHandler = Handler(ftpThread.looper)
        loggingHandler = Handler(loggingThread.looper)

        object : Runnable {
            override fun run() {

                if (counter % 10 == 0) {

//                    Util.saveLogData(counter.toString())
                    // We want the app to report which interface is being used for data every 10 seconds
                    val ci = getActiveConnectionInterface()
                    Util.addToEventList(arrayOf(INTERFACE_STATE, Util.getTimeStamp(), ci))
                    Util.saveTrafficStats()
                    Log.d("CellShark_DeBug", "${hasDataConnection(tm, wm)}")
                    if (hasDataConnection(tm, wm)) {
                        processNetworkData(tm, wm)
                        axEndPoints.forEach {
                            GlobalScope.launch(IO) { ping(it) }
                        }
                    }
                }

                if (counter % 30 == 0) {
                    wm.startScan()
                    logBatteryInfo(bm)
                }

                //Daily
                if(counter % (MINUTE*1440) == 0) {
                    Util.updateFTPConnection(true)
                    logDocAppVersion()
                    val buildNum = Build.VERSION.SDK_INT
                    Util.addToEventList(
                            arrayOf(SYSTEM, Util.getTimeStamp(), VERSION,
                                    buildNum.toString())
                    )
                    val mac = Util.getWifiMacAddress()
                    Util.addToEventList(
                            arrayOf(SYSTEM, Util.getTimeStamp(), MAC, mac)
                    )
                    counter = 0
                }

                counter++
                loggingHandler.postDelayed(this, 1000)
            }
        }.also { loggingRunnable = it }

        uploadRunnable = object : Runnable {
            override fun run() {

                GlobalScope.launch(IO) {

                    val fileName = saveToFile()
                    Log.d("Cellshark", "is Ftp Uloading? $FTP_isUploading")
                    if (!FTP_isUploading) {
                        val size = File(Util.dataDir).listFiles()!!.size
                        if (size > 1 ) mergeFileData(fileName)
                        else fileQueue.add(fileName)
                        if (Util.FTP_SERVER_ACCESS) upload()
                    }

                }
//                Log.d("FTPRunnable,", "Upload Ping")
                ftpHandler.postDelayed(this, 30000)
            }
        }

        loggingHandler.post(loggingRunnable)
        ftpHandler.post(uploadRunnable)
        Toast.makeText(this, "CellShark Service Started", Toast.LENGTH_SHORT).show()
        val foregroundIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, foregroundIntent, 0)
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("CellShark")
            .setContentIntent(pendingIntent)
            .setContentText("Augmedix Support Tool Running").build()

        startForeground(101, notification)
        return START_NOT_STICKY
    }



    fun getActiveConnectionInterface(): String {

        //Function will return which interface is being used for data
        //1. Android usually prioritizes WiFi, so if there's a connection RETURN WiFi
        //2. Else return LTE if there's a connection to that
        //3. Else return NONE since it's not connected to anything....

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Data Activity 0 --> No Activity with cell radios (LTE, 3G, etc)
        /*
        *
        * WiFi Off, LTE ON : will be 0 IF no data is being passed
        * WiFi Off, LTE ON : will be 1 if data is being passed
        * WiFi On,  LTE ON : will be 0 if WiFi has data
        * WiFi On,  LTE ON : will be 1 if WiFi has no data
        *
         */
        //No SIM --> Mobile Data Toggle is OFF (Cannot be enabled)

        val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dataActivity = tm.dataActivity

//        Log.d("csDebug", "Data State: $mobileDataState\tActivity: $dataActivity")

        return if(isWifiConnected(wm) && dataActivity == TelephonyManager.DATA_ACTIVITY_NONE) {
            "WiFi"
        }
        else if (tm.isDataEnabled) { "LTE" }
        else { "Offline" }
    }

    private fun upload() {
        FTP_isUploading = true

        fileQueue.forEach{
            Log.d("FileQueuePrint", it)
        }

//        val tempFileQueue = fileQueue
//        tempFileQueue.forEach {
//            Log.d("FileQueuePrint", "v2 $it" )
//        }

        val ftpClient = FTPSSLClient()
        val out = StringWriter()
        var success = mutableListOf<String>()

        // Timeout at 10 seconds for connection attempt & 20 seconds for keepAlive
        ftpClient.connectTimeout = FTP_Timeout
        ftpClient.controlKeepAliveTimeout = FTP_KeepAliveTimeout
        ftpClient.addProtocolCommandListener(PrintCommandListener(PrintWriter(out), true))
        ftpClient.addProtocolCommandListener(PrintCommandListener(System.out))

        try {
            ftpClient.connect(FTP_ADDRESS, FTP_PORT)
            ftpClient.login(username, password)
            ftpClient.execPBSZ(0)
            ftpClient.execPROT("P")
            ftpClient.enterLocalPassiveMode()
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

            //Enabling here will ensure no thread crashes due to list manipulation
            Util.updateFTPConnection(true)

            fileQueue.forEach { fileName ->

                val filePath = File(Util.dataDir + File.separator + fileName)
                if(!filePath.exists()) { return@forEach }

                try {
                    val fileLocation = FileInputStream(filePath.absolutePath)
                    val result = ftpClient.appendFile("/CellShark/Data/$fileName", fileLocation)
                    Log.i("csDebug", "reply Code: ${ftpClient.replyCode}")
//                    if (result) { success.add(fileName) }
                    if (result) { filePath.delete() }
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    Log.d("FTP_CellShark", "Crashed Here v1")
                }
            }


            ftpClient.logout()
            ftpClient.disconnect()

        } catch (e: Exception) {
            Log.d("FTP_CellShark", "Crashed Here v3")
            Util.updateFTPConnection(false)
            e.printStackTrace()
            val logString = out.toString()
            Util.saveLogData("FTP Connection Attempt Response Log\n$logString")
            Util.saveLogData("FTP Crash Stack Trace\n${e.stackTraceToString()}")
        } finally {
            fileQueue.clear()
        }

        FTP_isUploading = false
    }

    private fun mergeFileData(excludeFileName: String) {

        /**
         * Function will merge previous files into one, to reduce the # of connection attempts to the
         * FTP Server.
         * Limitations -- No more than 300kb per file combined
         * To reduce a high number of file append requests, I'm reducing total # of files on device to 50.
         *
         */

        val records: MutableList<Array<String>> = mutableListOf()
        var totalSize = 0
        val allFiles = File(Util.dataDir).listFiles()
        val files: MutableList<File>
        fileQueue.add(excludeFileName)

        val fileLimit = 10

        if(allFiles != null) {

            allFiles.sort()
            allFiles.reverse()

            files = if (allFiles.size > fileLimit) {
                val temp = allFiles.toMutableList()
                temp.subList(fileLimit, temp.size).clear()
                allFiles.forEach { file -> if (!temp.contains(file)) file.delete() }
                temp
            } else allFiles.toMutableList()

            files.forEach { file ->
                if(file.name != excludeFileName) {
                    val fileSize = Integer.parseInt((file.length()/1024).toString())
                    if(totalSize >= 300) {
                        fileQueue.add(file.name)
                        return@forEach
                    }
                    else if (fileSize < 300) {
                        totalSize += fileSize
                        val csvReader = CSVReader(FileReader(file))
                        val data = csvReader.readAll()
                        data.forEach {
                            records.add(it)
                        }
                        file.delete()
                    }
                }
            }

            val fileName = "${Util.getSerialNumber(applicationContext)}_" +
                    SimpleDateFormat(DATE_FORMAT_FILE, Locale.getDefault()).format(Date()) + "_merged.csv"
            val filePath = File(Util.dataDir + File.separator + fileName)

            val mFileWriter = FileWriter(filePath, false)
            val csvWriter = CSVWriter(mFileWriter)

            records.forEach {
                csvWriter.writeNext(it)
            }

            csvWriter.close()
            fileQueue.add(fileName)

        }
    }

    private  fun saveToFile(): String {

        val fileName = "${Util.getSerialNumber(applicationContext)}_" +
                SimpleDateFormat(DATE_FORMAT_FILE, Locale.getDefault()).format(Date()) + ".csv"
        val filePath = File(Util.dataDir + File.separator + fileName)


        val mFileWriter = FileWriter(filePath, false)
        val csvWriter = CSVWriter(mFileWriter)

        val events = Util.getEventList()
        try {
            while(events.hasNext()) {
                csvWriter.writeNext(events.next())
            }
            csvWriter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Util.disOccupy()
        return fileName
    }

     private fun logDocAppVersion() {
        val pm = packageManager
        val installedApps = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        installedApps.forEach { app ->
            if(app.packageName == AX_PKG_NAME) {
                Util.addToEventList(arrayOf(AX_DOC_VERSION, Util.getTimeStamp(), app.versionName.toString()))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNetworkData(tm: TelephonyManager?, wm: WifiManager?) {

        //LTE Data
        var registeredLTETower = false
        tm?.allCellInfo?.forEach { event ->
            when (event) {
                is CellInfoLte -> {
                    if (event.isRegistered) {
                        registeredLTETower = true
                        val lteEvent = LteEvent(event)
                        Util.addToEventList(lteEvent.csvLine)
                    }
                }
            }
        }

        if(registeredLTETower) Util.addToEventList(arrayOf(LTE_CONNECTION_STATE, Util.getTimeStamp(), "1"))
        else Util.addToEventList(arrayOf(LTE_CONNECTION_STATE, Util.getTimeStamp(), "0"))

        //Wireless Data
        if (wm != null) {
            if(Util.isWiFiConnected(wm)) {
                val wifiEvent = WiFiEvent(wm.connectionInfo, wm.scanResults, Util.getTimeStamp())
                Util.addToEventList(wifiEvent.csvLine)
                val dhcpInfo = wm.dhcpInfo
                val defaultGateway = Util.formatIP(dhcpInfo.gateway)
                if (defaultGateway != "0.0.0.0") {
                    GlobalScope.launch(Dispatchers.IO) {
                        Log.d("csDebug", "Running Default Gateway Ping")
                        ping(defaultGateway, true)
                    }
                }
            } else {
                Util.addToEventList(arrayOf(WIFI_CONNECTION_STATE, Util.getTimeStamp(), "0"))
            }
        } else {
            Util.addToEventList(arrayOf(WIFI_CONNECTION_STATE, Util.getTimeStamp(), "0"))
        }
    }

    private fun newPing(address: String, isDefaultGateway: Boolean): PingInfo {
        val dgPingInfo: PingInfo
        if(isDefaultGateway) alternatePing(address)
        else {
            val result: Boolean
            val start = System.currentTimeMillis()
            try {
                val inetAddress = InetAddress.getByName(address)
                result = inetAddress.isReachable(500)
            } catch (e: Exception) {
                throw Exception("Ping Failed: ${e.printStackTrace()}")
            }

            val end = System.currentTimeMillis()
            val time = (end-start).toInt()

            return PingInfo(address, time.toString(), result)
        }

        return PingInfo("test", "100", false)
    }

    private fun ping(address: String, isDefaultGateway: Boolean = false) {
        val result: Boolean
        //start time
        val timestamp = Util.getTimeStamp()

        if(isDefaultGateway) alternatePing(address)
        else {

            val start = System.currentTimeMillis()

            try {
                val inetAddress = InetAddress.getByName(address)
                result = inetAddress.isReachable(500)
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }

            val end = System.currentTimeMillis()
            var diff = (end-start).toInt()
            if(!result) { diff = 0 }
            Util.addToEventList(arrayOf(PING, timestamp, address, result.toString(), diff.toString()))
        }

    }

    private fun alternatePing(address: String) {

        // This function is made specifically for default gateway pings. Since other method is iffy

        val runtime = Runtime.getRuntime()

        try {

            val runCommand = runtime.exec("/system/bin/ping -c 1 $address")
            val exitValue = runCommand.waitFor()
            val stdInput = BufferedReader(InputStreamReader(runCommand.inputStream))

            //Exit Value 0: Success
            //Exit Value > 0: Failed

            if(exitValue == 0) {
                var count = 0
                stdInput.forEachLine { inputLine ->
                    if(count == 1) {
                        Util.addToEventList(arrayOf(PING_GATEWAY, Util.getTimeStamp(), address, true.toString(), Util.getPingTime(inputLine)!!.toString()))
                    }
                    count++
                }
            }
            else {
                Util.addToEventList(arrayOf(PING_GATEWAY, Util.getTimeStamp(), address, false.toString(), .0.toString()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasDataConnection(tm: TelephonyManager?, wm: WifiManager?): Boolean {
        //Mobile Data ---> isDataEnabled && NetworkOperator?
        return isWifiConnected(wm!!) || (tm!!.isDataEnabled && tm.networkOperator != "")
    }

    private fun isWifiConnected(wm: WifiManager): Boolean {
        return run {
            val netInfo = wm.connectionInfo
            (wm.wifiState == WifiManager.WIFI_STATE_ENABLED
                    && (netInfo.bssid != null && netInfo.bssid != "02:00:00:00:00"))
        }
    }

    private fun logBatteryInfo(bm: BatteryManager){
        //Battery Percent
        try {
            Util.addToEventList(
                arrayOf(
                    SYSTEM,
                    Util.getTimeStamp(),
                    BATT_PERCENT,
                    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
                )
            )
            //Battery Charging/Plugged In
            if (isPowerPluggedIn(applicationContext)) {
                Util.addToEventList(
                    arrayOf(
                        SYSTEM,
                        Util.getTimeStamp(),
                        BATT_CHARGING_STATE,
                        "100"
                    )
                )
            } else {
                Util.addToEventList(arrayOf(SYSTEM, Util.getTimeStamp(), BATT_CHARGING_STATE, "0"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun isPowerPluggedIn(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = intent!!.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        csRunning = false
        Toast.makeText(this, "CellShark Service Stopped", Toast.LENGTH_SHORT).show()
        tm?.listen(ss, PhoneStateListener.LISTEN_NONE)
        loggingHandler.removeCallbacks(loggingRunnable)
        ftpHandler.removeCallbacks(uploadRunnable)
        csRunning = false
        w1.release()
        wifiLock.release()
        super.onDestroy()
    }

}