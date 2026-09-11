package com.example.ticketassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TrainRepository {
    @Volatile private var cookie: String = ""

    /** Prepare the read-only query session without making it a purchase gate. */
    suspend fun warmUpSession(): Boolean = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) cookie = initSession()
        cookie.isNotBlank()
    }

    suspend fun query(date: String, from: Station, to: Station): List<Train> = queryInternal(date, from, to, allowRefresh = true)

    private suspend fun queryInternal(date: String, from: Station, to: Station, allowRefresh: Boolean): List<Train> = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) cookie = initSession()
        val params = listOf(
            "leftTicketDTO.train_date" to date,
            "leftTicketDTO.from_station" to from.telecode,
            "leftTicketDTO.to_station" to to.telecode,
            "purpose_codes" to "ADULT"
        ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val url = URL("https://kyfw.12306.cn/otn/leftTicket/queryG?$params")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
            setRequestProperty("Referer", "https://kyfw.12306.cn/otn/leftTicket/init")
            if (this@TrainRepository.cookie.isNotBlank()) setRequestProperty("Cookie", this@TrainRepository.cookie)
        }
        try {
            val responseCode = connection.responseCode
            val text = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode == 429) error("12306 接口触发限流（HTTP 429）")
            if (allowRefresh && (responseCode == 401 || responseCode == 403 || text.trimStart().startsWith("<"))) {
                cookie = ""
                return@withContext retryQuery(date, from, to)
            }
            if (responseCode !in 200..299) error("12306 查询失败（HTTP $responseCode）")
            if (text.trimStart().startsWith("<")) error("12306 返回了网页而不是查询数据")
            parse(text)
        } finally { connection.disconnect() }
    }

    suspend fun assessSaleState(date: String, from: Station, to: Station, target: Train, seat: String): SaleAssessment =
        withContext(Dispatchers.IO) {
            val trains = query(date, from, to)
            val match = trains.firstOrNull {
                it.trainNo.equals(target.trainNo, ignoreCase = true) &&
                    it.from == target.from && it.to == target.to &&
                    it.depart == target.depart && it.arrive == target.arrive
            } ?: return@withContext SaleAssessment(SaleState.UNKNOWN, targetFound = false)
            val value = match.seats[seat]?.trim().orEmpty()
            if (value.isNotBlank() && value !in UNAVAILABLE_SEAT_VALUES) {
                SaleAssessment(SaleState.ALREADY_ON_SALE, targetFound = true)
            } else {
                SaleAssessment(SaleState.UNKNOWN, targetFound = true)
            }
        }

    private suspend fun retryQuery(date: String, from: Station, to: Station): List<Train> {
        cookie = initSession()
        return queryInternal(date, from, to, allowRefresh = false)
    }

    private fun initSession(): String {
        val connection = (URL("https://kyfw.12306.cn/otn/leftTicket/init").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        return try {
            connection.inputStream.close()
            connection.headerFields["Set-Cookie"].orEmpty()
                .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotBlank) }
                .joinToString("; ")
        } finally { connection.disconnect() }
    }

    private fun parse(text: String): List<Train> {
        val root = JSONObject(text)
        if (!root.optBoolean("httpstatus", true)) error(root.optString("messages", "12306 查询失败"))
        val data = root.optJSONObject("data") ?: error("查询结果缺少 data 字段")
        val rows = data.optJSONArray("result") ?: return emptyList()
        val stations = data.optJSONObject("map") ?: JSONObject()
        return buildList {
            for (i in 0 until rows.length()) {
                val fields = rows.optString(i).split("|")
                if (fields.size < 35) continue
                val trainNo = fields[3]
                val fromCode = fields[6]
                val toCode = fields[7]
                val depart = fields[8]
                val arrive = fields[9]
                val duration = fields[10]
                if (trainNo.isBlank() || depart.isBlank()) continue
                val seatMap = linkedMapOf<String, String>()
                // These indexes are the official leftTicket result-seat fields.
                listOf(
                    32 to "商务座",
                    25 to "特等座",
                    31 to "一等座",
                    30 to "二等座",
                    21 to "高级软卧",
                    23 to "软卧",
                    33 to "动卧",
                    28 to "硬卧",
                    24 to "软座",
                    29 to "硬座",
                    26 to "无座"
                )
                    .forEach { (index, label) -> fields.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { seatMap[label] = it } }
                add(Train(trainNo, stations.optString(fromCode, fromCode), stations.optString(toCode, toCode), depart, arrive, duration, seatMap))
            }
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        val UNAVAILABLE_SEAT_VALUES = setOf("无", "无票", "--", "*")
    }
}

data class SaleAssessment(
    val state: SaleState,
    val targetFound: Boolean
)
