package com.monitoring.cellshark

import android.app.Activity
import android.content.Context
import org.jetbrains.anko.getStackTraceString

class CellSharkExceptionHandler(private var app: Activity) : Thread.UncaughtExceptionHandler {

    private var defaultUEH: Thread.UncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()!!

    override fun uncaughtException(t: Thread, e: Throwable) {
        var arr: Array<StackTraceElement> = e.stackTrace
        var report = e.toString() + "\n\n"
        report += "--------- Stack trace ---------\n\n"
        arr.forEach { report += "      $it\n" }
        report += "-------------------------------\n\n"

        report += "--------- Cause ----------\n\n"
        val cause: Throwable? = e.cause
        if(cause != null) {
            report += cause.toString() + "\n\n"
            arr = cause.stackTrace
            arr.forEach { report += "      $it\n" }
        }
        report += "-------------------------------\n\n"
        try {
            Util.saveLogData(report)
        } catch (e: Exception) {
            val trace = app.openFileOutput("stack.trace", Context.MODE_PRIVATE)
            trace.write(report.toByteArray())
            trace.close()
        }

        defaultUEH.uncaughtException(t, e)
    }

}