package ru.liftzip.mail

import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

object AccountColorSlots {
    fun next(used: Collection<Int>): Int {
        val reserved=used.filter{it>=0}.toSet()
        return generateSequence(0){it+1}.first{it !in reserved}
    }
    fun assign(accounts: List<MailAccount>): List<MailAccount> {
        val reserved=accounts.map{it.colorSlot}.filter{it>=0}.toSet()
        val used=mutableSetOf<Int>()
        return accounts.map { account->
            val slot=account.colorSlot.takeIf{it>=0 && it !in used}?:next(used+reserved)
            used+=slot
            account.copy(colorSlot=slot)
        }
    }
}

data class AccountColor(val background: Color,val foreground: Color)

@Composable fun accountColor(account: MailAccount?): AccountColor {
    val slot=account?.colorSlot?.takeIf{it>=0}?:0
    val hue=(slot*137.50776f)%360f
    return if(isSystemInDarkTheme()) AccountColor(Color.hsv(hue,0.40f,0.29f),Color.hsv(hue,0.28f,0.98f))
    else AccountColor(Color.hsv(hue,0.20f,0.97f),Color.hsv(hue,0.72f,0.38f))
}
