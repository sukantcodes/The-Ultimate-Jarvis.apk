package com.jarvis.assistant.macro

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Selects the first real image thumbnail in a newest-first Android/WhatsApp media picker.
 * It deliberately refuses to guess when the current screen does not look like a photo picker.
 */
object MacroImagePicker {
    private data class Candidate(
        val clickTarget: AccessibilityNodeInfo,
        val bounds: Rect,
        val label: String
    )

    suspend fun selectLatestImage(): StepResult {
        val service = JarvisAccessibilityService.instance
            ?: return StepResult(false, "Accessibility service is unavailable.")
        val root = service.getActiveRootNode() ?: service.getAppRootNode()
            ?: return StepResult(false, "The current photo-picker screen is unavailable.")
        val packageName = root.packageName?.toString().orEmpty().lowercase(Locale.ROOT)
        val beforeText = ScreenTextReader.read(root).lowercase(Locale.ROOT)
        val knownPickerPackage = listOf(
            "documentsui", "providers.media", "mediaprovider", "google.android.apps.photos",
            "com.whatsapp", "com.whatsapp.w4b"
        ).any { packageName.contains(it) }
        val pickerWords = listOf("recent", "photos", "gallery", "select photos", "choose photo", "albums", "images")
        if (!knownPickerPackage && pickerWords.none { beforeText.contains(it) }) {
            return StepResult(false, "The foreground screen is not a verified photo picker ('$packageName'); no image was selected.")
        }

        val candidates = ArrayList<Candidate>()
        fun walk(
            node: AccessibilityNodeInfo,
            clickableAncestor: AccessibilityNodeInfo?,
            ancestorLabels: String,
            depth: Int
        ) {
            if (depth > 35 || candidates.size > 250) return
            val ownText = node.text?.toString().orEmpty()
            val ownDescription = node.contentDescription?.toString().orEmpty()
            val ownLabel = "$ownText $ownDescription".trim()
            val allLabels = "$ancestorLabels $ownLabel".lowercase(Locale.ROOT)
            val clickTarget = if (node.isClickable && node.isEnabled) node else clickableAncestor
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val className = node.className?.toString().orEmpty().lowercase(Locale.ROOT)
            val imageLike = className.contains("imageview") || className.contains("imagebutton") ||
                ownLabel.contains("photo", true) || ownLabel.contains("image", true) ||
                ownLabel.contains("picture", true)
            val cameraOrNavigation = listOf(
                "camera", "take photo", "take picture", "browse", "albums", "gallery button",
                "back", "close", "search", "settings", "select all"
            ).any { allLabels.contains(it) }
            if (imageLike && !cameraOrNavigation && clickTarget != null &&
                node.isVisibleToUser && bounds.width() >= 80 && bounds.height() >= 80 &&
                bounds.top >= 60 && bounds.bottom > bounds.top) {
                candidates.add(Candidate(clickTarget, bounds, ownLabel))
            }
            val nextAncestorLabels = if (ownLabel.isBlank()) ancestorLabels else "$ancestorLabels $ownLabel"
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, clickTarget, nextAncestorLabels, depth + 1)
            }
        }
        walk(root, null, "", 0)

        val candidate = candidates
            .distinctBy { it.bounds.top.toString() + ":" + it.bounds.left + ":" + it.bounds.right + ":" + it.bounds.bottom + ":" + it.label }
            .sortedWith(compareBy<Candidate>({ it.bounds.top }, { it.bounds.left }))
            .firstOrNull()
            ?: return StepResult(false, "No safe, visible image thumbnail was found in the photo picker.")

        val clicked = candidate.clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            return StepResult(false, "The newest visible image thumbnail could not be selected.")
        }

        delay(650L)
        val refreshed = service.getActiveRootNode() ?: service.getAppRootNode()
        val afterText = ScreenTextReader.read(refreshed).lowercase(Locale.ROOT)
        val selectionEvidence = afterText != beforeText ||
            listOf("selected", "next", "done", "send", "caption", "1 photo", "1 item").any { afterText.contains(it) } ||
            candidate.clickTarget.isSelected
        if (!selectionEvidence) {
            return StepResult(false, "Tapped the newest visible thumbnail, but the picker did not confirm selection; stopping before posting.")
        }
        return StepResult(true, "Selected the first visible image thumbnail in the newest-first picker and observed a selection-state change.")
    }
}
