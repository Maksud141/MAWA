package com.mawa.assistant.model

data class AppCommand(
    val type: String,
    val params: Map<String, String>
)
