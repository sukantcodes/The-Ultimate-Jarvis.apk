package com.jarvis.assistant.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryPolicyTest {
    @Test fun a_single_or_double_failure_never_replaces_saved_keys() {
        assertFalse(VaultRecoveryPolicy.shouldReplaceStore(1))
        assertFalse(VaultRecoveryPolicy.shouldReplaceStore(2))
    }

    @Test fun repeated_failures_allow_a_fresh_store() {
        assertTrue(VaultRecoveryPolicy.shouldReplaceStore(VaultRecoveryPolicy.FAILURES_BEFORE_REPLACE))
        assertTrue(VaultRecoveryPolicy.shouldReplaceStore(VaultRecoveryPolicy.FAILURES_BEFORE_REPLACE + 4))
    }

    @Test fun quick_retries_count_as_one_bad_moment() {
        assertTrue(VaultRecoveryPolicy.countsAsNewFailure(0L, 1_000L))
        assertFalse(VaultRecoveryPolicy.countsAsNewFailure(10_000L, 12_000L))
        assertTrue(VaultRecoveryPolicy.countsAsNewFailure(10_000L, 10_000L + VaultRecoveryPolicy.FAILURE_SPACING_MS))
    }
}
