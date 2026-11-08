package com.jarvis.assistant.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.offline.OfflineModelDownloader
import com.jarvis.assistant.security.AiEngineStore
import com.jarvis.assistant.util.PermissionHelper

/**
 * Reads the real state of the app from the components that already own it. Nothing here is remembered or
 * assumed: every call re-reads Android and the existing preferences.
 */
object OnboardingStatusReader {

    fun read(context: Context, store: OnboardingStore): LiveStatus {
        val app = context.applicationContext
        val prefs = JarvisApp.instance.preferences

        val granted = LinkedHashMap<PermissionKey, Boolean>()
        for (spec in PermissionCatalog.forSdk(Build.VERSION.SDK_INT)) {
            granted[spec.key] = spec.grantedIfAny.any {
                ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        val engines = AiEngineStore(app)

        val installed = LinkedHashMap<String, Boolean>()
        for (profile in AppControlCatalog.apps) {
            installed[profile.id] = profile.alwaysAvailable || profile.packages.any { isInstalled(app, it) }
        }

        return LiveStatus(
            permissionGranted = granted,
            accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(app),
            accessibilityConnected = JarvisAccessibilityService.isRunning(),
            apiKeyPresent = safely { prefs.apiKey.isNotBlank() },
            groqSelected = safely { engines.useGroq },
            groqKeyPresent = safely { engines.hasGroqKey() },
            secureStorageAvailable = prefs.canStoreApiKeySecurely,
            lastApiCheck = store.lastApiCheck,
            profilePhotoSet = safely { prefs.profilePhotoUri.isNotBlank() },
            profileSaved = store.profileSaved,
            speechModelReady = VoskModelManager.isModelReady.value,
            ownerVoiceEnrolled = safely { prefs.hasOwnerVoiceProfile() },
            offlineModelInstalled = safely {
                OfflineModelDownloader.isModelValid(OfflineModelDownloader.modelFile(app))
            },
            installedApps = installed
        )
    }

    /** Whether the person installed the app outside the Play Store, where Android 13+ may restrict Accessibility. */
    fun likelyRestrictedSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackage
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
        return installer != "com.android.vending"
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Reads that touch encrypted storage can throw on a broken keystore: report "no" instead of crashing the tutorial. */
    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    }
}
