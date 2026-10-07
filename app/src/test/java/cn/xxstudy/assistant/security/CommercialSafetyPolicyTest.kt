package cn.xxstudy.assistant.security

import org.junit.Assert.assertFalse
import org.junit.Test

class CommercialSafetyPolicyTest {
    @Test fun unscopedExternalApiAndAutomaticModelActionsFailClosed() {
        assertFalse(CommercialSafetyPolicy.externalApiAvailable)
        assertFalse(CommercialSafetyPolicy.automaticModelActionsAllowed)
    }
}
