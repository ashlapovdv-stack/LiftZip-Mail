package ru.liftzip.mail

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** Keep measured bounds constant; only chrome rendering and hit targets change with zoom. */
internal fun Modifier.readerChrome(progress: Float,scrollOffset: Float=0f): Modifier=layout { measurable,constraints->
    val child=measurable.measure(constraints)
    val fraction=progress.coerceIn(0f,1f)
    layout(child.width,child.height) {
        if(fraction<0.999f)child.placeRelativeWithLayer(0,0) {
            translationY=-maxOf(scrollOffset,child.height*fraction)
            alpha=1f-fraction
        }
    }
}

/** A document inset scales and scrolls natively, behind the independently drawn Compose header. */
internal fun MailHtmlDocument.withReaderInset(headerPixels: Int,viewportWidth: Int,baseScale: Float?=null): MailHtmlDocument {
    val ratio=headerPixels.toDouble()/viewportWidth.coerceAtLeast(1)
    val cssHeight=baseScale?.takeIf{it.isFinite() && it>0f}?.let{"${headerPixels/it}px"}?:"calc(100vw * $ratio)"
    val inset="<div aria-hidden=\"true\" style=\"display:block!important;height:$cssHeight!important;margin:0!important;padding:0!important;border:0!important;pointer-events:none!important\"></div>"
    return copy(html=html.replaceFirst("<body>","<body>$inset"))
}

/** Added only after intrinsic measurement. This native canvas permits focal panning even for short mail. */
internal fun MailHtmlDocument.withReaderCanvas(viewportHeight: Int,viewportWidth: Int,baseScale: Float?=null): MailHtmlDocument {
    val ratio=viewportHeight.toDouble()/viewportWidth.coerceAtLeast(1)
    val cssHeight=baseScale?.takeIf{it.isFinite() && it>0f}?.let{"${viewportHeight/it}px"}?:"calc(100vw * $ratio)"
    return copy(html=html.replaceFirst("</head>","<style>html,body{min-height:$cssHeight!important}</style></head>"))
}
