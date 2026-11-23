package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static com.pengin0503.itemmilestonelog.LookupPolicy.Decision.ALLOWED;
import static com.pengin0503.itemmilestonelog.LookupPolicy.Decision.DISABLED;
import static com.pengin0503.itemmilestonelog.LookupPolicy.Decision.HIDDEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Permission matrix for F6 lookup: allowLookup on/off x privileged/self/other x hidden/shown. */
class LookupPolicyTest {
    // decide(allowLookup, viewerPrivileged, viewerIsTarget, targetHidden)

    @Test
    void allowLookupOnOtherPlayerSeesShownButNotHiddenTargets() {
        assertEquals(ALLOWED, LookupPolicy.decide(true, false, false, false));
        assertEquals(HIDDEN, LookupPolicy.decide(true, false, false, true));
    }

    @Test
    void allowLookupOnPrivilegedSeesEveryone() {
        assertEquals(ALLOWED, LookupPolicy.decide(true, true, false, false));
        assertEquals(ALLOWED, LookupPolicy.decide(true, true, false, true));
    }

    @Test
    void allowLookupOnSelfAlwaysAllowed() {
        assertEquals(ALLOWED, LookupPolicy.decide(true, false, true, false));
        assertEquals(ALLOWED, LookupPolicy.decide(true, false, true, true));
        assertEquals(ALLOWED, LookupPolicy.decide(true, true, true, true));
    }

    @Test
    void allowLookupOffOtherPlayerIsDisabledRegardlessOfPrivacy() {
        assertEquals(DISABLED, LookupPolicy.decide(false, false, false, false));
        assertEquals(DISABLED, LookupPolicy.decide(false, false, false, true));
    }

    @Test
    void allowLookupOffPrivilegedStillSeesEveryone() {
        assertEquals(ALLOWED, LookupPolicy.decide(false, true, false, false));
        assertEquals(ALLOWED, LookupPolicy.decide(false, true, false, true));
    }

    @Test
    void allowLookupOffSelfAlwaysAllowed() {
        assertEquals(ALLOWED, LookupPolicy.decide(false, false, true, false));
        assertEquals(ALLOWED, LookupPolicy.decide(false, false, true, true));
        assertEquals(ALLOWED, LookupPolicy.decide(false, true, true, true));
    }

    @Test
    void canLookUpOthersGatesSuggestionsOnAllowLookupOrPrivilege() {
        assertTrue(LookupPolicy.canLookUpOthers(true, false));
        assertTrue(LookupPolicy.canLookUpOthers(true, true));
        assertTrue(LookupPolicy.canLookUpOthers(false, true));
        assertFalse(LookupPolicy.canLookUpOthers(false, false));
    }

    @Test
    void suggestionsMatchDecisionAndHideHiddenPlayersFromNonPrivilegedViewers() {
        // allowLookup on, regular viewer: shown players suggested, hidden ones excluded
        assertTrue(LookupPolicy.shouldSuggest(true, false, false, false));
        assertFalse(LookupPolicy.shouldSuggest(true, false, false, true));
        // privileged viewer sees hidden players in suggestions
        assertTrue(LookupPolicy.shouldSuggest(true, true, false, true));
        assertTrue(LookupPolicy.shouldSuggest(false, true, false, true));
        // allowLookup off, regular viewer: no other names (past participants are not listed)
        assertFalse(LookupPolicy.shouldSuggest(false, false, false, false));
        assertFalse(LookupPolicy.shouldSuggest(false, false, false, true));
        // own name is always suggestible
        assertTrue(LookupPolicy.shouldSuggest(false, false, true, true));
        assertTrue(LookupPolicy.shouldSuggest(true, false, true, true));
    }
}
