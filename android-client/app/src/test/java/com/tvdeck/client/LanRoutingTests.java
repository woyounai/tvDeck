package com.tvdeck.client;
import org.junit.Test;
import static org.junit.Assert.*;

public class LanRoutingTests {
    @Test public void onlyOnLinkPrivateAddressesUseLan(){
        assertTrue(LanRouting.matches(LanRouting.ipv4("192.168.1.20"),LanRouting.ipv4("192.168.1.70"),24));
        assertFalse(LanRouting.matches(LanRouting.ipv4("192.168.2.20"),LanRouting.ipv4("192.168.1.70"),24));
        assertTrue(LanRouting.matches(LanRouting.ipv4("10.1.8.20"),LanRouting.ipv4("10.1.1.70"),16));
        assertFalse(LanRouting.matches(LanRouting.ipv4("8.8.8.8"),LanRouting.ipv4("8.8.8.1"),24));
        assertFalse(LanRouting.matches(LanRouting.ipv4("127.0.0.1"),LanRouting.ipv4("127.0.0.1"),8));
        assertFalse(LanRouting.matches(LanRouting.ipv4("192.168.1.21"),LanRouting.ipv4("192.168.1.20"),32));
        assertFalse(LanRouting.matches(LanRouting.ipv4("192.168.1.20"),LanRouting.ipv4("192.168.1.70"),0));
        for(String host:new String[]{"tv.local","192.168.01.20","256.1.1.1","192.168.1.1.invalid","::1"})assertNull(LanRouting.ipv4(host));
    }
}
