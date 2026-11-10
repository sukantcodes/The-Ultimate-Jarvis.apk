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
    @Test fun branchNamesRejectInvalidGitRefs() {
        assertTrue(GitHubWriteTools.validBranchName("feature/chatbox-agent"))
        assertFalse(GitHubWriteTools.validBranchName("../main"))
        assertFalse(GitHubWriteTools.validBranchName("feature//bad"))
        assertFalse(GitHubWriteTools.validBranchName("main.lock"))
        assertFalse(GitHubWriteTools.validBranchName("feature name"))
    }

    @Test fun githubWritesRejectRecognizableCredentialMaterial() {
        assertTrue(GitHubWriteTools.containsCredentialMaterial("ghp_abcdefghijklmnopqrstuvwxyz1234567890".toByteArray()))
        assertTrue(GitHubWriteTools.containsCredentialMaterial("github_pat_abcdefghijklmnopqrstuvwxyz1234567890".toByteArray()))
        assertTrue(GitHubWriteTools.containsCredentialMaterial("-----BEGIN RSA PRIVATE KEY-----".toByteArray()))
        assertTrue(GitHubWriteTools.containsCredentialMaterial("AKIA1234567890ABCDEF".toByteArray()))
        assertFalse(GitHubWriteTools.containsCredentialMaterial("fun main() = println(\"hello\")".toByteArray()))
    }

    @Test fun repositoryImportSkipsCredentialAndPrivateKeyFiles() {
        assertTrue(GitHubTools.isSensitiveRepositoryPath(".env"))
        assertTrue(GitHubTools.isSensitiveRepositoryPath("config/.env.production"))
        assertTrue(GitHubTools.isSensitiveRepositoryPath("keys/release.jks"))
        assertTrue(GitHubTools.isSensitiveRepositoryPath("secrets/service-account.json"))
        assertFalse(GitHubTools.isSensitiveRepositoryPath("app/src/MainActivity.kt"))
        assertFalse(GitHubTools.isSensitiveRepositoryPath("README.md"))
    }

    @Test fun githubBatchCommitValidationAcceptsSafeUtf8Files() {
        val files = listOf(
            GitHubWriteTools.CommitFileInput("src/Main.kt", "app/src/Main.kt", "fun main() = Unit\n".toByteArray()),
            GitHubWriteTools.CommitFileInput("README.md", "README.md", "# Project\n".toByteArray())
        )
        assertTrue(GitHubWriteTools.validateCommitBatch(files) == null)
    }

    @Test fun githubBatchCommitValidationRejectsDuplicateAndFileFolderConflicts() {
        val duplicate = listOf(
            GitHubWriteTools.CommitFileInput("a.txt", "src/a.txt", byteArrayOf(1)),
            GitHubWriteTools.CommitFileInput("b.txt", "src/a.txt", byteArrayOf(2))
        )
        assertTrue(GitHubWriteTools.validateCommitBatch(duplicate)?.contains("Duplicate") == true)

        val conflict = listOf(
            GitHubWriteTools.CommitFileInput("a.txt", "src", byteArrayOf(1)),
            GitHubWriteTools.CommitFileInput("b.txt", "src/file.txt", byteArrayOf(2))
        )
        assertTrue(GitHubWriteTools.validateCommitBatch(conflict)?.contains("folder") == true)
    }

    @Test fun githubBatchCommitValidationRejectsSecretsBinaryAndInvalidUtf8() {
        val secret = listOf(
            GitHubWriteTools.CommitFileInput("config.txt", "config.txt",
                "token=ghp_abcdefghijklmnopqrstuvwxyz1234567890".toByteArray())
        )
        assertTrue(GitHubWriteTools.validateCommitBatch(secret)?.contains("credential") == true)

        val binary = listOf(GitHubWriteTools.CommitFileInput("image.dat", "image.dat", byteArrayOf(1, 0, 2)))
        assertTrue(GitHubWriteTools.validateCommitBatch(binary)?.contains("Binary") == true)

        val invalidUtf8 = listOf(GitHubWriteTools.CommitFileInput("bad.txt", "bad.txt", byteArrayOf(0xC3.toByte(), 0x28)))
        assertTrue(GitHubWriteTools.validateCommitBatch(invalidUtf8)?.contains("UTF-8") == true)

        val sensitive = listOf(GitHubWriteTools.CommitFileInput(".env", ".env", "SAFE=1".toByteArray()))
        assertTrue(GitHubWriteTools.validateCommitBatch(sensitive)?.contains("private-key") == true)
    }

    @Test fun githubBatchCommitValidationEnforcesPerFileAndAggregateLimits() {
        val oversizedFile = listOf(
            GitHubWriteTools.CommitFileInput("large.txt", "large.txt", ByteArray(1_000_001) { 65 })
        )
        assertTrue(GitHubWriteTools.validateCommitBatch(oversizedFile)?.contains("1 MB") == true)

        val tooManyBytes = (0 until 6).map { index ->
            GitHubWriteTools.CommitFileInput("file" + index + ".txt", "dir/file" + index + ".txt", ByteArray(900_000) { 65 })
        }
        assertTrue(GitHubWriteTools.validateCommitBatch(tooManyBytes)?.contains("5 MB") == true)
    }

    @Test fun githubBatchCommitValidationCapsFileCount() {
        val tooMany = (0 until 26).map { index ->
            GitHubWriteTools.CommitFileInput("file" + index + ".txt", "file" + index + ".txt", byteArrayOf(65))
        }
        assertTrue(GitHubWriteTools.validateCommitBatch(tooMany)?.contains("25 files") == true)
    }

    @Test fun githubFileImportsRejectInvalidUtf8() {
        assertTrue(GitHubTools.isUtf8Text("normal UTF-8 text".toByteArray()))
        assertFalse(GitHubTools.isUtf8Text(byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(GitHubTools.isUtf8Text(byteArrayOf(65, 0, 66)))
    }

    @Test fun repositoryImportRespectsTheTotalWorkspaceQuota() {
        val fiftyMb = 50L * 1024L * 1024L
        val twentyMb = 20L * 1024L * 1024L
        assertTrue(GitHubTools.fitsWorkspaceBudget(10L * 1024L * 1024L, 20L * 1024L * 1024L))
        assertFalse(GitHubTools.fitsWorkspaceBudget(40L * 1024L * 1024L, 20L * 1024L * 1024L))
        assertFalse(GitHubTools.fitsWorkspaceBudget(fiftyMb, 1L))
        assertFalse(GitHubTools.fitsWorkspaceBudget(0L, twentyMb + 1L))
    }

    @Test fun pullRequestPreflightRequiresDistinctBranchesAndNewCommits() {
        assertTrue(GitHubWriteTools.pullRequestPreflightError("feature/chatbox", "main", 2) == null)
        assertTrue(GitHubWriteTools.pullRequestPreflightError("main", "main", 2)?.contains("different") == true)
        assertTrue(GitHubWriteTools.pullRequestPreflightError("../bad", "main", 2) != null)
        assertTrue(GitHubWriteTools.pullRequestPreflightError("feature/chatbox", "main", 0)?.contains("no commits ahead") == true)
        assertTrue(GitHubWriteTools.pullRequestPreflightError("feature/chatbox", "main", -1)?.contains("no commits ahead") == true)
    }

    @Test fun branchComparisonRejectsInvalidRepositoryAndBranchInputs() {
        assertTrue(GitHubTools.validComparisonRequest("sukantcodes", "Jarvis", "main", "feature/chatbox"))
        assertFalse(GitHubTools.validComparisonRequest("../owner", "Jarvis", "main", "feature/chatbox"))
        assertFalse(GitHubTools.validComparisonRequest("owner", "../repo", "main", "feature/chatbox"))
        assertFalse(GitHubTools.validComparisonRequest("owner", "repo", "main", "main"))
        assertFalse(GitHubTools.validComparisonRequest("owner", "repo", "../main", "feature/chatbox"))
    }
}
