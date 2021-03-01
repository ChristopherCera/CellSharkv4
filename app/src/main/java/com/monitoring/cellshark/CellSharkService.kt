package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
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

        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath

        mIntentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        applicationContext.registerReceiver(supplicantStateReceiver, mIntentFilter)

        ftpHandler = Handler(ftpThread.looper)
        loggingHandler = Handler(loggingThread.looper)

        object : Runnable {
            override fun run() {

                if (counter % 10 == 0) {

                    logBatteryInfo(bm)

                    // We want the app to report which interface is being used for data every 10 seconds
                    Util.addToEventList(arrayOf(INTERFACE_STATE, Util.getTimeStamp(), getActiveConnectionInterface()))
                    Util.saveTrafficStats()
//                    Log.d("I/CellShark", "Is an active interface ON? ${hasDataConnection(tm, wm)}")
                    if (hasDataConnection(tm, wm)) {

                        processNetworkData(tm, wm)
                        val defaultGateway = Util.formatIP(wm.dhcpInfo.gateway)
                        val result = mutableListOf<PingInfo>()

                        GlobalScope.launch(IO) {
                            axEndPoints.forEach { address ->
                                result.add(ping(address))
                            }
                            if (defaultGateway != "0.0.0.0") result.add(defaultGatewayPing(defaultGateway))
                            if(result.size > 0 ) addPingData(result)
                        }

                    }
                }

                if (counter % 30 == 0) { wm.startScan() }
                if (counter % 300 == 0) {
                    logBatteryUsage(bm)
                }

                //Daily
                if(counter % (MINUTE*1440) == 0) {
                    Util.updateFTPConnection(true)
                    val buildNum = Build.VERSION.SDK_INT
                    val mac = Util.getWifiMacAddress()
                    val newAr = arrayOf(SystemMac, Util.getTimeStamp(), buildNum.toString(), mac)
                    Util.addToEventList(newAr)
                    counter = 0
                }

                counter++
                loggingHandler.postDelayed(this, 1000)
            }
        }.also { loggingRunnable = it }

        object : Runnable {
            override fun run() {

                GlobalScope.launch(IO) {

                    val fileName = saveToFile()
                    Log.d("Cellshark", "is Ftp Uloading? $FTP_isUploading")
                    if (!FTP_isUploading) {
                        val size = File(appDirectory + File.separator + DATA_DIRECTORY_NAME).listFiles()!!.size
                        if (size > 1 ) mergeFileData(fileName)
                        else fileQueue.add(fileName)
                        if (FTP_SERVER_ACCESS) upload()
                    }

                }
//                Log.d("FTPRunnable,", "Upload Ping")
                ftpHandler.postDelayed(this, 30000)
            }
        }.also { uploadRunnable = it }

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

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dataActivity = tm.dataActivity

