package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.tools.McpOAuth
import kotlinx.coroutines.launch

class McpOAuthCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback()
    }

    private fun handleCallback() {
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val result = runCatching { McpOAuth.complete(this@McpOAuthCallbackActivity, uri) }
            Toast.makeText(
                this@McpOAuthCallbackActivity,
                if (result.isSuccess) "MCP server login completed." else result.exceptionOrNull()?.message ?: "MCP login failed.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
}
