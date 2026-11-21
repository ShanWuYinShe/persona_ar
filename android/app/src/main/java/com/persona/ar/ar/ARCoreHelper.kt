package com.persona.ar.ar

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException

object ARCoreHelper {

    fun isARCoreSupported(context: Context): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            availability.isSupported
        } catch (e: Exception) {
            false
        }
    }

    fun createSession(context: Context): Session? {
        if (!isARCoreSupported(context)) return null
        return try {
            Session(context).apply {
                val config = com.google.ar.core.Config(this)
                config.planeFindingMode = com.google.ar.core.Config.PlaneFindingMode.HORIZONTAL
                configure(config)
            }
        } catch (e: UnavailableException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