//        Log.d("csDebug", "Data State: $mobileDataState\tActivity: $dataActivity")

        return if(isConnectedSSID(wm) && dataActivity == TelephonyManager.DATA_ACTIVITY_NONE) { WIFI_INT }
        else if (tm.isDataEnabled) { LTE_INT }
        else { "Offline" }
    }

    private fun upload() {

        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath
        FTP_isUploading = true

        val appDataDir = File(appDirectory + File.separator + DATA_DIRECTORY_NAME ).absolutePath
        val ftpClient = FTPSSLClient()
        val out = StringWriter()

        // Timeout at 10 seconds for connection attempt & 20 seconds for keepAlive
        ftpClient.connectTimeout = FTP_Timeout
        ftpClient.controlKeepAliveTimeout = FTP_KeepAliveTimeout
        ftpClient.addProtocolCommandListener(PrintCommandListener(PrintWriter(out), true))
//        ftpClient.addProtocolCommandListener(PrintCommandListener(System.out))

        try {
            ftpClient.connect(FTP_ADDRESS, FTP_PORT)
            ftpClient.login(username, password)
            ftpClient.execPBSZ(0)
            ftpClient.execPROT("P")
            ftpClient.enterLocalPassiveMode()
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

            //Enabling here will ensure no thread crashes due to list manipulation
            Util.updateFTPConnection(true)

            fileQueue.take(5).forEach { fileName ->

                val filePath = File(appDataDir + File.separator + fileName)
                if(!filePath.exists()) { return@forEach }

                try {
                    val fileLocation = FileInputStream(filePath.absolutePath)
                    val result = ftpClient.appendFile("/CellShark/Data/$fileName", fileLocation)
                    Log.i("csDebug", "reply Code: ${ftpClient.replyCode}")
                    if (result) { filePath.delete() }
                } catch (e: java.lang.Exception) { e.printStackTrace() }

            }


            ftpClient.logout()
            ftpClient.disconnect()

        } catch (e: Exception) {
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
         * To reduce a high number of file append requests, I'm reducing total # of files on device to 30.
         *
         */
        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath
        val records: MutableList<Array<String>> = mutableListOf()
        var totalSize = 0
        val allFiles = File(appDirectory + File.separator + DATA_DIRECTORY_NAME).listFiles()
        val files: MutableList<File>
        fileQueue.add(excludeFileName)

        val fileLimit = 30

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

            val fileName = "${Util.getSerialNumber()}_" +
                    SimpleDateFormat(DATE_FORMAT_FILE, Locale.getDefault()).format(Date()) + "_merged.csv"
            val filePath = File(appDirectory + File.separator + DATA_DIRECTORY_NAME + File.separator + fileName)

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
        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath
        val fileName = "${Util.getSerialNumber()}_" +
                SimpleDateFormat(DATE_FORMAT_FILE, Locale.getDefault()).format(Date()) + ".csv"
        val filePath = File(appDirectory + File.separator + DATA_DIRECTORY_NAME + File.separator + fileName)


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

    @SuppressLint("MissingPermission")
    private fun processNetworkData(tm: TelephonyManager?, wm: WifiManager?) {


        if (logBothMetrics) {

            tm?.allCellInfo?.forEach { info ->
                when (info) {
                    is CellInfoLte -> {
                        if (info.isRegistered) {
                            Util.addToEventList(LteEvent(info).csvLine)
                        }
                    }
                }
            }

            if (wm != null) {
                if (Util.isWiFiNotNull(wm)) {
                    Util.addToEventList(WiFiEvent(wm.connectionInfo, wm.scanResults, Util.getTimeStamp()).csvLine)
                }
            }

        }

        else {
            if(getActiveConnectionInterface() == WIFI_INT ) {
                if (wm != null) {
                    if (Util.isWiFiNotNull(wm)) {
                        Util.addToEventList(WiFiEvent(wm.connectionInfo, wm.scanResults, Util.getTimeStamp()).csvLine)
                    }
                }
            } else if (getActiveConnectionInterface() == LTE_INT ) {
                tm?.allCellInfo?.forEach { info ->
                    when (info) {
                        is CellInfoLte -> {
                            if (info.isRegistered) {
                                Util.addToEventList(LteEvent(info).csvLine)
                            }
                        }
                    }
                }
            }
        }

    }

    private fun addPingData(data: MutableList<PingInfo>) {

        val newAr: Array<String>
        val temp: MutableList<String> = mutableListOf(PINGv2, Util.getTimeStamp() )

        // Index
        // 0 - PINGv2
        // 1 - Timestamp
        // 2-> End {2 address, 3 duration, 4 result}
        // 6-> End {5 address, 6 duration, 7 result}

        for (index in 0..4) {
            if (data.getOrNull(index) != null) {
                temp.add(data[index].adress)
                temp.add(data[index].duration)
                temp.add(data[index].result.toString())
            } else {
                temp.add("None")
                temp.add("None")
                temp.add("None")
            }
        }

        newAr = temp.toTypedArray()

        Util.addToEventList(newAr)
    }

    private fun ping(address: String): PingInfo {

        val name = when(address) {
            axEndPoints[0] -> "CC"
            axEndPoints[1] -> "echo"
            axEndPoints[2] -> "goog"
            axEndPoints[3] -> "mcu4"
            else -> address
        }

        val result: Boolean
        val start = System.currentTimeMillis()
        try {
            val inetAddress = InetAddress.getByName(address)
            result = inetAddress.isReachable(500)
        } catch (e: Exception) {
            return PingInfo(name, "0", false)
        }

        val end = System.currentTimeMillis()
        val time = (end-start).toInt()

        return PingInfo(name, time.toString(), result)
    }

    private fun defaultGatewayPing(address: String): PingInfo {

        val runtime = Runtime.getRuntime()
        var time: String = "0"
        var result: Boolean = false

        try {

            val runCommand = runtime.exec("/system/bin/ping -c 1 $address")
            val exitValue = runCommand.waitFor()
            val stdInput = BufferedReader(InputStreamReader(runCommand.inputStream))

            //Exit Value 0: Success
            //Exit Value != 0: Failed

            if(exitValue == 0) {
                var count = 0
                result = true
                stdInput.forEachLine { inputLine ->
                    if(count == 1) { time = Util.getPingTime(inputLine).toString() }
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return PingInfo(address, time, result)

    }
    

    private fun hasDataConnection(tm: TelephonyManager?, wm: WifiManager?): Boolean {
        //Mobile Data ---> isDataEnabled && NetworkOperator?
        return isConnectedSSID(wm!!) || (tm!!.isDataEnabled && tm.networkOperator != "")
    }

    private fun isConnectedSSID(wm: WifiManager): Boolean {
        return run {
            val netInfo = wm.connectionInfo
            (wm.wifiState == WifiManager.WIFI_STATE_ENABLED
                    && (netInfo.bssid != null && netInfo.bssid != "02:00:00:00:00"))
        }
    }

    private fun logBatteryInfo(bm: BatteryManager){
        //Battery Percent
        try {
            val batteryCapacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
            val batteryCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val screenBrightness = ((Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS).toDouble()/ 255 ) * 100).toInt()
            val batteryChargingState = if(isPowerPluggedIn(applicationContext))  "100" else "0"
            val newAr = arrayOf(BATTERY_INFO, Util.getTimeStamp(), batteryCapacity, batteryChargingState, batteryCurrent.toString(), screenBrightness.toString())
            Util.addToEventList(newAr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }


    private fun logBatteryUsage(bm: BatteryManager) {

        Log.d("CellShark_Battery_Info", "Running logBatteryUsage()")

        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath
        val currentBatteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        var batteryDif = 0.0
        val fileName = "BatteryInfo.txt"
        if ( !File( appDirectory, fileName ).exists() ) {
            File( appDirectory, fileName ).writeText(currentBatteryPercent.toString())
        } else {
            val previousPercent = File(appDirectory, fileName).readLines()[0]
            batteryDif = currentBatteryPercent.toDouble() - previousPercent.toDouble()
            File( appDirectory, fileName ).writeText(currentBatteryPercent.toString())
        }

        batteryDif *= 12        //Per Hour (5 minutes * 2 = 10 minutes * 6 = 1 hour Rate)

        Util.addToEventList(arrayOf(BATTERY_USAGE_RATE, Util.getTimeStamp(), batteryDif.toString()))

        Log.d("Cellshark_Battery", "Battery Difference: $batteryDif")

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