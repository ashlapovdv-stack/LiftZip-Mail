package ru.liftzip.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Local-only contacts. Independent of mail cache and account deletion. */
internal data class MailContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emails: List<String>
)

internal object ContactAddresses {
    fun normalize(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)

    fun clean(values: List<String>): List<String> =
        values.map(String::trim).filter { it.isNotEmpty() }
            .distinctBy(::normalize)

    fun suggestions(contacts: List<MailContact>, query: String): List<MailContact> {
        val term = query.trim().lowercase(java.util.Locale.ROOT)
        if (term.isEmpty()) return emptyList()
        return contacts.filter { contact ->
            contact.name.lowercase(java.util.Locale.ROOT).contains(term) ||
                contact.emails.any { normalize(it).contains(term) }
        }.sortedWith(compareBy<MailContact> {
            !it.name.lowercase(java.util.Locale.ROOT).startsWith(term)
        }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
    }
}

internal class ContactStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "contacts-v1.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE contact_emails (contact_id TEXT NOT NULL REFERENCES contacts(id) ON DELETE CASCADE, email TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY(contact_id, email))")
        db.execSQL("CREATE INDEX contact_emails_email ON contact_emails(email)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun all(): List<MailContact> {
        val db = readableDatabase
        return db.rawQuery("SELECT id,name FROM contacts ORDER BY name COLLATE NOCASE", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val emails = db.rawQuery(
                        "SELECT email FROM contact_emails WHERE contact_id=? ORDER BY rowid", arrayOf(id)
                    ).use { rows ->
                        buildList { while (rows.moveToNext()) add(rows.getString(0)) }
                    }
                    add(MailContact(id, cursor.getString(1), emails))
                }
            }
        }
    }

    @Synchronized fun save(contact: MailContact) {
        val name = contact.name.trim()
        val emails = ContactAddresses.clean(contact.emails)
        require(name.isNotEmpty() && emails.isNotEmpty()) { "Имя и адрес обязательны" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("contacts", null, ContentValues().apply {
                put("id", contact.id)
                put("name", name)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("contact_emails", "contact_id=?", arrayOf(contact.id))
            emails.forEach { email ->
                db.insertOrThrow("contact_emails", null, ContentValues().apply {
                    put("contact_id", contact.id)
                    put("email", email)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun delete(id: String) {
        writableDatabase.delete("contacts", "id=?", arrayOf(id))
    }
}
