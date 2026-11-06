package com.tvdeck.client;

/** Shared viewport geometry for decoded video, PNG and inverse touch mapping. */
final class PreviewTransform {
    float zoom=1,offsetX,offsetY;
    private float width,height,sourceWidth=1920,sourceHeight=1080;
    void size(float width,float height,float sourceWidth,float sourceHeight){
        this.width=Math.max(0,width);this.height=Math.max(0,height);
        this.sourceWidth=Math.max(1,sourceWidth);this.sourceHeight=Math.max(1,sourceHeight);clamp();
    }
    float baseScale(){return Math.min(width/sourceWidth,height/sourceHeight);}
    void scale(float factor,float anchorX,float anchorY){
        if(!Float.isFinite(factor)||factor<=0)return;
        float next=Math.max(1,Math.min(4,zoom*factor)),ratio=next/zoom;
        offsetX=anchorX-(anchorX-width/2-offsetX)*ratio-width/2;
        offsetY=anchorY-(anchorY-height/2-offsetY)*ratio-height/2;
        zoom=next;clamp();
    }
    void pan(float x,float y){if(zoom>1){offsetX+=x;offsetY+=y;clamp();}}
    void reset(){zoom=1;offsetX=offsetY=0;}
    private void clamp(){
        float x=Math.max(0,(sourceWidth*baseScale()*zoom-width)/2),y=Math.max(0,(sourceHeight*baseScale()*zoom-height)/2);
        offsetX=Math.max(-x,Math.min(x,offsetX));offsetY=Math.max(-y,Math.min(y,offsetY));
    }
    float[] point(float x,float y,boolean bounded){
        float scale=baseScale()*zoom;if(scale<=0)return null;
        float sx=(x-width/2-offsetX)/scale+sourceWidth/2,sy=(y-height/2-offsetY)/scale+sourceHeight/2;
        if(!bounded&&(sx<0||sy<0||sx>=sourceWidth||sy>=sourceHeight))return null;
        return new float[]{Math.max(0,Math.min(sourceWidth-1,sx)),Math.max(0,Math.min(sourceHeight-1,sy))};
    }
}
