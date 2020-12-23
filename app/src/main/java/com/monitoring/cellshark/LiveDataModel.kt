package com.monitoring.cellshark

import android.annotation.SuppressLint
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LiveDataModel : ViewModel() {

//    var cellData = MutableLiveData<LteEvent>()
//    var ftpResult = MutableLiveData<Boolean>()
//    fun getLiveData(): LiveData<LteEvent> {
//        return cellData
//    }
//
//    fun getFtpConnectionLD(): LiveData<Boolean> {
//        return ftpResult
//    }
//
//    @SuppressLint("MissingPermission")
//    fun addData(tm: TelephonyManager) {
//        tm.allCellInfo.forEach {
//            when (it) {
//                is CellInfoLte -> {
//                    if (it.isRegistered) {
//                        cellData.value = LteEvent(it)
//                    }
//                }
//            }
//        }
//    }
}