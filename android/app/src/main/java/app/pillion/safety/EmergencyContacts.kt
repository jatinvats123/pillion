package app.pillion.safety

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(val name: String, val number: String)

/**
 * Up to [MAX] emergency contacts and the rider's name for the SOS text. Stored only on the phone;
 * nothing here ever reaches the backend.
 */
class EmergencyContacts(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("safety", Context.MODE_PRIVATE)

    private val _contacts = MutableStateFlow(load())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts.asStateFlow()

    private val _riderName = MutableStateFlow(prefs.getString(KEY_NAME, null).orEmpty())
    val riderName: StateFlow<String> = _riderName.asStateFlow()

    /** False if the list is full or the number is already on it. */
    fun add(contact: EmergencyContact): Boolean {
        val list = _contacts.value
        if (list.size >= MAX || list.any { digits(it.number) == digits(contact.number) }) return false
        save(list + contact)
        return true
    }

    fun remove(index: Int) = save(_contacts.value.filterIndexed { i, _ -> i != index })

    /** Moves the contact at [index] one place up (-1) or down (+1). The first one is texted and called first. */
    fun move(index: Int, by: Int) {
        val list = _contacts.value.toMutableList()
        val target = index + by
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        save(list)
    }

    fun setRiderName(name: String) {
        _riderName.value = name.trim()
        prefs.edit { putString(KEY_NAME, name.trim()) }
    }

    private fun save(list: List<EmergencyContact>) {
        _contacts.value = list
        val json = JSONArray(list.map { JSONObject().put("name", it.name).put("number", it.number) })
        prefs.edit { putString(KEY_CONTACTS, json.toString()) }
    }

    private fun load(): List<EmergencyContact> = runCatching {
        val json = JSONArray(prefs.getString(KEY_CONTACTS, "[]"))
        List(json.length()) { i -> json.getJSONObject(i).let { EmergencyContact(it.getString("name"), it.getString("number")) } }
    }.getOrDefault(emptyList())

    private fun digits(number: String) = number.filter { it.isDigit() }.takeLast(10)

    companion object {
        const val MAX = 3
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_NAME = "rider_name"
    }
}
