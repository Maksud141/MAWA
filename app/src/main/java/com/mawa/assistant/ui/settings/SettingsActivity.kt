package com.mawa.assistant.ui.settings

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mawa.assistant.R
import com.mawa.assistant.service.AccessibilityHelperService
import com.mawa.assistant.service.MawaDeviceAdminReceiver
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    // ── Original MAWA Views ──────────────────────────────────────────
    private lateinit var apiKeyInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var personalityGroup: RadioGroup
    private lateinit var primeContactsRecycler: RecyclerView
    private lateinit var addPrimeBtn: View
    private lateinit var accessibilityStatus: TextView
    private lateinit var saveBtn: View
    private lateinit var backBtn: ImageButton

    // ── NEW MYRA Views (Added to MAWA) ───────────────────────────────
    private lateinit var callAnnounceSwitch: Switch
    private lateinit var grantPermissionsBtn: Button
    private lateinit var setDefaultAssistantBtn: Button
    private lateinit var permissionsStatusText: TextView
    private lateinit var deviceAdminBtn: Button
    private lateinit var adminStatusText: TextView

    private val primeContacts = mutableListOf<PrimeContact>()
    private lateinit var primeAdapter: PrimeContactAdapter

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private val modelLabels = arrayOf("Native Audio (Human Voice)", "Flash Live (Fast)", "Pro Audio Dialog")
    private val modelValues = arrayOf(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-2.0-flash-exp", // Updated to latest fast model
        "models/gemini-2.5-flash-preview-native-audio-dialog"
    )

    private val voiceLabels = arrayOf("Aoede (Female)", "Charon (Male)", "Kore (Female)", "Fenrir (Male)", "Puck (Male)", "Leda (Female)", "Orus (Male)", "Zephyr (Female)")
    private val voiceValues = arrayOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")

    private val allPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CAMERA, Manifest.permission.MODIFY_AUDIO_SETTINGS
    )

    // Contact Picker Launcher (MYRA feature)
    private val contactPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            handleContactResult(contactUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, MawaDeviceAdminReceiver::class.java)

        initViews()
        setupPrimeContacts()
        setupSpinners()
        loadSettings()
        setupListeners()
        updateAllStatuses()
    }

    private fun initViews() {
        apiKeyInput = findViewById(R.id.apiKeyInput)
        nameInput = findViewById(R.id.nameInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        personalityGroup = findViewById(R.id.personalityGroup)
        primeContactsRecycler = findViewById(R.id.primeContactsRecycler)
        addPrimeBtn = findViewById(R.id.addPrimeBtn)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        saveBtn = findViewById(R.id.saveBtn)
        backBtn = findViewById(R.id.backBtn)

        // Find new views (Make sure these IDs exist in your XML)
        callAnnounceSwitch = findViewById(R.id.callAnnounceSwitch)
        grantPermissionsBtn = findViewById(R.id.grantPermissionsBtn)
        setDefaultAssistantBtn = findViewById(R.id.setDefaultAssistantBtn)
        permissionsStatusText = findViewById(R.id.permissionsStatusText)
        deviceAdminBtn = findViewById(R.id.deviceAdminBtn)
        adminStatusText = findViewById(R.id.adminStatusText)
    }

    private fun setupSpinners() {
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelLabels)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = modelAdapter

        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceLabels)
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = voiceAdapter
    }

    private fun setupPrimeContacts() {
        primeAdapter = PrimeContactAdapter(primeContacts) { index ->
            primeContacts.removeAt(index)
            primeAdapter.notifyItemRemoved(index)
        }
        primeContactsRecycler.layoutManager = LinearLayoutManager(this)
        primeContactsRecycler.adapter = primeAdapter
    }

    private fun setupListeners() {
        backBtn.setOnClickListener { finish() }
        saveBtn.setOnClickListener { saveSettings() }

        // Open Phonebook to pick contact directly (MYRA style)
        addPrimeBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        }

        accessibilityStatus.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        grantPermissionsBtn.setOnClickListener { checkAndRequestPermissions() }

        setDefaultAssistantBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                Toast.makeText(this, "MAWA ke Default Assistant hishebe select korun 👆", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Settings → Apps → Default Apps → Assistant → MAWA", Toast.LENGTH_LONG).show()
            }
        }

        deviceAdminBtn.setOnClickListener {
            if (!devicePolicyManager.isAdminActive(componentName)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "MAWA er jonno admin dorkar.")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Admin already on ache ✅", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = allPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
        } else {
            Toast.makeText(this, "Shob permission deya ache! ✅", Toast.LENGTH_SHORT).show()
            updateAllStatuses()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            if (granted == permissions.size) {
                Toast.makeText(this, "Shob permission peyechi! ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "$granted/${permissions.size} permission paoa geche ⚠️", Toast.LENGTH_LONG).show()
            }
            updateAllStatuses()
        }
    }

    private fun handleContactResult(uri: android.net.Uri) {
        val cursor = contentResolver.query(uri, null, null, null, null)
        if (cursor?.moveToFirst() == true) {
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val name = cursor.getString(nameIndex)
            val number = cursor.getString(numIndex).replace(" ", "")
            
            primeContacts.add(PrimeContact(name, number))
            primeAdapter.notifyItemInserted(primeContacts.size - 1)
        }
        cursor?.close()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("mawa_prefs", MODE_PRIVATE)

        apiKeyInput.setText(prefs.getString("api_key", ""))
        nameInput.setText(prefs.getString("user_name", ""))

        val savedModel = prefs.getString("gemini_model", modelValues[1]) // Default to fast model
        val modelIndex = modelValues.indexOf(savedModel).coerceAtLeast(0)
        modelSpinner.setSelection(modelIndex)

        val savedVoice = prefs.getString("gemini_voice", voiceValues[0])
        val voiceIndex = voiceValues.indexOf(savedVoice).coerceAtLeast(0)
        voiceSpinner.setSelection(voiceIndex)

        when (prefs.getString("personality_mode", "gf")) {
            "gf" -> personalityGroup.check(R.id.radioGF)
            "professional" -> personalityGroup.check(R.id.radioProfessional)
            "assistant" -> personalityGroup.check(R.id.radioAssistant)
        }

        callAnnounceSwitch.isChecked = prefs.getBoolean("call_announce_enabled", true)
        loadPrimeContacts(prefs)
    }

    private fun loadPrimeContacts(prefs: android.content.SharedPreferences) {
        val json = prefs.getString("prime_contacts_json", null)
        if (json != null) {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                primeContacts.add(PrimeContact(obj.getString("name"), obj.getString("number")))
            }
        }
        primeAdapter.notifyDataSetChanged()
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("mawa_prefs", MODE_PRIVATE).edit()

        prefs.putString("api_key", apiKeyInput.text.toString().trim())
        prefs.putString("user_name", nameInput.text.toString().trim())
        prefs.putString("gemini_model", modelValues[modelSpinner.selectedItemPosition])
        prefs.putString("gemini_voice", voiceValues[voiceSpinner.selectedItemPosition])

        val personality = when (personalityGroup.checkedRadioButtonId) {
            R.id.radioGF -> "gf"
            R.id.radioProfessional -> "professional"
            R.id.radioAssistant -> "assistant"
            else -> "gf"
        }
        prefs.putString("personality_mode", personality)
        prefs.putBoolean("call_announce_enabled", callAnnounceSwitch.isChecked)

        val arr = JSONArray()
        for (contact in primeContacts) {
            arr.put(JSONObject().apply {
                put("name", contact.name)
                put("number", contact.number)
            })
        }
        prefs.putString("prime_contacts_json", arr.toString())

        prefs.apply()
        Toast.makeText(this, "Settings save kora hoyeche. App restart korun.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun updateAllStatuses() {
        // Accessibility
        val accEnabled = AccessibilityHelperService.isEnabled(this)
        accessibilityStatus.text = if (accEnabled) "Enabled" else "Disabled - Tap to enable"
        accessibilityStatus.setTextColor(if (accEnabled) getColor(R.color.success_green) else getColor(R.color.primary_red))

        // Device Admin
        val adminActive = devicePolicyManager.isAdminActive(componentName)
        adminStatusText.text = if (adminActive) "✅ Admin Active" else "❌ Admin Inactive"
        adminStatusText.setTextColor(if (adminActive) getColor(R.color.success_green) else getColor(R.color.primary_red))

        // Permissions
        val missing = allPermissions.count { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        permissionsStatusText.text = when {
            missing == 0 -> "✅ All Permissions Granted"
            missing <= 2 -> "⚠️ $missing permissions pending"
            else -> "❌ $missing permissions missing"
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllStatuses()
    }
}

// ─── Adapter & Data Class ─────────────────────────────────────────────────────────────
data class PrimeContact(val name: String, val number: String)

class PrimeContactAdapter(
    private val contacts: MutableList<PrimeContact>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.primeItemName)
        val numberText: TextView = view.findViewById(R.id.primeItemNumber)
        val deleteBtn: ImageButton = view.findViewById(R.id.primeItemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.nameText.text = contact.name
        holder.numberText.text = contact.number
        holder.deleteBtn.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount(): Int = contacts.size
}
