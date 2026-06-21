package com.emusite.plugin.onlineserietv

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.emusite.api.Plugin
import com.emusite.api.Source
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PluginImpl : Plugin {
    override val id = "onlineserietv"
    override val name = "OnlineSerieTV"
    override val version = "1.0.0"
    override val description = "Film e Serie TV in italiano da OnlineSerieTV"
    override val language = "it"
    override val iconUrl: String? = null

    private var appContext: Context? = null

    override fun getSources(): List<Source> = listOf(OnlineSerieTVSource(appContext))

    override fun onInit(context: Context) {
        appContext = context
    }
}

internal suspend fun solveCaptcha(base64Image: String, ctx: Context?): String? = suspendCoroutine { cont ->
    if (ctx == null) { cont.resume(null); return@suspendCoroutine }
    android.os.Handler(ctx.mainLooper).post {
        try {
            val bytes = Base64.decode(base64Image, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40)
            }
            layout.addView(ImageView(ctx).apply { setImageBitmap(bitmap); adjustViewBounds = true })
            val input = EditText(ctx).apply {
                hint = "Inserisci i numeri"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            layout.addView(input)
            AlertDialog.Builder(ctx)
                .setTitle("Verifica CAPTCHA")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("Sblocca") { _, _ -> cont.resume(input.text.toString().trim()) }
                .setNegativeButton("Annulla") { _, _ -> cont.resume(null) }
                .setOnDismissListener { cont.resume(null) }
                .show()
        } catch (e: Exception) { cont.resume(null) }
    }
}
