package com.tvdeck.client;

import android.Manifest;
import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private TextView hint;
    private Camera camera;private SurfaceView surface;private boolean visible,ready;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding=new AtomicBoolean();
    @Override public void onCreate(Bundle state){super.onCreate(state);if(!UsageConsent.accepted(this)){finish();return;}surface=new SurfaceView(this);FrameLayout panel=new FrameLayout(this);panel.addView(surface);hint=new TextView(this);hint.setText(getIntent().getBooleanExtra("aerial",false)?R.string.aerial_scan_hint:R.string.scan_connection_qr_hint);hint.setTextColor(android.graphics.Color.WHITE);hint.setBackgroundColor(0xCC090B19);hint.setPadding(24,24,24,48);panel.addView(hint,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(panel);surface.getHolder().addCallback(this);if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);}
    @Override protected void onResume(){super.onResume();visible=true;start();}
    @Override protected void onPause(){visible=false;stop();super.onPause();}
    @Override protected void onDestroy(){decoder.shutdownNow();super.onDestroy();}
    public void surfaceCreated(SurfaceHolder holder){ready=true;start();}
    public void surfaceChanged(SurfaceHolder holder,int f,int w,int h){}
    public void surfaceDestroyed(SurfaceHolder holder){ready=false;stop();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)start();else unavailable();}
    private void unavailable(){new AlertDialog.Builder(this).setMessage(R.string.camera_unavailable).setPositiveButton(R.string.close,(d,w)->finish()).show();}
    private void start(){if(!UsageConsent.accepted(this)||!visible||!ready||camera!=null||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;try{
        camera=Camera.open();Camera.Parameters params=camera.getParameters();if(params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);camera.setParameters(params);camera.setDisplayOrientation(90);camera.setPreviewDisplay(surface.getHolder());
        camera.setPreviewCallback((data,source)->{if(!decoding.compareAndSet(false,true))return;Camera.Size size=source.getParameters().getPreviewSize();byte[] copy=data.clone();decoder.execute(()->{try{
            Result result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(copy,size.width,size.height,0,0,size.width,size.height,false))));
            Intent scanned=new Intent();
            try{Endpoints.Scan endpoint=Endpoints.scan(result.getText(),getIntent().getBooleanExtra("aerial",false));if(endpoint.aerial!=null)scanned.putExtra("aerial_url",endpoint.aerial.toASCIIString());else scanned.putExtra("host",endpoint.device.host).putExtra("port",endpoint.device.port);}
            catch(Exception invalid){runOnUiThread(()->hint.setText(getIntent().getBooleanExtra("aerial",false)?R.string.invalid_aerial_qr:R.string.invalid_connection_qr));return;}
            runOnUiThread(()->{if(!visible)return;visible=false;setResult(RESULT_OK,scanned);finish();});
        }catch(Exception ignored){}finally{decoding.set(false);}});});camera.startPreview();
    }catch(Exception error){stop();unavailable();}}
    private void stop(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
}
