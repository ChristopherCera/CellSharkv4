package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LiveDataModel : ViewModel() {

    private var cellData = MutableLiveData<TelephonyManager>()
    private var wifiData = MutableLiveData<WifiInfo>()
    fun getLteData(): LiveData<TelephonyManager>    { return cellData }
    fun getWiFiData(): LiveData<WifiInfo>           { return wifiData }

    @SuppressLint("MissingPermission")
    fun addData(tm: TelephonyManager, wm: WifiManager) {

        cellData.value = tm
        wifiData.value = wm.connectionInfo


    }
}