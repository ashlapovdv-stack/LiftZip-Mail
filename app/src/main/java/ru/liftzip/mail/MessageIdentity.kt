package ru.liftzip.mail

import java.security.MessageDigest

object MessageIdentity {
    fun forDraft(id: String,existing: String=""): String {
        val original=existing.trim()
        if(original.matches(Regex("^<[^<>\\s@]+@[^<>\\s@]+>$")))return original
        val local=id.takeIf{it.matches(Regex("^[A-Za-z0-9._-]+$"))}?:MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}
        return "<$local@local.liftzip.mail>"
    }
}
