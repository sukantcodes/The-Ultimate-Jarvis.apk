# JARVIS coding-agent architecture

This is a clean-room integration into JARVIS, not a binary clone of Clozed Code.

## Ownership

| Layer | Responsibility | Main files |
|---|---|---|
| UI | Coding-agent dialog, event rendering and user actions | ui/chat/ClozedCodingAgentDialog.kt, ui/chat/ClozedCodingAgentService.kt |
| Orchestration | Provider selection for CODING, agent loop, session history, tools, prompts, undo and subagents | ui/chat/ClozedCodingAgentEngine.kt |
| Provider protocol | OpenAI-compatible and Anthropic request/response dialects, SSE parsing, auth and HTTP status mapping | provider/ClozedAgentProtocol.kt |
| Network transport | Redirect-safe HTTP, streaming, cancellation and timeouts | provider/HttpTransport.kt, provider/OkHttpTransport.kt |
| Tool runtime | Permission policy, audit/redaction, workspace-scoped file operations, shell and MCP tools | tools/AgentToolRuntime.kt, tools/ToolExecutor.kt, tools/CodingShellTool.kt, tools/McpManager.kt |
| Skill catalog | Existing JARVIS flat skills plus nested Clozed coding-agent skills | skills/SkillStore.kt, skills/SkillParser.kt |

**Important boundary:** provider/ProviderChatEngine.kt is the regular ChatBox custom-provider conversation engine. Coding-agent requests use ClozedCodingAgentEngine and ClozedLlm in provider/ClozedAgentProtocol.kt. Do not put coding-agent HTTP back in ui/chat/, and do not create a second socket implementation: OkHttpTransport is the only provider network implementation.

## Coding-agent route prerequisite

A coding turn resolves WorkCategory.CODING, independently of the normal chat route. Before testing, configure a provider and model under **AI & models → Work Routing → Coding**. The coding engine accepts OpenAI-compatible and Anthropic endpoints; Gemini is intentionally refused by this coding-agent protocol.

## Wire invariants covered by tests

- ClozedAgentProtocolTest.anthropic_consecutive_tool_results_are_grouped_into_one_user_message
- ClozedAgentProtocolTest.openai_blank_assistant_tool_call_content_is_json_null
- ClozedAgentProtocolTest.openai_fragmented_tool_arguments_are_appended_by_call_index
- ClozedAgentProtocolTest.malformed_anthropic_tool_arguments_fail_instead_of_becoming_empty_objects

The engine carries tool success as the Boolean member of the returned Pair<String, Boolean>; the parent loop forwards that flag to the tool-end event rather than parsing the result text.

## UI rendering ownership

The normal JARVIS chat transcript is rendered by JarvisChatAdapter, which binds ItemNairaChatMessageBinding and uses the shared Markdown renderer. NairaChatAdapter is only a Kotlin typealias to JarvisChatAdapter, not a separate stub. ChatAdapter is a legacy adapter. The separate coding-agent dialog renders its own streamed transcript and tool blocks directly; it does not use these RecyclerView adapters. Changes to MarkdownMessageRenderer must still be regression-tested against ordinary JARVIS chat.

## Subagents

The task tool runs a bounded loop of up to 12 model/tool steps. Explore subagents receive a read-only allowlist; general subagents cannot spawn nested tasks or ask the user questions. The parent consumes the typed success Boolean returned by the subagent dispatch.

## Build stack

The host app uses Kotlin DSL, Kotlin 2.0, JDK 17, XML/viewBinding, Gson, OkHttp, Room and WorkManager. It does not use Compose or kotlinx-serialization. Release builds enable R8 and resource shrinking; the coding engine, service and Gson-backed session models have explicit keep rules in app/proguard-rules.pro.

## Verification status

Unit tests and CI builds are not device acceptance. Record the tested commit SHA, build variant, Android device/version and observed output separately. Session transcript restoration does not prove that a running turn survives process death.
