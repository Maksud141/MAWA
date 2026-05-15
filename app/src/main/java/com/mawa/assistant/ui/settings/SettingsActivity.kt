package com.mawa.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mawa.assistant.R
import com.mawa.assistant.service.AccessibilityHelperService
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

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

    private val primeContacts = mutableListOf<PrimeContact>()
    private lateinit var primeAdapter: PrimeContactAdapter

    private val modelLabels = arrayOf(
        "Native Audio (Human Voice)",
        "Flash Live (Fast)",
        "Pro Audio Dialog"
    )
    private val modelValues = arrayOf(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-2.0-flash-live-001",
        "models/gemini-2.5-flash-preview-native-audio-dialog"
    )

    private val voiceLabels = arrayOf(
        "Aoede (Female)", "Charon (Male)", "Kore (Female)", "Fenrir (Male)",
        "Puck (Male)", "Leda (Female)", "Orus (Male)", "Zephyr (Female)"
    )
    private val voiceValues = arrayOf(
        "Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        setupPrimeContacts() 
        setupSpinners()
        loadSettings()  
        updateAccessibilityStatus()
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

        backBtn.setOnClickListener { finish() }
        saveBtn.setOnClickListener { saveSettings() }

        addPrimeBtn.setOnClickListener { showAddPrimeDialog() }

        accessibilityStatus.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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

    private fun loadSettings() {
        val prefs = getSharedPreferences("mawa_prefs", MODE_PRIVATE)

        apiKeyInput.setText(prefs.getString("api_key", ""))
        nameInput.setText(prefs.getString("user_name", ""))

        val savedModel = prefs.getString("gemini_model", modelValues[0])
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
        } else {
            val name = prefs.getString("prime_name", null)
            val number = prefs.getString("prime_number", null)
            if (name != null && number != null) {
                primeContacts.add(PrimeContact(name, number))
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

        val arr = JSONArray()
        for (contact in primeContacts) {
            arr.put(JSONObject().apply {
                put("name", contact.name)
                put("number", contact.number)
            })
        }
        prefs.putString("prime_contacts_json", arr.toString())

        prefs.apply()
        Toast.makeText(this, "Settings saved. Restart app to apply changes.", Toast.LENGTH_LONG).show()
    }

    private fun showAddPrimeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        val nameEdit = view.findViewById<EditText>(R.id.dialogNameInput)
        val numberEdit = view.findViewById<EditText>(R.id.dialogNumberInput)

        AlertDialog.Builder(this, R.style.Theme_Mawa)
            .setView(view)
            .setPositiveButton("ADD") { _, _ ->
                val name = nameEdit.text.toString().trim()
                val number = numberEdit.text.toString().trim()
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    primeContacts.add(PrimeContact(name, number))
                    primeAdapter.notifyItemInserted(primeContacts.size - 1)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun updateAccessibilityStatus() {
        val enabled = AccessibilityHelperService.isEnabled(this)
        accessibilityStatus.text = if (enabled) "Enabled" else "Disabled - Tap to enable"
        accessibilityStatus.setTextColor(
            if (enabled) getColor(R.color.success_green) else getColor(R.color.primary_red)
        )
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }
}

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
