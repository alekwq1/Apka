package pl.fujara.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Przyciski Start / Pauza / Stop w stalym powiadomieniu FUJARY. */
class WorkShiftReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = WorkSessionStore(context)
        when (intent.action) {
            ACTION_START -> store.start()
            ACTION_PAUSE -> store.pause()
            ACTION_STOP -> store.stop()
        }
    }

    companion object {
        const val ACTION_START = "pl.fujara.app.action.WORK_START"
        const val ACTION_PAUSE = "pl.fujara.app.action.WORK_PAUSE"
        const val ACTION_STOP = "pl.fujara.app.action.WORK_STOP"
    }
}
