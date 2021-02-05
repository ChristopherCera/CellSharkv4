package com.monitoring.cellshark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class SupplicantReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {

        var time = SimpleDateFormat(DATE_FORMAT_SINGLE_EVENT, Locale.getDefault())
        time.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = time.format(Date())

//        Log.d("csDebug", "timeStamp: $timestamp")
//-------------------------------
        val action = intent?.action

        if (action != null) {
            val state = intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
            val networkState = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)

//            Log.d("CellShark_Supplicant_Event", "Network State: $networkState")
//            Log.d("CellShark_Supplicant_Event", "Supplicant State: $state")
//            Util.saveLogData(networkState.toString())

            if (state != null) {

                if(noWiFiInfo(state)) {
                    Util.addToEventList(arrayOf(SUPPLICANT, timestamp, state.toString(), " _ ", " _ " ))
                }

                else if ( context != null ) {
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wmInfo = wm.connectionInfo
                    if (wmInfo.bssid == null) { Util.addToEventList(arrayOf(SUPPLICANT, timestamp, state.toString(), " _ ", " _ " )) } else {
                        Util.addToEventList(WiFiEvent(wm.connectionInfo, wm.scanResults, timestamp).csvLine)
                        Util.addToEventList(arrayOf(SUPPLICANT, timestamp, state.toString(), wmInfo.ssid, wmInfo.bssid))
                    }
                }
            }
        }

    }
}

private fun noWiFiInfo(state: SupplicantState): Boolean {
    return state == SupplicantState.INACTIVE  ||
            state == SupplicantState.DORMANT   ||
            state == SupplicantState.DISCONNECTED ||
            state == SupplicantState.INTERFACE_DISABLED ||
            state == SupplicantState.SCANNING ||
            state == SupplicantState.UNINITIALIZED
}