package com.example.astrion.utils

import android.content.res.Resources
import com.example.astrion.R
import com.example.astrion.esphome.Connected
import com.example.astrion.esphome.Disconnected
import com.example.astrion.esphome.EspHomeState
import com.example.astrion.esphome.ServerError
import com.example.astrion.esphome.Stopped
import com.example.astrion.esphome.voiceassistant.Listening
import com.example.astrion.esphome.voiceassistant.Processing
import com.example.astrion.esphome.voiceassistant.Responding

fun EspHomeState.translate(resources: Resources): String = when (this) {
    is Stopped -> resources.getString(R.string.satellite_state_stopped)
    is Disconnected -> resources.getString(R.string.satellite_state_disconnected)
    is Connected -> resources.getString(R.string.satellite_state_idle)
    is Listening -> resources.getString(R.string.satellite_state_listening)
    is Processing -> resources.getString(R.string.satellite_state_processing)
    is Responding -> resources.getString(R.string.satellite_state_responding)
    is ServerError -> resources.getString(R.string.satellite_state_server_error, message)
    else -> this.toString()
}