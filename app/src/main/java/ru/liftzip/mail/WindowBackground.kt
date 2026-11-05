package ru.liftzip.mail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

fun ComponentActivity.edgeMailWindow() {
    enableEdgeToEdge(statusBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
        navigationBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
    if(Build.VERSION.SDK_INT>=29)window.isNavigationBarContrastEnforced=false
}

/** Artwork fills the window; individual visible controls own their safe insets. */
@Composable fun InboxCanvas(style: MailStyle,content: @Composable ()->Unit) {
    val density=LocalDensity.current
    val top=with(density){WindowInsets.safeDrawing.getTop(density).toDp()}
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if(style!=MailStyle.STANDARD)Box(Modifier.fillMaxWidth().height(140.dp+top)) {
            Image(painterResource(style.artwork),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            // Keep the shared artwork visible while protecting dark clock/icons over foliage.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top+24.dp)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha=0.7f),Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(18.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent,MaterialTheme.colorScheme.surface))))
        }
        content()
    }
}

/** The visible screen owns bar appearance, including when the drawer covers the header. */
@Composable fun SystemBarIcons(decorativeStatus: Boolean=false) {
    val activity=LocalContext.current.activity()?:return
    val lightNavigation=!isSystemInDarkTheme()
    val lightStatus=decorativeStatus || lightNavigation
    val controller=remember(activity){WindowCompat.getInsetsController(activity.window,activity.window.decorView)}
    SideEffect {
        if(controller.isAppearanceLightStatusBars!=lightStatus)controller.isAppearanceLightStatusBars=lightStatus
        if(controller.isAppearanceLightNavigationBars!=lightNavigation)controller.isAppearanceLightNavigationBars=lightNavigation
        if(Build.VERSION.SDK_INT>=29 && activity.window.isNavigationBarContrastEnforced)activity.window.isNavigationBarContrastEnforced=false
    }
}
private fun Context.activity(): Activity? {
    var next=this
    while(next is ContextWrapper) {
        if(next is Activity)return next
        val base=next.baseContext
        if(base===next)return null
        next=base
    }
    return next as? Activity
}
