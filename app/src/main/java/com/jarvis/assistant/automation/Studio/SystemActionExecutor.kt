package com.jarvis.assistant.automation.studio

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

/**
 * Implements local controls where Android exposes a supported app-level API.
 * Restricted toggles deliberately open the relevant settings panel rather than
 * attempting hidden APIs or requesting privileged permissions.
 */
class SystemActionExecutor(
    context: Context,
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
) : StudioActionExecutor {
    private val appContext = context.applicationContext

    override suspend fun execute(
        action: AutomationAction,
        variables: Map<String, String>
    ): ActionExecutionResult {
        fun value(key: String) = (action.parameters[key] ?: variables[key] ?: "").trim()
        return try {
            when (action.type) {
                RuleActionType.OPEN_APP -> {
                    val packageName = value("packageName")
                    if (packageName.isBlank()) return ActionExecutionResult(false, "Choose an installed app first.")
                    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return ActionExecutionResult(false, "This app has no launchable activity or Android blocked it.")
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(launchIntent)
                    ActionExecutionResult(true, "Opening ${appContext.packageManager.getApplicationLabel(appContext.packageManager.getApplicationInfo(packageName, 0))}.", true)
                }
                RuleActionType.SET_FLASHLIGHT -> {
                    val enabled = value("enabled").lowercase() in setOf("true", "on", "yes", "1")
                    val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = manager.cameraIdList.firstOrNull {
                        manager.getCameraCharacteristics(it)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return ActionExecutionResult(false, "This device has no available flashlight.")
                    manager.setTorchMode(cameraId, enabled)
                    ActionExecutionResult(true, if (enabled) "Flashlight turned on." else "Flashlight turned off.", true)
                }
                RuleActionType.SET_VOLUME -> {
                    val level = value("level").removeSuffix("%").toIntOrNull()
                        ?: return ActionExecutionResult(false, "Volume level must be between 0 and 100.")
                    if (level !in 0..100) return ActionExecutionResult(false, "Volume level must be between 0 and 100.")
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (max * level / 100f).toInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    ActionExecutionResult(true, "Volume set to $level percent.", true)
                }
                RuleActionType.SET_BRIGHTNESS -> {
                    val level = value("level").removeSuffix("%").toIntOrNull()
                        ?: return ActionExecutionResult(false, "Brightness level must be between 0 and 100.")
                    if (level !in 0..100) return ActionExecutionResult(false, "Brightness level must be between 0 and 100.")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(appContext)) {
                        openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        return ActionExecutionResult(false, "Allow modify-system-settings permission, then retry.")
                    }
                    val brightness = (level * 255 / 100).coerceIn(0, 255)
                    val changed = Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                    if (changed) ActionExecutionResult(true, "Brightness set to $level percent.", true)
                    else ActionExecutionResult(false, "Could not change brightness.")
                }
                RuleActionType.SET_RINGER_MODE -> {
                    val mode = value("mode").lowercase()
                    val ringer = when (mode) {
                        "silent" -> AudioManager.RINGER_MODE_SILENT
                        "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
                        "normal", "ring" -> AudioManager.RINGER_MODE_NORMAL
                        else -> return ActionExecutionResult(false, "Ringer mode must be normal, vibrate, or silent.")
                    }
                    if (!audioManager.isVolumeFixed) {
                        audioManager.ringerMode = ringer
                        ActionExecutionResult(true, "Ringer mode changed to $mode.", true)
                    } else ActionExecutionResult(false, "Ringer mode is controlled by the device.")
                }
                RuleActionType.OPEN_WIFI_SETTINGS -> {
                    openSettings(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS)
                    ActionExecutionResult(true, "Opening internet settings.", true)
                }
                RuleActionType.OPEN_BLUETOOTH_SETTINGS -> {
                    openSettings(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_BLUETOOTH_SETTINGS)
                    ActionExecutionResult(true, "Opening connectivity settings.", true)
                }
                RuleActionType.MEDIA_PLAY,
                RuleActionType.MEDIA_PAUSE,
                RuleActionType.MEDIA_NEXT,
                RuleActionType.MEDIA_PREVIOUS -> ActionExecutionResult(
                    false,
                    "Media control needs an active media-session integration; this executor intentionally does not send synthetic key events."
                )
                else -> ActionExecutionResult(false, "Action ${action.type} is not supported by this executor.")
            }
        } catch (e: SecurityException) {
            ActionExecutionResult(false, "Android denied this action. Check app permissions and device policy.")
        } catch (e: Exception) {
            ActionExecutionResult(false, "Could not complete action: ${e.message ?: "unknown error"}")
        }
    }

    private fun openSettings(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }
}
