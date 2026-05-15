package com.mawa.assistant.ai

import com.mawa.assistant.model.AppCommand

object CommandParser {

    private val openAppPatterns = listOf(
        Regex("(?:open|kholo|chalu karo|start)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:kholo|open karo|chalu karo)", RegexOption.IGNORE_CASE)
    )

    private val closeAppPatterns = listOf(
        Regex("(?:close|bondho karo|band karo)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:close karo|bondho karo|band karo)", RegexOption.IGNORE_CASE)
    )

    private val callPatterns = listOf(
        Regex("(?:call karo|phone karo|call)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:ke call karo|ke phone karo|ko call karo)", RegexOption.IGNORE_CASE)
    )

    private val smsPatterns = listOf(
        Regex("(?:sms pathao|message pathao|sms karo|msg karo|text karo)\\s+(.+?)\\s+(?:ke|ko)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(?:send sms|send message|text)\\s+(.+?)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:ke sms pathao|ke msg pathao|ke message karo|ko sms bhejo)\\s*(.*)", RegexOption.IGNORE_CASE)
    )

    private val whatsappPatterns = listOf(
        Regex("(?:whatsapp karo|whatsapp msg|whatsapp message)\\s+(.+?)\\s*(?:ke|ko)?\\s*(.*)", RegexOption.IGNORE_CASE),
        Regex("(.+?)\\s+(?:ke whatsapp karo|ko whatsapp karo)", RegexOption.IGNORE_CASE)
    )

    private val primeCallPatterns = listOf(
        Regex("(?:close friend|priyo bondhu|amar priyo bondo|my love|amar jaan|best friend)\\s*(?:ke|ko)?\\s*(?:call karo|phone karo|call)", RegexOption.IGNORE_CASE),
        Regex("(?:call|phone)\\s+(?:my close friend|my love|my best friend|amar priyo bondhu|amar jaan)", RegexOption.IGNORE_CASE),
        Regex("(?:call|phone)\\s+(?:my )?(?:first|second|third)\\s+(?:contact|prime contact)", RegexOption.IGNORE_CASE)
    )

    private val primeMsgPatterns = listOf(
        Regex("(?:close friend|priyo bondhu|amar priyo bondo|my love|amar jaan|best friend)\\s*(?:ke|ko)?\\s*(?:msg karo|message karo|sms karo|message pathao)", RegexOption.IGNORE_CASE),
        Regex("(?:message|msg|sms)\\s+(?:my close friend|my love|my best friend|amar priyo bondhu|amar jaan)", RegexOption.IGNORE_CASE)
    )

    fun parse(text: String): AppCommand? {
        val input = text.trim()

        for (pattern in primeCallPatterns) {
            if (pattern.containsMatchIn(input)) {
                val index = extractPrimeIndex(input)
                return AppCommand("PRIME_CALL", mapOf("index" to index.toString()))
            }
        }

        for (pattern in primeMsgPatterns) {
            if (pattern.containsMatchIn(input)) {
                val index = extractPrimeIndex(input)
                return AppCommand("PRIME_MSG", mapOf("index" to index.toString()))
            }
        }

        if (input.matches(Regex(".*(?:volume barao|volume up|volume increase|awaz barao).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("VOLUME_UP", emptyMap())
        }
        if (input.matches(Regex(".*(?:volume komao|volume down|volume decrease|awaz komao).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("VOLUME_DOWN", emptyMap())
        }
        if (input.matches(Regex(".*(?:torch on|flashlight on|torch jalo|torch chalu).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("FLASHLIGHT_ON", emptyMap())
        }
        if (input.matches(Regex(".*(?:torch off|flashlight off|torch bondho|torch band).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("FLASHLIGHT_OFF", emptyMap())
        }
        if (input.matches(Regex(".*(?:wifi on|wifi chalu|wifi jalo).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("WIFI_ON", emptyMap())
        }
        if (input.matches(Regex(".*(?:wifi off|wifi bondho|wifi band).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("WIFI_OFF", emptyMap())
        }
        if (input.matches(Regex(".*(?:bluetooth on|bluetooth chalu).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("BLUETOOTH_ON", emptyMap())
        }
        if (input.matches(Regex(".*(?:bluetooth off|bluetooth bondho|bluetooth band).*", RegexOption.IGNORE_CASE))) {
            return AppCommand("BLUETOOTH_OFF", emptyMap())
        }

        for (pattern in whatsappPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val message = if (match.groupValues.size > 2) match.groupValues[2].trim() else ""
                return if (input.contains("call", ignoreCase = true)) {
                    AppCommand("WHATSAPP_CALL", mapOf("name" to name))
                } else {
                    AppCommand("WHATSAPP_MSG", mapOf("name" to name, "message" to message))
                }
            }
        }

        for (pattern in smsPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val message = if (match.groupValues.size > 2) match.groupValues[2].trim() else ""
                return AppCommand("SMS", mapOf("name" to name, "message" to message))
            }
        }

        for (pattern in callPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val name = match.groupValues[1].trim()
                return AppCommand("CALL", mapOf("name" to name))
            }
        }

        for (pattern in closeAppPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                return AppCommand("CLOSE_APP", mapOf("app_name" to appName))
            }
        }

        for (pattern in openAppPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                return AppCommand("OPEN_APP", mapOf("app_name" to appName))
            }
        }

        return null
    }

    private fun extractPrimeIndex(input: String): Int {
        return when {
            input.contains("second", ignoreCase = true) || input.contains("2nd", ignoreCase = true) -> 1
            input.contains("third", ignoreCase = true) || input.contains("3rd", ignoreCase = true) -> 2
            else -> 0
        }
    }
}
