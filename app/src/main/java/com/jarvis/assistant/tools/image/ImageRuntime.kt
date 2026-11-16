package com.jarvis.assistant.tools.image

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.provider.ProviderRuntime
import java.io.File

/** The one [ImageService] for the whole app, so the ChatBox shortcut and the model's tools share keys, folder and model discovery. */
object ImageRuntime {
    private const val PREFS = "jarvis_image"
    private const val KEY_MODEL = "preferred_model"
    @Volatile private var instance: ImageService? = null

    /** Lives as long as the app, so a picture that is being made is not lost when the person leaves the ChatBox. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    fun service(context: Context): ImageService = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            ImageService(
                transport = ProviderRuntime.transport(),
                apiKey = { JarvisApp.instance.preferences.apiKey },
                outDir = File(app.filesDir, "generated_images"),
                preferredModel = { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, "").orEmpty() }
            ).also { instance = it }
        }
    }

    /** Lets a person (or Jarvis) pin an image model; an empty value goes back to automatic choice. */
    fun setPreferredModel(context: Context, model: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, model.trim()).apply()
    }
}
