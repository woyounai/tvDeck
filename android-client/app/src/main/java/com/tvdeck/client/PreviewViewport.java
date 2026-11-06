package com.tvdeck.client;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** A single surface survives normal/full-screen layout and shares geometry with PNG. */
final class PreviewViewport extends FrameLayout {
    interface Listener {
        void playback(boolean start);
        void fullscreen(boolean full);
        void mode(boolean mouse);
        void orientation(int orientation);
        default void controls(boolean expanded) {}
        void touch(int action,float x,float y,long time);
    }
    final SurfaceView surface;
    private final FrameLayout canvas;
    private final ImageView image;
    private final TextView state,zoomButton,fullButton,playButton,touchButton,mouseButton;
    private final Button retry;
    private final LinearLayout controls;
    private final TextView exit,expand;
    final PreviewTransform transform=new PreviewTransform();
    private final Listener listener;
    private boolean requested,ready,full,expanded,multi,mouse;
    private float centerX,centerY;
    private boolean hasCenter;
    private int sourceWidth=1920,sourceHeight=1080;

    PreviewViewport(Context context,boolean mouse,Listener listener){
        super(context);this.listener=listener;this.mouse=mouse;setBackgroundColor(Color.BLACK);
        canvas=new FrameLayout(context);canvas.setClipChildren(true);
        LayoutParams canvasParams=new LayoutParams(-1,-1);canvasParams.bottomMargin=dp(64);addView(canvas,canvasParams);
        surface=new SurfaceView(context);surface.setVisibility(INVISIBLE);canvas.addView(surface,new LayoutParams(-1,-1,Gravity.CENTER));
        image=new ImageView(context);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setVisibility(GONE);canvas.addView(image,new LayoutParams(-1,-1));
        state=new TextView(context);state.setTextColor(Color.WHITE);state.setTextSize(12);state.setPadding(dp(8),dp(6),dp(8),dp(6));state.setMaxLines(3);state.setBackgroundColor(0x99000000);state.setText(R.string.screen_preview);canvas.addView(state,new LayoutParams(-1,-2,Gravity.TOP));
        retry=new Button(context);retry.setText(R.string.retry_video_preview);retry.setVisibility(GONE);retry.setOnClickListener(v->listener.playback(true));LayoutParams retryParams=new LayoutParams(-2,dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);retryParams.topMargin=dp(62);canvas.addView(retry,retryParams);
        controls=new LinearLayout(context);controls.setGravity(Gravity.CENTER);controls.setPadding(dp(4),dp(4),dp(4),dp(4));controls.setBackgroundColor(0xcc111111);
        touchButton=control("☝",R.string.preview_touch,()->setMouse(false));mouseButton=control("⌖",R.string.preview_mouse,()->setMouse(true));
        zoomButton=control("1×",R.string.preview_zoom,()->{cancelTouch();if(transform.zoom>1.01f)transform.reset();else transform.scale(1.5f,canvas.getWidth()/2f,canvas.getHeight()/2f);applyTransform();});
        control("↻",R.string.preview_orientation,this::orientationMenu);
        fullButton=control("⛶",R.string.preview_fullscreen,()->{if(full){expanded=false;updateControls();}else listener.fullscreen(true);});
        playButton=control("▶",R.string.screen_preview,()->listener.playback(!requested));
        LayoutParams controlsParams=new LayoutParams(-1,dp(56),Gravity.BOTTOM);addView(controls,controlsParams);
        exit=overlay("×",R.string.preview_exit_fullscreen,()->listener.fullscreen(false),Gravity.TOP|Gravity.END);
        expand=overlay("⌃",R.string.preview_show_controls,()->{expanded=true;updateControls();},Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        ScaleGestureDetector detector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){transform.scale(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());applyTransform();return true;}
        });
        canvas.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot){layoutMedia();updateControls();}});
        canvas.setOnTouchListener((v,event)->{
            if(!ready)return true;
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){multi=false;hasCenter=false;}
            if(event.getPointerCount()>1&&!multi){multi=true;cancelTouch();v.getParent().requestDisallowInterceptTouchEvent(true);}
            detector.onTouchEvent(event);
            if(multi){
                if(event.getPointerCount()>=2&&action==MotionEvent.ACTION_MOVE){
                    float x=(event.getX(0)+event.getX(1))/2,y=(event.getY(0)+event.getY(1))/2;
                    if(hasCenter){transform.pan(x-centerX,y-centerY);applyTransform();}centerX=x;centerY=y;hasCenter=true;
                }else if(action!=MotionEvent.ACTION_MOVE)hasCenter=false;
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){multi=false;hasCenter=false;v.getParent().requestDisallowInterceptTouchEvent(false);}
                return true;
            }
            float[] point=transform.point(event.getX(),event.getY(),action!=MotionEvent.ACTION_DOWN);
            if(point!=null){v.getParent().requestDisallowInterceptTouchEvent(true);listener.touch(action,point[0],point[1],event.getEventTime());}
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)v.getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        });
        zoomButton.setOnLongClickListener(v->{Toast.makeText(context,R.string.preview_gesture_help,Toast.LENGTH_LONG).show();return true;});
        updateControls();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView control(String icon,int label,Runnable action){
        TextView button=new TextView(getContext());button.setText(icon);button.setTextColor(Color.WHITE);button.setTextSize(20);button.setGravity(Gravity.CENTER);button.setContentDescription(getContext().getString(label));button.setOnClickListener(v->action.run());button.setMinimumWidth(dp(44));controls.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));return button;
    }
    private TextView overlay(String icon,int label,Runnable action,int gravity){
        TextView button=new TextView(getContext());button.setText(icon);button.setTextColor(Color.WHITE);button.setTextSize(26);button.setGravity(Gravity.CENTER);button.setContentDescription(getContext().getString(label));button.setOnClickListener(v->action.run());
        GradientDrawable background=new GradientDrawable();background.setColor(0xaa111111);background.setCornerRadius(dp(24));button.setBackground(background);LayoutParams params=new LayoutParams(dp(48),dp(48),gravity);params.setMargins(dp(8),dp(8),dp(8),dp(8));addView(button,params);return button;
    }
    private void setMouse(boolean value){cancelTouch();mouse=value;listener.mode(value);updateControls();}
    private void orientationMenu(){
        PopupMenu menu=new PopupMenu(getContext(),controls);
        int[] labels={R.string.preview_orientation_auto,R.string.preview_orientation_portrait,R.string.preview_orientation_landscape};
        int[] orientations={ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE};
        for(int i=0;i<labels.length;i++){final int orientation=orientations[i];menu.getMenu().add(labels[i]).setOnMenuItemClickListener(item->{cancelTouch();listener.orientation(orientation);return true;});}
        menu.show();
    }
    void setRequested(boolean value){
        cancelTouch();requested=value;ready=false;retry.setVisibility(GONE);image.setVisibility(GONE);surface.setVisibility(value?VISIBLE:INVISIBLE);
        state.setText(value?R.string.preview_connecting:R.string.screen_preview);if(!value){transform.reset();image.setImageDrawable(null);}updateControls();
    }
    void videoFrame(int width,int height){ready=true;sourceWidth=width;sourceHeight=height;state.setText(R.string.preview_video);retry.setVisibility(GONE);layoutMedia();}
    void fallback(String reason){ready=false;surface.setVisibility(INVISIBLE);image.setVisibility(VISIBLE);state.setText(getContext().getString(R.string.preview_mode)+"\n"+reason);retry.setVisibility(VISIBLE);}
    void screenshot(Bitmap bitmap){ready=true;sourceWidth=bitmap.getWidth();sourceHeight=bitmap.getHeight();image.setImageBitmap(bitmap);layoutMedia();}
    void setFullScreen(boolean value){
        cancelTouch();full=value;expanded=false;LayoutParams params=(LayoutParams)canvas.getLayoutParams();params.bottomMargin=value?0:dp(64);canvas.setLayoutParams(params);updateControls();
    }
    private void layoutMedia(){MainActivity.fitPreviewSurface(surface,canvas,sourceWidth,sourceHeight);transform.size(canvas.getWidth(),canvas.getHeight(),sourceWidth,sourceHeight);applyTransform();}
    private void applyTransform(){
        for(View view:new View[]{surface,image}){view.setScaleX(transform.zoom);view.setScaleY(transform.zoom);view.setTranslationX(transform.offsetX);view.setTranslationY(transform.offsetY);}
        zoomButton.setText(String.format(Locale.ROOT,"%.1f×",transform.zoom));zoomButton.setContentDescription(getContext().getString(transform.zoom>1.01?R.string.preview_reset_zoom:R.string.preview_zoom));
    }
    private void updateControls(){
        LayoutParams params=(LayoutParams)controls.getLayoutParams();
        int width=full&&getWidth()>0?Math.min(dp(520),Math.max(1,getWidth()-dp(32))):-1;
        if(params.width!=width||params.gravity!=(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL)){params.width=width;params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;controls.setLayoutParams(params);}
        listener.controls(full&&expanded);
        controls.setVisibility(!full||expanded?VISIBLE:GONE);exit.setVisibility(full?VISIBLE:GONE);expand.setVisibility(full&&!expanded?VISIBLE:GONE);
        fullButton.setText(full?"⌄":"⛶");fullButton.setContentDescription(getContext().getString(full?R.string.preview_hide_controls:R.string.preview_fullscreen));
        playButton.setText(requested?"■":"▶");playButton.setContentDescription(getContext().getString(requested?R.string.stop_preview:R.string.screen_preview));
        touchButton.setSelected(!mouse);mouseButton.setSelected(mouse);touchButton.setAlpha(mouse?.45f:1);mouseButton.setAlpha(mouse?1:.45f);applyTransform();
    }
    void cancelTouch(){listener.touch(MotionEvent.ACTION_CANCEL,0,0,0);}
}
