package com.monitoring.cellshark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {

        if(intent != null){
            if (intent.action.equals(Intent.ACTION_BOOT_COMPLETED, true)) {
                Log.d("CELLSHARK_INTENT", "Reboot Successful")
                val foregroundIntent = Intent(context, CellSharkService::class.java)
                if(!csRunning){
                    Util.addToEventList(arrayOf(SYSTEM, Util.getTimeStamp(), REBOOT_EVENT, "0"))
                    context?.startForegroundService(foregroundIntent)
                }
            }
        }
    }
}