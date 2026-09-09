package com.example.ticketassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class StationRepository {
    suspend fun fetchStations(): List<Station> = withContext(Dispatchers.IO) {
        val connection = (URL(STATION_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(body)
        } finally { connection.disconnect() }
    }

    private fun parse(body: String): List<Station> {
        return body.substringAfter("=")
            .split('@')
            .mapNotNull { entry ->
                val fields = entry.split('|')
                // Official resource: shortCode|stationName|telecode|pinyin|...
                val name = fields.getOrNull(1)?.trim().orEmpty()
                val telecode = fields.getOrNull(2)?.trim().orEmpty()
                if (name.isBlank() || !telecode.matches(Regex("[A-Z]{2,4}"))) null
                else Station(name, telecode, fields.getOrNull(4).orEmpty(), fields.getOrNull(3).orEmpty())
            }.distinctBy { it.telecode }
    }

    companion object {
        private const val STATION_URL = "https://kyfw.12306.cn/otn/resources/js/framework/station_name.js"
        const val USER_AGENT = "Mozilla/5.0 (Android) TicketAssistant/0.1"
    }
}
