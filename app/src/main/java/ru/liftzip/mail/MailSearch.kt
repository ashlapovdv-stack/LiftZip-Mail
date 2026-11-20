package ru.liftzip.mail

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal object MailSearch {
    private val whitespace=Regex("\\s+")
    private const val BATCH_SIZE=16
    private const val MAX_SQL_TERMS=32

    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE message_search_keys(docid INTEGER PRIMARY KEY, message_id TEXT NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(grams, text, notindexed=text, matchinfo=fts3)")
        db.execSQL("CREATE INDEX message_search_pending ON messages(id) WHERE search_pending=1")
        db.execSQL("""
            CREATE TRIGGER message_search_delete BEFORE DELETE ON message_search_keys
            BEGIN
                DELETE FROM message_search WHERE docid=old.docid;
            END
        """.trimIndent())
    }

    private fun text(row: JSONObject)=MailContent.searchText(
        HeaderText.decode(row.getString("sender")),row.getString("email"),
        HeaderText.decode(row.getString("subject")),row.getString("body"),
        HeaderText.recipients(row.getString("to")),HeaderText.recipients(row.optString("cc")),row.optString("bcc")
    )

    // Encode UTF-16 trigrams as ASCII tokens so punctuation and non-Latin fragments
    // retain substring semantics. Hash collisions only add candidates for the exact check.
    private fun gram(text: String,offset: Int): Int =
        ((text[offset].code*65521 + text[offset+1].code)*65521 + text[offset+2].code) and 0xfffff

    private fun tokens(text: String): String {
        val grams=mutableSetOf<Int>()
        for(offset in 0..text.length-3)grams+=gram(text,offset)
        return grams.joinToString(" "){ "g"+it.toString(36) }
    }

    fun put(db: SQLiteDatabase,messageId: String,row: JSONObject) {
        // VACUUM may renumber messages.rowid. Only an explicit integer key is stable.
        val key=db.rawQuery("SELECT docid FROM message_search_keys WHERE message_id=?",arrayOf(messageId)).use {
            if(it.moveToFirst())it.getLong(0)else null
        }
        val docId=key?:db.insertOrThrow("message_search_keys",null,ContentValues().apply {
            put("message_id",messageId)
        })
        val searchable=text(row)
        val old=if(key==null)null else db.rawQuery("SELECT text FROM message_search WHERE docid=?",arrayOf(docId.toString())).use{
            if(it.moveToFirst())it.getString(0)else null
        }
        if(old==searchable)return
        if(old!=null)db.delete("message_search","docid=?",arrayOf(docId.toString()))
        db.insertOrThrow("message_search",null,ContentValues().apply {
            put("docid",docId)
            put("grams",tokens(searchable))
            put("text",searchable)
        })
    }

    fun indexBatch(db: SQLiteDatabase,checkCancelled: ()->Unit): Boolean {
        db.beginTransactionNonExclusive()
        try {
            val rows=db.rawQuery("SELECT id,data FROM messages WHERE search_pending=1 ORDER BY id LIMIT $BATCH_SIZE",null).use{cursor->
                buildList {
                    while(cursor.moveToNext()){
                        checkCancelled()
                        add(cursor.getString(0) to JSONObject(cursor.getString(1)))
                    }
                }
            }
            for((id,row) in rows) {
                checkCancelled()
                put(db,id,row)
                db.execSQL("UPDATE messages SET search_pending=0 WHERE id=?",arrayOf(id))
            }
            db.setTransactionSuccessful()
            return rows.size==BATCH_SIZE
        }finally{db.endTransaction()}
    }

    fun find(db: SQLiteDatabase,query: String,checkCancelled: ()->Unit): Set<String> {
        val terms=query.lowercase().trim().split(whitespace).filter(String::isNotBlank).distinct()
        if(terms.isEmpty())return emptySet()
        val sqlTerms=terms.take(MAX_SQL_TERMS)
        val extraTerms=terms.drop(MAX_SQL_TERMS)
        val grams=sqlTerms.asSequence().filter{it.length>=3}.flatMap{term->
            val last=term.length-3
            sequenceOf(0,last/2,last).distinct().map{gram(term,it)}
        }.distinct().joinToString(" "){"g"+it.toString(36)}
        val params=buildList {
            if(grams.isNotEmpty())add(grams)
            addAll(sqlTerms)
        }
        val match=if(grams.isEmpty())"" else "message_search MATCH ? AND "
        val contains=sqlTerms.joinToString(" AND "){"instr(s.text,?)>0"}
        val extraText=if(extraTerms.isEmpty())"NULL" else "s.text"
        checkCancelled()
        return db.rawQuery("""
            SELECT k.message_id, $extraText, NULL FROM message_search s JOIN message_search_keys k ON s.docid=k.docid
            WHERE $match$contains
            UNION ALL
            SELECT id, NULL, data FROM messages WHERE search_pending=1
        """.trimIndent(),params.toTypedArray()).use{cursor->buildSet{
            while(cursor.moveToNext()) {
                checkCancelled()
                val matches=if(!cursor.isNull(2)){
                    val searchable=text(JSONObject(cursor.getString(2)))
                    terms.all{it in searchable}
                }else if(extraTerms.isEmpty())true
                else extraTerms.all{it in cursor.getString(1)}
                if(matches)add(cursor.getString(0))
            }
        }}
    }
}
