package com.example.ticketassistant.data

data class Station(val name: String, val telecode: String, val city: String = "", val pinyin: String = "")

data class Train(
    val trainNo: String,
    val from: String,
    val to: String,
    val depart: String,
    val arrive: String,
    val duration: String,
    val seats: Map<String, String>,
    val saleTime: String? = null
)

data class TicketTask(
    val date: String,
    val from: Station,
    val to: Station,
    val train: Train,
    val seat: String,
    val passengerName: String,
    val saleTime: String,
    val maxRunMinutes: Int = 120,
    val enabled: Boolean = true,
    val status: TaskStatus = TaskStatus.ENABLED,
    val lastEvent: String? = null,
    val lastEventAt: Long? = null,
    val lastError: String? = null
)

enum class TaskStatus { DRAFT, ENABLED, PREPARING, SEARCHING, PENDING_PAYMENT, TAKEOVER, EXPIRED, DISABLED }
