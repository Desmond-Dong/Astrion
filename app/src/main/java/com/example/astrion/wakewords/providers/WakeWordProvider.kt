package com.example.astrion.wakewords.providers

import com.example.astrion.wakewords.models.WakeWordWithId

interface WakeWordProvider {
    suspend fun get(): List<WakeWordWithId>
}