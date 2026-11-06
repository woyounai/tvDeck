package com.tvdeck.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsageConsentTests {
    @Test public void noticesFollowSystemRegionWithAMainlandFallback(){
        assertEquals(UsageConsent.MAINLAND,UsageConsent.fromSystemCode(null));
        assertEquals(UsageConsent.MAINLAND,UsageConsent.fromSystemCode(""));
        assertEquals(UsageConsent.MAINLAND,UsageConsent.fromSystemCode("cn"));
        for(String code:new String[]{"HK","MO","TW","US"})
            assertEquals(UsageConsent.INTERNATIONAL,UsageConsent.fromSystemCode(code));
    }
    @Test public void olderIncompleteAndInvalidRecordsRequireConsentAgain(){
        assertFalse(UsageConsent.valid("","mainland","en",1));
        assertFalse(UsageConsent.valid("older","mainland","en",1));
        assertFalse(UsageConsent.valid(UsageConsent.VERSION,"","en",1));
        assertFalse(UsageConsent.valid(UsageConsent.VERSION,"other","en",1));
        assertFalse(UsageConsent.valid(UsageConsent.VERSION,"mainland","",1));
        assertFalse(UsageConsent.valid(UsageConsent.VERSION,"mainland","en",0));
        assertTrue(UsageConsent.valid(UsageConsent.VERSION,"mainland","en",1));
        assertTrue(UsageConsent.valid(UsageConsent.VERSION,"international","zh-Hans",1));
    }
}
