package com.jarvis.assistant.provider

import android.content.Context

/**
 * One shared provider configuration manager for the whole app, so voice and ChatBox always read the same
 * active provider, model, endpoint, authentication and capabilities.
 */
object ProviderRuntime {

    @Volatile private var instance: ProviderConfigManager? = null
    @Volatile private var transportInstance: HttpTransport? = null

    fun transport(): HttpTransport = transportInstance ?: synchronized(this) {
        transportInstance ?: OkHttpTransport().also { transportInstance = it }
    }

    fun manager(context: Context): ProviderConfigManager = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val repo = ProviderRepository(SharedPrefsKeyValueStore(app), AndroidKeyVault(app))
            ProviderConfigManager(repo, transport()).also { instance = it }
        }
    }

    @Volatile private var engine: ProviderChatEngine? = null

    /** ChatBox's engine. It lives for the whole process so an answer keeps streaming while the user switches screens. */
    fun chatEngine(context: Context): ProviderChatEngine = engine ?: synchronized(this) {
        engine ?: run {
            val app = com.jarvis.assistant.JarvisApp.instance
            ProviderChatEngine(
                config = manager(context),
                transport = transport(),
                store = ProviderChatBridge(app.chatRepository),
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
                systemPrompt = { ChatContext.systemPrompt(app.preferences.userName) }
            ).also { engine = it }
        }
    }
}
