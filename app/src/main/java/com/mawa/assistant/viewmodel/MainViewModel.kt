package com.mawa.assistant.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mawa.assistant.model.AppCommand
import com.mawa.assistant.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    private val appMap = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "settings" to "com.android.settings",
        "calculator" to "com.google.android.calculator",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "phone" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
        "play store" to "com.android.vending",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "zoom" to "us.zoom.videomeetings",
        "meet" to "com.google.android.apps.meetings",
        "teams" to "com.microsoft.teams",
        "tiktok" to "com.zhiliaoapp.musically",
        "discord" to "com.discord",
        "linkedin" to "com.linkedin.android"
    )

    fun executeCommand(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = when (command.type) {
                "OPEN_APP" -> openApp(command.params["app_name"] ?: "")
                "CLOSE_APP" -> closeApp()
                "CALL" -> makeCall(command.params["name"] ?: "")
                "SMS" -> sendSms(command.params["name"] ?: "", command.params["message"] ?: "")
                "WHATSAPP_MSG" -> sendWhatsApp(command.params["name"] ?: "", command.params["message"] ?: "")
                "WHATSAPP_CALL" -> openWhatsAppCall(command.params["name"] ?: "")
                "PRIME_CALL" -> primCall(command.params["index"]?.toIntOrNull() ?: 0)
                "PRIME_MSG" -> primeMsg(command.params["index"]?.toIntOrNull() ?: 0)
                "VOLUME_UP" -> adjustVolume(true)
                "VOLUME_DOWN" -> adjustVolume(false)
                "FLASHLIGHT_ON" -> toggleFlashlight(true)
                "FLASHLIGHT_OFF" -> toggleFlashlight(false)
                "WIFI_ON" -> "Please enable WiFi from Settings manually"
                "WIFI_OFF" -> "Please disable WiFi from Settings manually"
                "BLUETOOTH_ON" -> toggleBluetooth(true)
                "BLUETOOTH_OFF" -> toggleBluetooth(false)
                else -> "Unknown command"
            }
            withContext(Dispatchers.Main) {
                _commandResult.value = result
            }
        }
    }

    private fun openApp(appName: String): String {
        val ctx = getApplication<Application>()
        val packageName = appMap[appName.lowercase()]
            ?: findAppByLabel(ctx, appName)
            ?: return "App '$appName' not found"

        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "$appName opened"
        } else {
            "Cannot open $appName"
        }
    }

    private fun findAppByLabel(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
        val lower = name.lowercase()
        return apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(lower)
        }?.packageName
    }

    private fun closeApp(): String {
        val service = AccessibilityHelperService.instance
        return if (service != null) {
            service.closeCurrentApp()
            "App closed"
        } else {
            "Accessibility service not enabled"
        }
    }

    private fun makeCall(name: String): String {
        val ctx = getApplication<Application>()
        val number = lookupContact(ctx, name) ?: return "Contact '$name' not found"
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Calling $name"
    }

    private fun sendSms(name: String, message: String): String {
        val ctx = getApplication<Application>()
        val number = lookupContact(ctx, name) ?: return "Contact '$name' not found"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$number"))
        intent.putExtra("sms_body", message)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Opening SMS to $name"
    }

    private fun sendWhatsApp(name: String, message: String): String {
        val ctx = getApplication<Application>()
        val number = lookupContact(ctx, name)?.replace("+", "")?.replace(" ", "")
            ?: return "Contact '$name' not found"
        val url = "https://wa.me/$number" + if (message.isNotEmpty()) "?text=${Uri.encode(message)}" else ""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Opening WhatsApp for $name"
    }

    private fun openWhatsAppCall(name: String): String {
        val ctx = getApplication<Application>()
        val number = lookupContact(ctx, name)?.replace("+", "")?.replace(" ", "")
            ?: return "Contact '$name' not found"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
        intent.setPackage("com.whatsapp")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Opening WhatsApp call for $name"
    }

    private fun primCall(index: Int): String {
        val contact = getPrimeContact(index) ?: return "Prime contact not set"
        val ctx = getApplication<Application>()
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.second}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Calling ${contact.first}"
    }

    private fun primeMsg(index: Int): String {
        val contact = getPrimeContact(index) ?: return "Prime contact not set"
        val ctx = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:${contact.second}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Opening message to ${contact.first}"
    }

    private fun getPrimeContact(index: Int): Pair<String, String>? {
        val ctx = getApplication<Application>()
        val prefs = ctx.getSharedPreferences("mawa_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("prime_contacts_json", null)
        if (json != null) {
            val arr = JSONArray(json)
            if (index < arr.length()) {
                val obj = arr.getJSONObject(index)
                return Pair(obj.getString("name"), obj.getString("number"))
            }
        } else {
            val name = prefs.getString("prime_name", null)
            val number = prefs.getString("prime_number", null)
            if (name != null && number != null && index == 0) {
                return Pair(name, number)
            }
        }
        return null
    }

    private fun adjustVolume(up: Boolean): String {
        val ctx = getApplication<Application>()
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
        return if (up) "Volume increased" else "Volume decreased"
    }

    private fun toggleFlashlight(on: Boolean): String {
        return try {
            val ctx = getApplication<Application>()
            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
            if (on) "Flashlight on" else "Flashlight off"
        } catch (e: CameraAccessException) {
            "Cannot access flashlight"
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth(on: Boolean): String {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return "Bluetooth not available"
        return if (on) {
            if (!adapter.isEnabled) adapter.enable()
            "Bluetooth turning on"
        } else {
            if (adapter.isEnabled) adapter.disable()
            "Bluetooth turning off"
        }
    }

    fun acceptCall(): String {
        return try {
            val ctx = getApplication<Application>()
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            "Call accepted"
        } catch (e: Exception) {
            "Cannot accept call: ${e.message}"
        }
    }

    fun rejectCall(): String {
        return try {
            val ctx = getApplication<Application>()
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            telecom.endCall()
            "Call rejected"
        } catch (e: Exception) {
            "Cannot reject call: ${e.message}"
        }
    }

    fun lookupContact(ctx: Context, name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var number: String? = null
        val cursor: Cursor? = ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                number = it.getString(0)
            }
        }
        return number
    }

    fun clearResult() {
        _commandResult.value = null
    }
}
