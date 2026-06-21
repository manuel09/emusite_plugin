package com.emusite.plugin.onlineserietv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.emusite.api.Plugin
import com.emusite.api.Source

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

internal fun solveCaptcha(base64Image: String, context: Context?): String? {
    val activity = context as? Activity ?: return null
    var result: String? = null
    val lock = java.util.concurrent.CountDownLatch(1)
    activity.runOnUiThread {
        try {
            val bytes = Base64.decode(base64Image, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 40)
            }
            val imageView = ImageView(activity).apply { setImageBitmap(bitmap); adjustViewBounds = true }
            val input = EditText(activity).apply {
                hint = "Inserisci i numeri che vedi"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            layout.addView(imageView)
            layout.addView(input)
            AlertDialog.Builder(activity)
                .setTitle("Verifica CAPTCHA")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("Sblocca") { _, _ -> result = input.text.toString().trim(); lock.countDown() }
                .setNegativeButton("Annulla") { _, _ -> lock.countDown() }
                .show()
        } catch (e: Exception) { lock.countDown() }
    }
    lock.await()
    return result
}
