package com.jarvis.assistant.macro

import org.json.JSONObject
import java.util.Locale

/** The macros that ship with Jarvis. Edit [BuiltinMacros.JSON] to add or change one. */
object MacroLibrary {
    val macros: List<Macro> by lazy { parse(BuiltinMacros.JSON) }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Finds a macro by its name or one of its aliases, tolerating small differences in wording. */
    fun find(query: String): Macro? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        macros.firstOrNull { macro ->
            normalize(macro.name) == q || macro.aliases.any { normalize(it) == q }
        }?.let { return it }
        return macros.firstOrNull { macro ->
            val names = listOf(macro.name) + macro.aliases
            names.any { raw ->
                val n = normalize(raw)
                n.isNotEmpty() && (q.contains(n) || n.contains(q))
            }
        }
    }

    /** One line per macro, used in the tool description so the assistant knows what is available. */
    fun describe(): String = macros.joinToString("; ") { it.name + " (" + it.description + ")" }

    private fun parse(json: String): List<Macro> {
        val out = ArrayList<Macro>()
        try {
            val array = JSONObject(json).getJSONArray("macros")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val aliases = ArrayList<String>()
                obj.optJSONArray("aliases")?.let { list ->
                    for (j in 0 until list.length()) aliases.add(list.optString(j, ""))
                }
                val steps = ArrayList<MacroStep>()
                val stepArray = obj.getJSONArray("steps")
                for (j in 0 until stepArray.length()) {
                    val stepObj = stepArray.getJSONObject(j)
                    steps.add(MacroStep(stepObj.getString("tool"), stepObj.optJSONObject("args") ?: JSONObject()))
                }
                out.add(
                    Macro(
                        name = obj.getString("name"),
                        aliases = aliases,
                        description = obj.optString("description", ""),
                        param = obj.optString("param", ""),
                        steps = steps
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MacroLibrary", "Could not read the built-in macros: ${e.message}")
        }
        return out
    }
}

internal object BuiltinMacros {
    const val JSON = """
{
  "version": 1,
  "macros": [
    {
      "name": "chatgpt image",
      "aliases": [
        "chatgpt image generate",
        "chatgpt se image banao",
        "image banao chatgpt"
      ],
      "description": "Creates an image in the ChatGPT app and saves it to the gallery; input = what the image should show",
      "param": "image_prompt",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "ChatGPT",
            "package_hints": [
              "openai",
              "chatgpt"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "New chat"
            ],
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "Create an image: {image_prompt}",
            "focus": [
              "Message ChatGPT",
              "Ask anything",
              "Message",
              "Ask ChatGPT"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send Message",
              "Send message",
              "Send",
              "Generate",
              "Create"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": [
              "Download",
              "Save image",
              "Image generated",
              "Generated image",
              "Share"
            ],
            "mode": "appear",
            "timeout_seconds": 300
          }
        },
        {
          "tool": "wait_for_stable_screen",
          "args": {
            "min_seconds": 6,
            "stable_seconds": 3,
            "timeout_seconds": 120
          }
        },
        {
          "tool": "download_generated_image",
          "args": {}
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        }
      ]
    },
    {
      "name": "gemini image",
      "aliases": [
        "gemini image generate",
        "gemini se image banao",
        "image banao gemini"
      ],
      "description": "Creates an image in the Gemini app with its Images tool and saves it to the gallery; input = what the image should show",
      "param": "image_prompt",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "Gemini",
            "package_hints": [
              "bard",
              "gemini"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "New chat"
            ],
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Add attachments",
              "Tools"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 1
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Images",
              "Create images",
              "Generate images",
              "Image generation"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "{image_prompt}",
            "focus": [
              "Ask Gemini",
              "Enter a prompt",
              "Message Gemini",
              "Ask anything"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send",
              "Send message",
              "Generate",
              "Create"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": [
              "Download",
              "Save image",
              "Save",
              "Image generated",
              "Generated image"
            ],
            "mode": "appear",
            "timeout_seconds": 300
          }
        },
        {
          "tool": "wait_for_stable_screen",
          "args": {
            "min_seconds": 6,
            "stable_seconds": 3,
            "timeout_seconds": 120
          }
        },
        {
          "tool": "download_generated_image",
          "args": {}
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        }
      ]
    },
    {
      "name": "gemini video",
      "aliases": [
        "gemini video generate",
        "gemini se video banao",
        "video banao gemini"
      ],
      "description": "creates a video in the Gemini app with its Videos tool and saves it (takes several minutes); input = what the video should show",
      "param": "video_prompt",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "Gemini",
            "package_hints": [
              "bard",
              "gemini"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "New chat",
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "Add attachments"
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "Videos"
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "{video_prompt}",
            "focus": [
              "Ask Gemini",
              "Enter a prompt"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send",
              "Send message"
            ]
          }
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": "Your video is ready",
            "mode": "appear",
            "timeout_seconds": 600
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "More options"
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "Save video"
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 4
          }
        }
      ]
    },
    {
      "name": "chatgpt ask",
      "aliases": [
        "chatgpt se poochho",
        "chatgpt se pucho",
        "ask chatgpt"
      ],
      "description": "asks the ChatGPT app a question and reads back its answer; input = the question",
      "param": "question",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "ChatGPT",
            "package_hints": [
              "openai",
              "chatgpt"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "New chat",
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "{question}",
            "focus": [
              "Message",
              "Ask anything"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send Message",
              "Send message",
              "Send"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "wait_for_stable_screen",
          "args": {
            "min_seconds": 6,
            "stable_seconds": 4,
            "timeout_seconds": 90
          }
        },
        {
          "tool": "read_screen_text",
          "args": {}
        }
      ]
    },
    {
      "name": "gemini ask",
      "aliases": [
        "gemini se poochho",
        "gemini se pucho",
        "ask gemini"
      ],
      "description": "asks the Gemini app a question and reads back its answer; input = the question",
      "param": "question",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "Gemini",
            "package_hints": [
              "bard",
              "gemini"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": "New chat",
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "{question}",
            "focus": [
              "Ask Gemini",
              "Enter a prompt"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send",
              "Send message"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "wait_for_stable_screen",
          "args": {
            "min_seconds": 6,
            "stable_seconds": 4,
            "timeout_seconds": 90
          }
        },
        {
          "tool": "read_screen_text",
          "args": {}
        }
      ]
    },
    {
      "name": "chatgpt image WhatsApp status",
      "aliases": [
        "chatgpt image whatsapp status",
        "chatgpt se image bana ke status lagao",
        "create image with chatgpt and post to whatsapp status"
      ],
      "description": "Creates an image in ChatGPT, saves it, selects the newest image in WhatsApp's status picker, and posts it to WhatsApp Status; input = what the image should show",
      "param": "image_prompt",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "ChatGPT",
            "package_hints": [
              "openai",
              "chatgpt"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "New chat"
            ],
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "Create an image: {image_prompt}",
            "focus": [
              "Message ChatGPT",
              "Ask anything",
              "Message",
              "Ask ChatGPT"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send Message",
              "Send message",
              "Send",
              "Generate",
              "Create"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": [
              "Download",
              "Save image",
              "Image generated",
              "Generated image",
              "Share"
            ],
            "mode": "appear",
            "timeout_seconds": 300
          }
        },
        {
          "tool": "wait_for_stable_screen",
          "args": {
            "min_seconds": 6,
            "stable_seconds": 3,
            "timeout_seconds": 120
          }
        },
        {
          "tool": "download_generated_image",
          "args": {}
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "WhatsApp",
            "package_hints": [
              "whatsapp"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Updates",
              "Status"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Add status",
              "My status",
              "Add to my status",
              "My Status"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Gallery",
              "Photos",
              "Choose from gallery",
              "Select from gallery",
              "Choose photo"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "select_latest_image",
          "args": {}
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 1
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Next",
              "Continue"
            ],
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 1
          }
        },
        {
          "tool": "publish_whatsapp_status",
          "args": {}
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": [
              "Status updated",
              "Just now",
              "Sent"
            ],
            "mode": "appear",
            "timeout_seconds": 90
          }
        }
      ]
    },
    {
      "name": "gemini image WhatsApp status",
      "aliases": [
        "gemini image whatsapp status",
        "gemini se image bana ke status lagao",
        "create image with gemini and post to whatsapp status"
      ],
      "description": "Creates an image in Gemini, saves it, selects the newest image in WhatsApp's status picker, and posts it to WhatsApp Status; input = what the image should show",
      "param": "image_prompt",
      "steps": [
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "Gemini",
            "package_hints": [
              "bard",
              "gemini"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "New chat"
            ],
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Add attachments",
              "Tools"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 1
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Images",
              "Create images",
              "Generate images",
              "Image generation"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "type_text",
          "args": {
            "text": "{image_prompt}",
            "focus": [
              "Ask Gemini",
              "Enter a prompt",
              "Message Gemini",
              "Ask anything"
            ]
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Send",
              "Send message",
              "Generate",
              "Create"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": [
              "Download",
              "Save image",
              "Save",
              "Image generated",
              "Generated image"
            ],
            "mode": "appear",
            "timeout_seconds": 300
          }
        },
        {
          "tool": "wait_for_stable_screen",
          "args": {
            "min_seconds": 6,
            "stable_seconds": 3,
            "timeout_seconds": 120
          }
        },
        {
          "tool": "download_generated_image",
          "args": {}
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 3
          }
        },
        {
          "tool": "open_app_by_name",
          "args": {
            "name": "WhatsApp",
            "package_hints": [
              "whatsapp"
            ]
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Updates",
              "Status"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Add status",
              "My status",
              "Add to my status",
              "My Status"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Gallery",
              "Photos",
              "Choose from gallery",
              "Select from gallery",
              "Choose photo"
            ],
            "retries": 3
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 2
          }
        },
        {
          "tool": "select_latest_image",
          "args": {}
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 1
          }
        },
        {
          "tool": "tap_text",
          "args": {
            "text": [
              "Next",
              "Continue"
            ],
            "_optional": true,
            "retries": 1
          }
        },
        {
          "tool": "wait",
          "args": {
            "seconds": 1
          }
        },
        {
          "tool": "publish_whatsapp_status",
          "args": {}
        },
        {
          "tool": "wait_for_screen_text",
          "args": {
            "text": [
              "Status updated",
              "Just now",
              "Sent"
            ],
            "mode": "appear",
            "timeout_seconds": 90
          }
        }
      ]
    }
  ]
}
"""
}
