package com.jarvis.assistant.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permission policy for the optional, user-opened gallery browser. */
object GalleryPermissionManager {
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAnyMediaAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            return granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasFullImageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        else granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasFullVideoAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        else granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasSelectedVisualAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
