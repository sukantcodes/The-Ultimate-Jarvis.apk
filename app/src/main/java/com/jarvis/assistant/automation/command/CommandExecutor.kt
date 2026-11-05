package com.jarvis.assistant.automation.command

import android.util.Log
import com.jarvis.assistant.automation.action.ActionAttempt
import com.jarvis.assistant.automation.action.ActionRouter
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager,
    private val actionRouter: ActionRouter? = null
) {
    companion object { private const val TAG = "CommandExecutor" }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        val searchQuery = when (command) {
            is JarvisCommand.SearchWeb -> command.query
            is JarvisCommand.SearchYouTube -> command.query
            is JarvisCommand.SearchSocialApp -> command.query
            is JarvisCommand.SearchInApp -> command.query
            else -> null
        }
        if (searchQuery != null && !phoneManager.isSafeSearchQuery(searchQuery)) {
            return@withContext "I can't search for content that could enable harm or bypass safety protections."
        }
        Log.i(TAG, "Executing command: $command")
        val router = actionRouter
        if (router == null || !router.isEnabled) return@withContext executeExistingAutomation(command)

        router.execute(
            taskName = command.javaClass.simpleName,
            directSystemApi = nativeAttempt(command),
            explicitIntent = intentAttempt(command),
            legacyAutomation = { ActionAttempt(true, executeExistingAutomation(command)) }
        ).message
    }

    private fun nativeAttempt(command: JarvisCommand): (suspend () -> ActionAttempt?)? = when (command) {
        is JarvisCommand.Flash -> suspend {
            val result = phoneManager.setFlashlight(command.enabled)
            ActionAttempt(!result.startsWith("Unable", true) && !result.startsWith("No flashlight", true), result)
        }
        is JarvisCommand.Brightness -> suspend {
            val result = if (command.isRelative) phoneManager.adjustBrightness(command.delta > 0)
                else phoneManager.setBrightnessPercent(command.level)
            ActionAttempt(!result.startsWith("Failed", true), result)
        }
        is JarvisCommand.Volume -> suspend {
            val result = if (command.isRelative) phoneManager.adjustVolume(command.delta > 0)
                else phoneManager.setVolumePercent(command.level)
            ActionAttempt(!result.startsWith("Could not", true) && !result.startsWith("Failed", true), result)
        }
        else -> null
    }

    private fun intentAttempt(command: JarvisCommand): (suspend () -> ActionAttempt?)? = when (command) {
        is JarvisCommand.OpenApp -> suspend {
            val result = phoneManager.openApp(command.packageName)
            ActionAttempt(!result.startsWith("Failed", true) && !result.startsWith("Could not", true), result)
        }
        is JarvisCommand.OpenWebUrls -> suspend {
            val result = phoneManager.openMultipleUrls(command.urls)
            ActionAttempt(!result.startsWith("Failed", true) && !result.startsWith("Could not", true), result)
        }
        else -> null
    }

    /** Existing command behaviour remains the final compatibility fallback. */
    private suspend fun executeExistingAutomation(command: JarvisCommand): String = when (command) {
        is JarvisCommand.PlayYouTube -> {
            val song = memoryManager.resolveSongQuery(command.query)
            memoryManager.trackSongPlay(song)
            youtubeManager.playVideo(song)
        }
        is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
        is JarvisCommand.SearchSocialApp -> phoneManager.searchSocialApp(command.app, command.query)
        is JarvisCommand.SearchInApp -> phoneManager.searchInApp(command.app, command.query)
        is JarvisCommand.SocialAppAction -> phoneManager.socialAppAction(command.app, command.action, command.target, command.message)
        is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
        is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
        is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
        is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
        is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
        is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()
        is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
        is JarvisCommand.Brightness -> if (command.isRelative) phoneManager.adjustBrightness(command.delta > 0) else phoneManager.setBrightnessPercent(command.level)
        is JarvisCommand.Volume -> if (command.isRelative) phoneManager.adjustVolume(command.delta > 0) else phoneManager.setVolumePercent(command.level)
        is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
        is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
        is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
        is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)
        is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
        is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
        is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
        is JarvisCommand.ConfirmAction -> if (command.confirmed) whatsappAssistant.confirmAndSend() else whatsappAssistant.cancelDraft()
        is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)
        is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
        is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
        is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
        is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
        is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
        is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
        is JarvisCommand.GoToBackground -> "Going to background."
    }
}
