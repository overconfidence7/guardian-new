package app.guardianmode

import app.guardianmode.protection.Capability
import app.guardianmode.ui.onboarding.ConsentPages
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** qa/antigravity/cases/02-setup-and-consent.md GM-CONS-006: the overlay step matches what Settings opens. */
class ConsentInstructionTest {

    @Test fun gm_cons_006_android11AndLaterSayToFindGuardianModeInTheList() {
        listOf(30, 33, 36).forEach { sdk ->
            val text = ConsentPages.instruction(Capability.OVERLAY, sdk)
            assertThat(text).contains("Find Guardian Mode in the list")
            assertThat(text).contains("\"Allow display over other apps\"")
        }
    }

    @Test fun gm_cons_006_android10GoesStraightToTheSwitch() {
        val text = ConsentPages.instruction(Capability.OVERLAY, 29)
        assertThat(text).doesNotContain("list")
        assertThat(text).contains("\"Allow display over other apps\"")
    }
}
