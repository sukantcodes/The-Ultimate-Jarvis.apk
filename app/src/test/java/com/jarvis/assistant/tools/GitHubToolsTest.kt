package com.jarvis.assistant.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolsTest {
    @Test fun repositoryNamesRejectPathInjection() {
        assertTrue(GitHubTools.validRepositoryPart("owner-name"))
        assertTrue(GitHubTools.validRepositoryPart("repo.name_1"))
        assertFalse(GitHubTools.validRepositoryPart("../other"))
        assertFalse(GitHubTools.validRepositoryPart("."))
        assertFalse(GitHubTools.validRepositoryPart(""))
    }

    @Test fun repositoryPathsStayRelativeAndSegmented() {
        assertTrue(GitHubTools.validRepoPath("src/main/Example.kt"))
        assertTrue(GitHubTools.validRepoPath("README.md"))
        assertFalse(GitHubTools.validRepoPath("../secrets.txt"))
        assertFalse(GitHubTools.validRepoPath("src/../../secrets.txt"))
        assertFalse(GitHubTools.validRepoPath("/etc/passwd"))
        assertFalse(GitHubTools.validRepoPath("src\\\\file.kt"))
        assertFalse(GitHubTools.validRepoPath("src//file.kt"))
    }
}
