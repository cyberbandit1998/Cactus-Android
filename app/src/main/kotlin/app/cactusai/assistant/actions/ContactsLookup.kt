package app.cactusai.assistant.actions

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone

data class PhoneEntry(val contactId: Long, val name: String, val number: String, val typeLabel: String)

/** Reads contact names and numbers, only after the user granted READ_CONTACTS. Nothing is stored or sent. */
class ContactsLookup(private val context: Context) {
    fun load(): List<PhoneEntry> {
        val out = ArrayList<PhoneEntry>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameCol = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numCol = c.getColumnIndexOrThrow(Phone.NUMBER)
            val typeCol = c.getColumnIndexOrThrow(Phone.TYPE)
            val labelCol = c.getColumnIndexOrThrow(Phone.LABEL)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val number = c.getString(numCol) ?: continue
                val type = Phone.getTypeLabel(context.resources, c.getInt(typeCol), c.getString(labelCol)).toString()
                out += PhoneEntry(c.getLong(idCol), name, number, type)
            }
        }
        return out
    }
}
