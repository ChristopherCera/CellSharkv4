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
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.FileDataPart
import com.github.kittinunf.fuel.core.Method
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.apache.commons.net.PrintCommandListener
import org.apache.commons.net.ftp.FTP
import org.jetbrains.anko.doAsync
import java.io.*
import java.lang.Runnable
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import kotlin.Result.*

class CellSharkService: Service() {

    private var counter = 0
    private val ftpThread = HandlerThread("ftpThread")
    private val loggingThread = HandlerThread("loggingThread")
    private val supplicantStateReceiver = SupplicantReceiver()
    private val mIntentFilter = IntentFilter()
    private var totalCurrentBattery = mutableListOf<Double>()

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

                saveBatteryCurrent(bm)

                if (counter % 10 == 0) {

                    logBatteryInfo(bm)

                    // We want the app to report which interface is being used for data every 10 seconds
                    Util.addToEventList(arrayOf(INTERFACE_STATE, Util.getTimeStamp(), getActiveConnectionInterface()))
                    Util.saveTrafficStats()
//                    Log.d("I/CellShark", "Is an active interface ON? ${hasDataConnection(tm, wm)}")
                    if (hasDataConnection(tm, wm)) {

                        processNetworkObjects(tm, wm)
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
                    Util.updateFailedConnectionAttempts(true)
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

                    saveMetricsToCSV()
                    Log.d("Cellshark", "is Ftp Uloading? $FTP_isUploading")
                    if (!FTP_isUploading) {
                        mergeFileData()
                        //Check FTP Connection
                        //If FTP Failed, Check HTTP Connection
                        httpPost()
//                        if (FTP_SERVER_ACCESS) upload()
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

    private fun httpUpload(filePath: String) {
        val fileData = FileDataPart.from(filePath, name="csFile")
        Fuel.upload("http://cellshark.augmedix.com:1200/upload", Method.POST).add(fileData).timeout(10000).response { request, response, result ->
            if (response.statusCode / 100 == 2) {
                Log.d("CellShark_HTTP_POST_Response", "Status Code Successful, Deleting File")
                Util.updateFailedConnectionAttempts(true)
//                File(filePath).delete()
            }
            else Log.d("CellShark_HTTP_POST_Response", "\nRequest: $request \nResponse: $response\nResult: $result")

            Log.d("CellShark_HTTP_POST_Response", "\nRequest: $request \nResponse: $response\nResult: $result")
        }
    }

    private fun httpPost() {

        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath
        val appDataDir = File(appDirectory + File.separator + DATA_DIRECTORY_NAME ).absolutePath
        val files = File(appDataDir).listFiles()!!


        files.take(4).forEach { _file ->

            doAsync {

                Log.d("CellShark_HTTP_POST", "Sending ${_file.name}")

                val file = FileDataPart.from(_file.absolutePath, name="csFile")

                Fuel.upload(CELLSHARK_HTTP_LINK, Method.POST).add(file).timeout(10000).response { request, response, result ->

                    if (response.statusCode / 100 == 2) {
                        Log.d("CellShark_HTTP_POST_Response", "Status Code Successful, Deleting File")
//                      File(filePath).delete()
                    }
                    else Log.d("CellShark_HTTP_POST_Response", "\nRequest: $request \nResponse: $response\nResult: $result")

                    Log.d("CellShark_HTTP_POST_Response", "\nRequest: $request \nResponse: $response\nResult: $result")

                }
            }
//            Log.d("CellShark_HTTP_POST", "Sending ${_file.name}")
//
//            val file = FileDataPart.from(_file.absolutePath, name="csFile")
//
//            Fuel.upload("http://cellshark.augmedix.com:1200/upload", Method.POST).add(file).timeout(10000).response { request, response, result ->
//
//                if (response.statusCode / 100 == 2) File(appDataDir + File.separator + _file.name).delete()
//                else Log.d("CellShark_HTTP_POST_Response", "\nRequest: $request \nResponse: $response\nResult: $result")
//
//                Log.d("CellShark_HTTP_POST_Response", "\nRequest: $request \nResponse: $response\nResult: $result")
//
//            }
        }

        //ORIGINAL CODE
//        val file = FileDataPart.from(files[0].absolutePath, name="csFile")
//
//        Fuel.upload("http://cellshark.augmedix.com:1200/upload", Method.POST).add(file).timeout(10000).response { request, response, result ->
//
//            Log.d("CellShark_HTTP", "Request: $request \nResponse: $response\nResult: $result")
//
//        }



    }

    private fun ftpUpload() {

        val appDirectory = applicationContext.getExternalFilesDir(null)!!.absolutePath
        FTP_isUploading = true

        val appDataDir = File(appDirectory + File.separator + DATA_DIRECTORY_NAME ).absolutePath
        val files = File(appDataDir).listFiles()!!
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
            Util.updateFailedConnectionAttempts(true)

            files.take(4).forEach { fileName ->

                val filePath = File(appDataDir + File.separator + fileName.name)
                if(!filePath.exists()) { return@forEach }

                try {
                    val fileLocation = FileInputStream(filePath.absolutePath)
                    val result = ftpClient.appendFile("/CellShark/Data/${fileName.name}", fileLocation)
                    Log.i("csDebug", "reply Code: ${ftpClient.replyCode}")
                    if (result) { filePath.delete() }
                } catch (e: java.lang.Exception) { e.printStackTrace() }

            }


            ftpClient.logout()
            ftpClient.disconnect()

        } catch (e: Exception) {
            Util.updateFailedConnectionAttempts(false)
            e.printStackTrace()
            val logString = out.toString()
            Util.saveLogData("FTP Connection Attempt Response Log\n$logString")
            Util.saveLogData("FTP Crash Stack Trace\n${e.stackTraceToString()}")

            files.take(4).forEach { file ->
                doAsync { httpUpload(File(appDataDir + File.separator + file.name).absolutePath) }
            }
        } finally {
            fileQueue.clear()
        }

        FTP_isUploading = false
    }


    private fun mergeFileData() {

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
        val allFiles = File(appDirectory + File.separator + DATA_DIRECTORY_NAME).listFiles()!!

        if (allFiles.size <= 1) return

        allFiles.sort()
        allFiles.reverse()

        val files = if (allFiles.size > TOTAL_FILE_LIMIT) {
            val temp = allFiles.toMutableList()
            temp.subList(TOTAL_FILE_LIMIT+1, temp.size).clear()
            allFiles.forEach { file -> if (!temp.contains(file)) file.delete() }
            temp
        } else allFiles.toMutableList()

        var combinedFilesSize = 0

        files.forEach { file ->
            val fileSize = Integer.parseInt((file.length()/1024).toString())
            if (fileSize >= FILE_SIZE_LIMIT) return@forEach
            if(combinedFilesSize >= FILE_SIZE_LIMIT) {
                fileQueue.add(file.name)
                combinedFilesSize = 0
                return@forEach
            } else {
                combinedFilesSize += fileSize
                val csvReader = CSVReader(FileReader(file))
                val data = csvReader.readAll()
                data.forEach {
                    records.add(it)
                }
                file.delete()
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

    private  fun saveMetricsToCSV() {
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
    }

    @SuppressLint("MissingPermission")
    private fun processNetworkObjects(tm: TelephonyManager?, wm: WifiManager?) {


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
            val screenBrightness = ((Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS).toDouble()/ 255 ) * 100).toInt()
            val batteryChargingState = if(isPowerPluggedIn(applicationContext))  "100" else "0"
            var calculatedBatteryUsage = 0.0
            totalCurrentBattery.forEach {
                calculatedBatteryUsage += it
            }
            calculatedBatteryUsage /= totalCurrentBattery.size
            val newAr = arrayOf(BATTERY_INFO, Util.getTimeStamp(), batteryCapacity, batteryChargingState, calculatedBatteryUsage.toString(), screenBrightness.toString())
            totalCurrentBattery.clear()
            Util.addToEventList(newAr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun saveBatteryCurrent(bm: BatteryManager) {

        val batteryCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble() / 1000.0
        val batteryCurrentmah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        Log.d("CellShark_Current", "Current Now: $batteryCurrentmah")
        totalCurrentBattery.add(batteryCurrentmah.toDouble())


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