package com.jarvis.assistant.home

import android.content.Context
import com.jarvis.assistant.clock.PrefsKeyValueStore
import com.jarvis.assistant.home.adapters.BleGattAdapter
import com.jarvis.assistant.home.adapters.BluetoothClassicAdapter
import com.jarvis.assistant.home.adapters.DeviceDiscovery
import com.jarvis.assistant.home.adapters.HomeSecrets
import com.jarvis.assistant.home.adapters.HttpTransport
import com.jarvis.assistant.home.adapters.LanHttpAdapter
import com.jarvis.assistant.home.adapters.OkHttpTransport
import com.jarvis.assistant.home.automation.AutomationEngine
import com.jarvis.assistant.home.automation.AutomationScheduler
import com.jarvis.assistant.home.automation.AutomationStore
import com.jarvis.assistant.home.remote.SupabaseRemote

/** The one shared set of Home objects. The Home screens, the voice tools, the workers and the hub sync all use these. */
object HomeServices {
    private const val PREFS = "jarvis_home"

    @Volatile private var registry: HomeRegistry? = null
    @Volatile private var controller: HomeController? = null
    @Volatile private var store: AutomationStore? = null
    @Volatile private var engine: AutomationEngine? = null
    @Volatile private var remote: SupabaseRemote? = null
    val http: HttpTransport by lazy { OkHttpTransport() }

    fun registry(context: Context): HomeRegistry = registry ?: synchronized(this) {
        registry ?: HomeRegistry(PrefsKeyValueStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))).also {
            HomeSecrets.init(context)
            registry = it
        }
    }

    fun remote(context: Context): SupabaseRemote = remote ?: synchronized(this) {
        remote ?: SupabaseRemote(http = okhttp3.OkHttpClient()).also { remote = it }
    }

    fun automationStore(context: Context): AutomationStore = store ?: synchronized(this) {
        store ?: AutomationStore(PrefsKeyValueStore(context.applicationContext.getSharedPreferences(PREFS + "_auto", Context.MODE_PRIVATE))).also { store = it }
    }

    fun controller(context: Context): HomeController = controller ?: synchronized(this) {
        controller ?: run {
            val app = context.applicationContext
            HomeController(
                registry(app),
                listOf(LanHttpAdapter(http), BleGattAdapter(app), BluetoothClassicAdapter(app)),
                remote(app).dispatcher()
            ) { device, powered -> engine?.onDeviceState(device.id, powered) }.also { controller = it }
        }
    }

    fun engine(context: Context): AutomationEngine = engine ?: synchronized(this) {
        engine ?: run {
            val app = context.applicationContext
            AutomationEngine(registry(app), controller(app), automationStore(app),
                continuation = { id, step, delay -> AutomationScheduler.scheduleContinuation(app, id, step, delay) }).also { engine = it }
        }
    }

    fun discovery(context: Context) = DeviceDiscovery(context.applicationContext)

    /** Ensures state-trigger evaluation is wired even if only the controller was touched first. */
    fun warm(context: Context) { engine(context) }
}
