@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalMailStyle=staticCompositionLocalOf{MailStyle.STANDARD}
object AppearancePreferences {
    fun read(prefs: SharedPreferences)=MailStyle.fromKey(prefs.getString("mail-style",null))
    fun write(prefs: SharedPreferences,style: MailStyle){prefs.edit().putString("mail-style",style.key).apply()}
}
val MailStyle.artwork: Int get()=when(this) {
    MailStyle.COZY->R.drawable.header_cozy
    MailStyle.FOREST->R.drawable.header_forest
    MailStyle.FROST->R.drawable.header_frost
    MailStyle.EMERALD->R.drawable.header_emerald
    MailStyle.MEADOW->R.drawable.header_meadow
    else->R.drawable.header_business
}
val MailStyle.accent: Color get()=palette.accent
val MailStyle.navAccent: Color get()=if(this==MailStyle.COZY)Color(0xFF356331)else accent

// Contextual actions replace the toolbar's content, not its layout footprint.
internal val DecorativeHeaderHeight=112.dp

@Composable fun DecorativeHeader(title: String,account: String,onMenu: ()->Unit) {
    Box(Modifier.fillMaxWidth().height(DecorativeHeaderHeight)) {
        Row(Modifier.align(Alignment.CenterStart).padding(start=14.dp,top=20.dp).fillMaxWidth(0.76f),verticalAlignment=Alignment.CenterVertically) {
            HeaderButton(onMenu){Icon(Icons.Outlined.Menu,"Выбрать ящик")}
            Column(Modifier.padding(start=12.dp)) {
                Text(title,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color(0xFF171D28),fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text(account,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color(0xFF49505C),fontSize=13.sp,modifier=Modifier.padding(vertical=8.dp))
            }
        }
    }
}
@Composable private fun HeaderButton(onClick: ()->Unit,enabled: Boolean=true,content: @Composable ()->Unit) {
    Surface(shape=CircleShape,color=Color.White.copy(alpha=0.94f),contentColor=Color(0xFF303640)) {
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(40.dp),content=content)
    }
}

@Composable fun StyleChoice(style: MailStyle,selected: Boolean,onSelect: ()->Unit) {
    OutlinedCard(onClick=onSelect,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(if(selected)2.dp else 1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        if(style!=MailStyle.STANDARD)DecorativeArtwork(style,Modifier.fillMaxWidth().height(84.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            RadioButton(selected,onClick=null)
            Column(Modifier.weight(1f).padding(start=8.dp)){Text(style.title,fontWeight=FontWeight.SemiBold);Text(style.detail,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

/** Fit scene artwork without zooming/cropping the business desk at unusual window ratios. */
@Composable internal fun DecorativeArtwork(style: MailStyle,modifier: Modifier=Modifier) {
    Image(painterResource(style.artwork),null,modifier.background(style.palette.header),
        contentScale=if(style==MailStyle.BUSINESS)ContentScale.Fit else ContentScale.Crop,
        alignment=Alignment.CenterEnd)
}
