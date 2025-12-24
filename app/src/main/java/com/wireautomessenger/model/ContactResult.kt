package com.wireautomessenger.model

data class ContactResult(
    val name: String,
    val status: ContactStatus,
    val errorMessage: String? = null,
    val position: Int // Position in the list (1-based)
)

enum class ContactStatus {
    SENT,
    FAILED,
    SKIPPED
}

