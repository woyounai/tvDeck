package com.tvdeck.client;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class AerialInstallTests {
    @Test public void setupPreservesExistingListenersAndContinuesPastUnsupportedGrants()throws Exception{
        String existing="com.vendor/.ExceptionListener$Inner",merged=AerialIntegration.mergedListeners(existing);assertEquals(existing+":"+AerialIntegration.LISTENER,merged);assertEquals(merged,AerialIntegration.mergedListeners(merged));assertEquals(AerialIntegration.LISTENER,AerialIntegration.mergedListeners("null"));
        try{AerialIntegration.mergedListeners("Error: denied");fail();}catch(IOException expected){}
        java.util.List<String> calls=new java.util.ArrayList<>();
        AerialIntegration.SetupResult result=AerialIntegration.configure(command->{calls.add(command);String body=command.startsWith("settings get")?existing:command.startsWith("appops set")?"Unknown operation string: unsupported":command.startsWith("am start")?"Status: ok\n":"";return body+"\n__AERIAL_SETUP_EXIT__0\n";},"guard",21);
        assertTrue(result.launched);assertTrue(result.warnings>0);assertTrue(result.report.contains("Unknown operation"));assertTrue(calls.stream().anyMatch(command->command.startsWith("settings put secure enabled_notification_listeners")&&command.contains(existing)&&command.contains(AerialIntegration.LISTENER)));
        assertTrue(calls.get(calls.size()-1).startsWith("am start -W -n app.orchard.tv/.MainActivity"));assertFalse(calls.stream().anyMatch(command->command.contains("enabled_accessibility_services")||command.contains("uninstall")||command.contains("set-home-activity")));
    }
    @Test public void setupDoesNotClaimSuccessForFailedLaunchOrLostTransport()throws Exception{
        AerialIntegration.SetupResult failed=AerialIntegration.configure(command->"Error: Activity class does not exist\n__AERIAL_SETUP_EXIT__0\n","lite",19);assertFalse(failed.launched);assertEquals(1,AerialIntegration.grantCommands("lite").size());
        AerialIntegration.SetupResult offline=AerialIntegration.configure(command->{throw new IOException("disconnected");},"guard",21);assertFalse(offline.launched);assertTrue(offline.report.contains("disconnected"));
        try{AerialIntegration.configure(command->{fail();return "";},"lite",13);fail();}catch(IOException expected){}
    }

    @Test public void detectsInstalledEditionAndRejectsBrokenStatus()throws Exception{
        AerialIntegration.Installed standard=AerialIntegration.installed("app.orchard.tv","versionCode=99 minSdk=21\nversionName=1.77");assertEquals(99,standard.code);assertEquals("standard",standard.flavor);
        assertEquals("guard",AerialIntegration.installed("app.orchard.tv","versionCode=99\nversionName=1.77-guard").flavor);
        assertEquals("lite",AerialIntegration.installed("app.orchard.tv.lite","versionCode=1\nversionName=0.1").flavor);
        for(String dump:new String[]{"","versionCode=99","versionCode=99bad\nversionName=1.77","versionCode=-1\nversionName=1.77"}){try{AerialIntegration.installed("app.orchard.tv",dump);fail();}catch(IOException expected){}}
    }
    @Test public void distinguishesMissingFromFailedDetection()throws Exception{
        assertNull(AerialIntegration.status(command->command.startsWith("getprop")?"35":"").installed);
        AerialIntegration.Status status=AerialIntegration.status(command->command.startsWith("getprop")?"35":command.startsWith("pm list")?"package:app.orchard.tv\n":"versionCode=99\nversionName=1.77-guard");assertEquals("guard",status.installed.flavor);
        assertArrayEquals(new boolean[]{true,true},AerialIntegration.homeStatus("standard","android.app.role.HOME\napp.orchard.tv/.MainActivity\n","com.vendor/.Service:"+AerialIntegration.HOME_GUARD));
        assertArrayEquals(new boolean[]{false,false},AerialIntegration.homeStatus("standard","com.vendor/.Home","null"));
        try{AerialIntegration.status(command->command.startsWith("getprop")?"35":"Error: package manager unavailable");fail();}catch(IOException expected){}
    }
    @Test public void releaseOriginSizeAndDigestAreVerified()throws Exception{
        String url="https://github.com/pengchujin/aerial-tv/releases/download/v1.77/aerial-standard.apk",hash="sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        AerialIntegration.Release release=new AerialIntegration.Release("v1.77",url,4,hash,"standard");release.verify(4,hash.substring(7));
        try{release.verify(3,hash.substring(7));fail();}catch(IOException expected){}
        try{release.verify(4,"bad");fail();}catch(IOException expected){}
        for(String wrong:new String[]{url.replace("https:","http:"),url.replace("github.com","example.com"),url+"?x=1",url.replace("standard","guard")}){try{new AerialIntegration.Release("v1.77",wrong,4,hash,"standard");fail();}catch(IOException expected){}}
        for(int size:new int[]{0,-1,AerialIntegration.MAXIMUM+1}){try{new AerialIntegration.Release("v1.77",url,size,hash,"standard");fail();}catch(IOException expected){}}
    }
}
