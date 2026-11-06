package com.tvdeck.client;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewTransformTest {
    @Test public void aspectFitAndLetterboxTouch(){
        PreviewTransform view=new PreviewTransform();view.size(1000,1000,1920,1080);
        assertArrayEquals(new float[]{960,540},view.point(500,500,false),.01f);
        assertNull(view.point(500,100,false));
        assertArrayEquals(new float[]{960,0},view.point(500,0,true),.01f);
        assertNull(new PreviewTransform().point(0,0,false));
    }
    @Test public void zoomKeepsAnchorAndBoundsPan(){
        PreviewTransform view=new PreviewTransform();view.size(1000,562.5f,1920,1080);
        float[] anchor=view.point(650,300,false);
        view.scale(2,650,300);
        assertArrayEquals(anchor,view.point(650,300,false),.01f);
        view.pan(10000,-10000);
        assertEquals(500,view.offsetX,.01f);assertEquals(-281.25f,view.offsetY,.01f);
        view.scale(100,500,281.25f);assertEquals(4,view.zoom,.001f);
        view.scale(.00001f,500,281.25f);assertEquals(1,view.zoom,.001f);
        assertEquals(0,view.offsetX,.001f);assertEquals(0,view.offsetY,.001f);
    }
    @Test public void resizeAndResetNeverLeaveUnreachableViewport(){
        PreviewTransform view=new PreviewTransform();view.size(1000,500,1920,1080);view.scale(3,500,250);view.pan(10000,10000);
        view.size(500,1000,1920,1080);assertEquals(0,view.offsetY,.01f);
        view.reset();assertEquals(1,view.zoom,.01f);assertEquals(0,view.offsetX,.01f);
        view.scale(Float.NaN,0,0);view.scale(-1,0,0);assertEquals(1,view.zoom,.01f);
    }
}
