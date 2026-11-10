package com.jarvis.assistant.tools.github

import com.jarvis.assistant.tools.Tool

/** Every GitHub and skill tool, ready to register with the shared ToolRegistry. */
object GitHubTools {
    fun all(client: GitHubClient, registry: InstalledSkillRegistry, tokenSource: GitHubTokenSource = client.tokenSource): List<Tool> {
        val installer = SkillInstaller(client, registry)
        return listOf(
            GhStatusTool(client, tokenSource), GhListReposTool(client), GhSearchReposTool(client), GhRepoInfoTool(client), GhListFilesTool(client),
            GhReadFileTool(client), GhSearchCodeTool(client), GhListCommitsTool(client), GhListBranchesTool(client), GhListPrsTool(client),
            GhListIssuesTool(client), GhPrDiffTool(client), GhCompareTool(client), GhActionsRunsTool(client), GhReleaseAssetsTool(client),
            GhCreateBranchTool(client), GhPutFileTool(client), GhDeleteFileTool(client), GhCreatePrTool(client),
            GhCloneTool(client), GhDownloadRepoTool(client), GhDownloadAssetTool(client),
            SkillInspectTool(client, installer), SkillInstallTool(client, installer), SkillListTool(registry, client), SkillRemoveTool(registry, client)
        )
    }
}
