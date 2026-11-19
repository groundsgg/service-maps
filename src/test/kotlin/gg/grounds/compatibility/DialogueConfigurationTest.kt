package gg.grounds.compatibility

import gg.grounds.derive.DeriveCompatibility
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DialogueConfigurationTest {
    @Test fun requiredGateNeedsEnabledRequiredSchema2() {
        listOf(Triple(false,false,1),Triple(true,false,2),Triple(true,true,1)).forEach { (enabled,required,schema) ->
            assertThrows(IllegalArgumentException::class.java) { DeriveCompatibility(enabled,required,true,schema) }
        }
        assertDoesNotThrow { DeriveCompatibility(true,true,true,2) }
        assertDoesNotThrow { DeriveCompatibility(false,false,false,1) }
    }
}
