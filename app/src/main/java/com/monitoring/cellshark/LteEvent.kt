package com.monitoring.cellshark

import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import java.text.SimpleDateFormat
import java.util.*

class LteEvent(cellInfo: CellInfoLte, rsrp: Int = 0, rsrq: Int = 0, earfcn: Int = 0) {

    private var cellBand: Int = 0
    private var timestamp: String
    private var cellSNR: Int
    var csvLine: Array<String>

    init {

        val time = SimpleDateFormat(DATE_FORMAT_SINGLE_EVENT, Locale.getDefault())
        time.timeZone = TimeZone.getTimeZone("UTC")
        timestamp = time.format(Date())

        //RSSI was added in for Android 10 / Q
        //Previous versions have no way to get this
        cellSNR = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cellInfo.cellSignalStrength.rssi
        } else {
            0
        }


        /* Changes the band the appropriate one */
        when (cellInfo.cellIdentity.earfcn) {
            //in 0..599 -> cellBand = 1
            in 600..1199 -> cellBand = 2
            in 1950..2399 -> cellBand = 4
            in 2400..2649 -> cellBand = 5
            in 5180..5279 -> cellBand = 13
            in 66436..67335 -> cellBand = 66
        }


        csvLine = arrayOf(
            LTE, timestamp,
            cellInfo.cellSignalStrength.rsrp.toString(),
            cellInfo.cellSignalStrength.rsrq.toString(),
            cellBand.toString(),
            cellInfo.cellIdentity.earfcn.toString(),
            cellInfo.cellIdentity.pci.toString(),
            cellSNR.toString()
        )
    }

}