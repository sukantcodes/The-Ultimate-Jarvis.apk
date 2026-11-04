package com.jarvis.assistant.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import com.jarvis.assistant.auth.AuthManager

/**
 * For a feature that genuinely needs an account. Guests keep full use of everything else; only that one
 * feature asks them to sign in, and the user comes straight back to it afterwards.
 *
 * Usage (register during activity/fragment creation, call from the feature's entry point):
 * ```
 * private val requireAccount = registerRequireAccount { signedIn -> if (signedIn) openCloudBackup() }
 * fun onCloudBackupTapped() = if (AuthManager.isSignedIn) openCloudBackup() else requireAccount.launch(Unit)
 * ```
 * Being signed in never implies a Pro subscription; check entitlement separately.
 */
class RequireAccountContract : ActivityResultContract<Unit, Boolean>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        AuthActivity.intent(context, returnToCaller = true)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK && AuthManager.isSignedIn
}

fun ComponentActivity.registerRequireAccount(onResult: (signedIn: Boolean) -> Unit): ActivityResultLauncher<Unit> =
    registerForActivityResult(RequireAccountContract()) { onResult(it) }
