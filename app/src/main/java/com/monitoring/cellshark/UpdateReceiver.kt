package com.monitoring.cellshark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UpdateReceiver : BroadcastReceiver() {


    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("CELLSHARK_INTENT", "onReceive for PackageUpdateReceiver")
        if(intent != null) {

            if (intent.action.equals(Intent.ACTION_MY_PACKAGE_REPLACED, true)) {
                Log.d("CELLSHARK_INTENT", "Update Successful")
                val foregroundIntent = Intent(context, CellSharkService::class.java)
                if(!csRunning) {
                    Util.deleteAllFiles()
                    context?.startForegroundService(foregroundIntent)
                }
            }
        }
    }

}