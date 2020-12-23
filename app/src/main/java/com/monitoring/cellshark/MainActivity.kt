package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import org.jetbrains.anko.sdk27.coroutines.onClick
import java.io.File
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
    private lateinit var phoneStateListener: PhoneStateListener
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createDirectories()
        createNotificationChannel()


        //create foreground service object
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
            }
        }

        //Get Serial
        val textSn: TextView = findViewById(R.id.device_sn)
        textSn.text = Util.getSerialNumber()

        //Get UI Elements
        val recordingButton: Button = findViewById(R.id.recording_button)

        if(csRunning) {
            recordingButton.text = getString(R.string.stop_recording)
        }

        //Create Model Object
        val dataModel = ViewModelProvider(this).get(LiveDataModel::class.java)

        //Telephony Object & Creating LTE Listener
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                //Add new data
                super.onSignalStrengthsChanged(signalStrength)
            }
        }

        recordingButton.onClick {
            if(csRunning) {
                stopService(cellSharkService)
                recordingButton.text = getString(R.string.start_recording)
            }
            else {
                startForegroundService(cellSharkService)
                recordingButton.text = getString(R.string.stop_recording)

            }
        }



    }

    private fun createDirectories() {

        val rootDir = getExternalFilesDir(null)!!
        File(rootDir.absolutePath + File.separator + "Data" ).mkdirs()
        File(rootDir.absolutePath + File.separator + "logcat").mkdirs()

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

    override fun onStart() {

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