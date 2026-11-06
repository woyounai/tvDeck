package com.tvdeck.client;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.net.nsd.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.RelativeSizeSpan;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class MainActivity extends Activity implements DeckModel.Listener {
    private DeckModel model;
    private LinearLayout root,content,nav,aerialPanel;
    private String aerialFlavor="standard";
    private TextView status;
    private ProgressBar aerialProgressBar;
    private int tab;
    private boolean settingsTab;
    private boolean aerialDetails;
    private String path="/sdcard", pendingDownload;
    private boolean systemApps;
    private int appFilter, fileSort;
    private boolean fileAscending=true;
    private String fileQuery="", appQuery="";
    private List<AdbFiles.Entry> fileEntries=Collections.emptyList();
    private LinearLayout fileList;
    private boolean fileLoadPending;
    private android.content.SharedPreferences preferences;
    private boolean recording;
    private List<String> pendingApkPaths;
    private final Map<String,Boolean> selectedFiles=new LinkedHashMap<>();
    private String shellOutput="";
    private boolean shellKeywordsVisible;
    private Runnable pendingAppLoad;
    private String shellDraft="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> discoveries=new ArrayList<>();
    private final Map<String,String> discoveredDevices=new LinkedHashMap<>();
    private LinearLayout discoveryPanel;
    private boolean discoveryScanning, discoveryFinished;
    private int discoveryGeneration;
    private volatile boolean preview;
    private LanScanner lanScanner;
    private final List<String> shellHistory=new ArrayList<>();
    private PreviewViewport previewViewport;
    private boolean previewFullScreen,previewGestureActive,previewMouseDown;
    private int previewPreviousOrientation,previewPreviousConfigurationOrientation;
    private final Map<View,Integer> previewHiddenViews=new IdentityHashMap<>();
    private float previewStartX,previewStartY,previewLastX,previewLastY;
    private long previewTouchStarted;
    private LinearLayout previewPanel;
    private VideoPreview videoPreview;
    private volatile long directionGeneration;
    private FrameLayout remoteOverlay;
    private View volumePopup;
    private Runnable cancelVolumeGesture;
    private AlertDialog keyboardDialog,logsDialog;
    private volatile io.github.muntashirakon.adb.AdbStream logsStream;
    private volatile long logsGeneration;
    private String logBuffer="",logSearch="",logTag="",logPid="",logLevel="";
    private volatile long previewGeneration;
    private final AtomicInteger dx=new AtomicInteger(),dy=new AtomicInteger();
    private final AtomicBoolean mouseSending=new AtomicBoolean();
    private boolean darkMode,resumed;
    private MousePad mousePad;
    private int CYAN=Color.rgb(35,229,240), BG=Color.rgb(9,11,25), FG=Color.rgb(231,237,250);

    private String t(int id) {return getString(id);}
    @Override protected void attachBaseContext(Context base){
        String language=base.getSharedPreferences("settings",0).getString("language","");
        if(!language.isEmpty()){android.content.res.Configuration config=new android.content.res.Configuration(base.getResources().getConfiguration());config.setLocales(LocaleList.forLanguageTags(language));base=base.createConfigurationContext(config);}super.attachBaseContext(base);
    }
    @Override public void onCreate(Bundle state) {
        preferences=getSharedPreferences("settings",0);shellHistory.addAll(loadStringList(getSharedPreferences("shell-history",0).getString("items","[]")));applyTheme();
        super.onCreate(state);
        if(!UsageConsent.accepted(this)){
            startActivity(new Intent(this,UsageNoticeActivity.class));
            finish();
            return;
        }
        if(getIntent().getBooleanExtra("remote",false))tab=2;model=((DeckApplication)getApplication()).model();
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        render();
        if(state==null&&!model.connected&&preferences.getBoolean("auto-connect",false)){DeviceStore.Device recent=null;for(DeviceStore.Device device:model.devices.all())if(recent==null||device.connectedAt>recent.connectedAt)recent=device;if(recent!=null)connect(recent.host,recent.port,null,0);}
    }
    @Override public void onResume() {super.onResume();if(model==null)return;if(!UsageConsent.accepted(this)){stopService(new Intent(this,ConnectionService.class));model.worker.execute(model::disconnect);startActivity(new Intent(this,UsageNoticeActivity.class));finish();return;}resumed=true;model.listener=this;if(!model.connected&&!settingsTab)render();changed();if(mousePad!=null)mousePad.start();if(model.connected&&model.aerialStatus==null)model.refreshAerial();}
    @Override public void onPause() {if(model==null){super.onPause();return;}resumed=false;if(mousePad!=null)mousePad.stop();directionGeneration++;closeVolumeGesture();setPreviewFullScreen(false);stopPreview();stopDiscovery();if(keyboardDialog!=null)keyboardDialog.dismiss();if(logsDialog!=null)logsDialog.dismiss();super.onPause();}
    @Override public void onDestroy() {if(model!=null&&model.listener==this) model.listener=null;super.onDestroy();}
    @Override public void onBackPressed(){if(previewFullScreen){setPreviewFullScreen(false);return;}if(aerialDetails){aerialDetails=false;tab=4;render();return;}super.onBackPressed();}
    @Override public void changed() {if(model==null||!UsageConsent.accepted(this))return;if(!model.connected&&nav!=null){nav=null;render();return;}if(status!=null){status.setText(model.busy?(model.installWorking?model.operation+"  "+Math.round(model.installProgress*100)+"%":model.operation):model.deviceName);status.setVisibility(model.busy&&!model.operation.isEmpty()?View.VISIBLE:View.INVISIBLE);}setButtonsEnabled(content,!model.busy);setButtonsEnabled(nav,!model.busy);if(aerialProgressBar!=null){boolean installing=model.busy&&model.installWorking;aerialProgressBar.setVisibility(model.busy&&(model.aerialWorking||installing)?View.VISIBLE:View.INVISIBLE);double value=installing?model.installProgress:model.aerialProgress;aerialProgressBar.setIndeterminate(Double.isNaN(value));if(!Double.isNaN(value))aerialProgressBar.setProgress((int)(value*1000));}if(previewFullScreen){if(status!=null)status.setVisibility(View.GONE);if(aerialProgressBar!=null)aerialProgressBar.setVisibility(View.GONE);}renderAerialCard();loadPendingFiles();loadPendingApps();}
    private void setButtonsEnabled(View view,boolean enabled) {if(view instanceof Button)view.setEnabled(enabled);else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setButtonsEnabled(group.getChildAt(i),enabled);}}
    @Override public void error(String message) {
        if(isFinishing()||isDestroyed()) return;
        if(!model.connected&&nav!=null){nav=null;render();}
        new AlertDialog.Builder(this).setTitle(R.string.operation_failed).setMessage(message+(model.connected?"":"\n\n"+t(R.string.connect_help)+"\n\n"+t(R.string.vpn_lan_help))).setPositiveButton(R.string.close,null).show();
    }
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private void applyTheme() {
        String appearance=preferences.getString("appearance","system");
        boolean dark=appearance.equals("dark")||appearance.equals("system")&&(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean cyberpunk=preferences.getString("visual-theme","cyberpunk").equals("cyberpunk");
        darkMode=dark;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        getTheme().applyStyle(dark?R.style.DeckDarkDialogOverlay:R.style.DeckLightDialogOverlay,true);
        if(cyberpunk){BG=dark?Color.rgb(9,11,25):Color.rgb(242,241,250);FG=dark?Color.rgb(231,237,250):Color.rgb(24,24,38);CYAN=dark?Color.rgb(35,229,240):Color.rgb(77,46,163);}
        else{BG=dark?Color.rgb(18,20,26):Color.rgb(244,245,248);FG=dark?Color.rgb(235,237,242):Color.rgb(28,31,38);CYAN=dark?Color.rgb(72,150,255):Color.rgb(20,104,220);}
    }
    private LinearLayout column() {LinearLayout view=new LinearLayout(this);view.setOrientation(1);return view;}
    private TextView text(String value,int size) {TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(FG);view.setPadding(dp(8),dp(8),dp(8),dp(8));return view;}
    private EditText field(int hint,String initial) {EditText view=new EditText(this);view.setTextColor(FG);view.setHintTextColor(darkMode?Color.LTGRAY:Color.DKGRAY);view.setHint(hint);view.setText(initial);view.setSingleLine();return view;}
    private EditText searchField(int hint,String initial){EditText view=field(hint,initial);view.setTextSize(16);view.setPadding(dp(18),0,dp(18),0);view.setBackground(rounded(darkMode?Color.rgb(29,31,43):Color.WHITE,Color.TRANSPARENT,dp(28)));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.setMargins(dp(10),dp(8),dp(10),dp(8));view.setLayoutParams(params);return view;}
    private Button button(String title,Runnable action) {
        Button button=new Button(this);button.setText(title);button.setTextColor(CYAN);button.setAllCaps(false);
        GradientDrawable bg=new GradientDrawable();bg.setColor(darkMode?Color.rgb(19,23,44):Color.WHITE);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),CYAN&0x55ffffff);button.setBackground(bg);button.setMinHeight(dp(52));button.setPadding(dp(12),dp(8),dp(12),dp(8));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(dp(4),dp(4),dp(4),dp(4));button.setLayoutParams(params);
        button.setOnClickListener(v->action.run());return button;
    }
    private Button remoteGlass(Button button){
        GradientDrawable normal=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(59,69,96),Color.rgb(31,39,59)});
        normal.setCornerRadius(dp(20));normal.setStroke(dp(1),0x55ffffff);
        GradientDrawable pressed=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(71,119,211),Color.rgb(35,65,130)});
        pressed.setCornerRadius(dp(20));pressed.setStroke(dp(2),Color.rgb(88,148,255));
        StateListDrawable background=new StateListDrawable();background.addState(new int[]{android.R.attr.state_pressed},pressed);background.addState(new int[]{},normal);
        button.setBackground(background);button.setTextColor(Color.WHITE);button.setElevation(dp(5));return button;
    }
    private LinearLayout card() {
        LinearLayout card=column();GradientDrawable background=new GradientDrawable();background.setColor(darkMode?Color.rgb(16,19,38):Color.WHITE);background.setCornerRadius(dp(20));background.setStroke(dp(1),CYAN&0x44ffffff);card.setBackground(background);card.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(7),0,dp(7));card.setLayoutParams(params);return card;
    }
    private void sectionTitle(LinearLayout target,int title) {TextView heading=text(t(title),16);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(dp(8),dp(10),dp(8),dp(6));target.addView(heading);}
    private void rowInto(LinearLayout target,int[] titles,Runnable[] actions) {LinearLayout row=new LinearLayout(this);for(int i=0;i<titles.length;i++){Button b=button(t(titles[i]),actions[i]);b.setTextSize(12);row.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}target.addView(row);}
    private void add(int id,Runnable action) {content.addView(button(t(id),action));}
    private void row(int[] titles,Runnable[] actions) {
        LinearLayout row=new LinearLayout(this);
        for(int i=0;i<titles.length;i++) {Button b=button(t(titles[i]),actions[i]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);p.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,p);}
        content.addView(row);
    }
    private void segmented(int[] titles,int selected,Runnable[] actions){LinearLayout group=new LinearLayout(this);group.setGravity(Gravity.CENTER);group.setPadding(dp(4),dp(4),dp(4),dp(4));group.setBackground(rounded(darkMode?Color.rgb(34,36,47):Color.rgb(229,229,234),Color.TRANSPARENT,dp(28)));LinearLayout.LayoutParams groupParams=new LinearLayout.LayoutParams(-1,dp(48));groupParams.setMargins(dp(4),dp(4),dp(4),dp(4));for(int i=0;i<titles.length;i++){final int index=i;TextView item=text(t(titles[i]),13);item.setGravity(Gravity.CENTER);item.setTextColor(selected==i?FG:Color.GRAY);item.setTypeface(null,selected==i?Typeface.BOLD:Typeface.NORMAL);item.setBackground(rounded(selected==i?(darkMode?Color.rgb(62,64,77):Color.WHITE):Color.TRANSPARENT,Color.TRANSPARENT,dp(24)));item.setOnClickListener(v->actions[index].run());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);params.setMargins(dp(2),0,dp(2),0);group.addView(item,params);}content.addView(group,groupParams);}
    private <T> void work(int title,DeckModel.Work<T> job,java.util.function.Consumer<T> done) {model.run(t(title),job,value->{
        if(isFinishing()||isDestroyed()){if(value instanceof Closeable)try{((Closeable)value).close();}catch(IOException ignored){}return;}
        done.accept(value);
    });}
    private void output(String title,String value) {
        TextView body=text(value,13);body.setTypeface(Typeface.MONOSPACE);body.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        showAlignedDialog(new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(R.string.close,null).create());
    }
    private void showAlignedDialog(AlertDialog dialog){dialog.show();Window window=dialog.getWindow();if(window==null)return;window.setBackgroundDrawable(rounded(darkMode?Color.rgb(22,24,36):Color.rgb(255,255,255),CYAN&0x33444444,dp(28)));WindowManager.LayoutParams params=window.getAttributes();params.width=getResources().getDisplayMetrics().widthPixels-dp(24);params.height=-2;params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;params.y=dp(8);params.dimAmount=0.34f;window.setAttributes(params);}
    private void confirm(String title,Runnable action) {new AlertDialog.Builder(this).setTitle(title).setMessage(R.string.confirm_destructive).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.confirm,(d,w)->action.run()).show();}
    private void render() {
        if(!UsageConsent.accepted(this))return;
        if(mousePad!=null){mousePad.stop();mousePad=null;}
        fileList=null;fileLoadPending=false;pendingAppLoad=null;
        if(!model.connected)aerialDetails=false;
        nav=null;aerialPanel=null;
        directionGeneration++;setPreviewFullScreen(false);previewFloatingControls=null;stopPreview();previewViewport=null;
        if(root==null){
            root=column();root.setPadding(dp(12),dp(8),dp(12),dp(8));
            root.setFocusableInTouchMode(true);
            root.setOnApplyWindowInsetsListener((v,insets)->{if(previewFullScreen)v.setPadding(0,0,0,0);else v.setPadding(dp(12),insets.getSystemWindowInsetTop()+dp(8),dp(12),insets.getSystemWindowInsetBottom()+dp(8));return insets;});
            root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(previewFullScreen)resizeFullScreenPreview();});
            setContentView(root);
        }else root.removeAllViews();
        root.setBackgroundColor(BG);
        FrameLayout header=new FrameLayout(this);header.setMinimumHeight(dp(54));int[] pageTitles={R.string.files,R.string.apps,R.string.remote,R.string.shell,R.string.tools};
        String pageTitle=aerialDetails?"Aerial":tab==0?model.deviceName:t(pageTitles[Math.max(0,Math.min(tab,pageTitles.length-1))]);TextView title=text(model.connected?pageTitle:"TVDeck",model.connected?20:28);title.setTextColor(FG);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(model.connected?Gravity.CENTER:Gravity.START|Gravity.CENTER_VERTICAL);FrameLayout.LayoutParams titleParams=new FrameLayout.LayoutParams(-1,-1,model.connected?Gravity.CENTER:Gravity.START|Gravity.CENTER_VERTICAL);titleParams.leftMargin=dp(model.connected?0:8);titleParams.rightMargin=dp(model.connected?0:108);header.addView(title,titleParams);
        if(model.connected&&aerialDetails){TextView back=toolbarAction("‹",()->{aerialDetails=false;render();});back.setContentDescription(t(R.string.back));header.addView(back,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.START|Gravity.CENTER_VERTICAL));ImageView disconnect=toolbarIcon(R.drawable.ic_disconnect,R.string.disconnect,()->{new Thread(()->{model.disconnect();stopService(new Intent(this,ConnectionService.class));handler.post(this::render);},"tvdeck-disconnect").start();});header.addView(disconnect,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.END|Gravity.CENTER_VERTICAL));}
        else if(model.connected) {ImageView disconnect=toolbarIcon(R.drawable.ic_disconnect,R.string.disconnect,()->{new Thread(()->{model.disconnect();stopService(new Intent(this,ConnectionService.class));handler.post(this::render);},"tvdeck-disconnect").start();});FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.START|Gravity.CENTER_VERTICAL);header.addView(disconnect,params);}
        if(model.connected&&tab==1){TextView refresh=toolbarAction("↻",this::render);header.addView(refresh,new FrameLayout.LayoutParams(dp(48),dp(46),Gravity.END|Gravity.CENTER_VERTICAL));}
        if(!model.connected){TextView add=toolbarAction("+",()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.connect),t(R.string.pair)},(d,i)->connectDialog(i==1)).show());add.setContentDescription(t(R.string.connect));header.addView(add,new FrameLayout.LayoutParams(dp(48),dp(46),Gravity.END|Gravity.CENTER_VERTICAL));TextView scan=toolbarAction(discoveryScanning?"×":"⌕",()->{if(discoveryScanning)stopDiscovery();else discover();render();});scan.setContentDescription(t(discoveryScanning?R.string.stop_scan:R.string.scan_lan));FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(48),dp(46),Gravity.END|Gravity.CENTER_VERTICAL);params.rightMargin=dp(50);header.addView(scan,params);}
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(58)));status=text("",12);status.setSingleLine();status.setEllipsize(TextUtils.TruncateAt.END);root.addView(status,new LinearLayout.LayoutParams(-1,dp(32)));aerialProgressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);aerialProgressBar.setMax(1000);root.addView(aerialProgressBar,new LinearLayout.LayoutParams(-1,dp(4)));changed();
        content=column();if(model.connected&&tab==3)root.addView(content,new LinearLayout.LayoutParams(-1,0,1));else{FrameLayout page=new FrameLayout(this);ScrollView scroll=new ScrollView(this);if(model.connected&&tab==2)content.setPadding(0,0,0,dp(84));scroll.addView(content);page.addView(scroll,new FrameLayout.LayoutParams(-1,-1));if(model.connected&&tab==2)remoteFloatingControls(page);root.addView(page,new LinearLayout.LayoutParams(-1,0,1));}
        int[] labels=model.connected?new int[]{R.string.files,R.string.apps,R.string.remote,R.string.shell,R.string.tools}:new int[]{R.string.home,R.string.settings};
        String[] icons=model.connected?new String[]{"▤","▦","◉","⌘","⚙"}:new String[]{"⌂","⚙"};nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(4),dp(4),dp(4),dp(4));nav.setBackground(rounded(darkMode?Color.rgb(31,33,44):Color.rgb(255,255,255),Color.TRANSPARENT,dp(34)));nav.setElevation(dp(5));LinearLayout.LayoutParams navParams=new LinearLayout.LayoutParams(-1,dp(64));navParams.setMargins(dp(18),dp(4),dp(18),dp(8));
        for(int i=0;i<labels.length;i++){final int page=i;TextView item=navItem(icons[i],labels[i],model.connected?tab==i:settingsTab==(i==1),()->{if(model.connected){if(page!=4)aerialDetails=false;tab=page;}else{settingsTab=page==1;if(settingsTab)stopDiscovery();}render();});LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(0,-1,1);itemParams.setMargins(dp(2),0,dp(2),0);nav.addView(item,itemParams);}if(!aerialDetails)root.addView(nav,navParams);
        root.requestFocus();
        if(!model.connected){if(settingsTab)settingsPage();else home();}else switch(tab) {case 0:files();break;case 1:apps();break;case 2:remote();break;case 3:shell();break;case 4:if(aerialDetails)aerialDetailsPage();else tools();break;}
    }
    private TextView navItem(String icon,int label,boolean selected,Runnable action) {String value=icon+"\n"+t(label);android.text.SpannableString styled=new android.text.SpannableString(value);styled.setSpan(new RelativeSizeSpan(1.9f),0,icon.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);TextView item=new TextView(this);item.setText(styled);item.setGravity(Gravity.CENTER);item.setTextSize(11);item.setLineSpacing(dp(2),1f);item.setTextColor(selected?CYAN:Color.GRAY);item.setTypeface(null,selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);item.setBackground(rounded(selected?(darkMode?Color.rgb(45,47,61):Color.rgb(238,238,243)):Color.TRANSPARENT,Color.TRANSPARENT,dp(28)));item.setOnClickListener(v->action.run());return item;}
    private TextView toolbarAction(String label,Runnable action){TextView item=new TextView(this);item.setText(label);item.setGravity(Gravity.CENTER);item.setTextSize(label.length()>2?14:24);item.setTextColor(CYAN);item.setPadding(dp(label.length()>2?14:0),0,dp(label.length()>2?14:0),0);item.setBackground(rounded(darkMode?Color.rgb(24,27,42):Color.rgb(255,255,255),Color.TRANSPARENT,dp(24)));item.setOnClickListener(v->action.run());return item;}
    private ImageView toolbarIcon(int icon,int label,Runnable action){ImageView item=new ImageView(this);item.setImageResource(icon);item.setColorFilter(CYAN);item.setContentDescription(t(label));item.setPadding(dp(10),dp(10),dp(10),dp(10));item.setBackground(rounded(darkMode?Color.rgb(24,27,42):Color.rgb(255,255,255),Color.TRANSPARENT,dp(22)));item.setOnClickListener(v->action.run());return item;}
    private GradientDrawable rounded(int fill,int stroke,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(radius);if(stroke!=Color.TRANSPARENT)shape.setStroke(dp(1),stroke);return shape;}
    private void home() {
        LinearLayout local=card();sectionTitle(local,R.string.local_network_devices);
        local.addView(button("▦   "+t(R.string.scan_connection_qr),()->startActivityForResult(new Intent(this,ScanActivity.class),13)));
        discoveryPanel=column();local.addView(discoveryPanel);renderDiscoveryPanel();
        content.addView(local);
        LinearLayout savedPanel=column();sectionTitle(savedPanel,R.string.my_tvs);
        for(DeviceStore.Device device:model.devices.all()){
            LinearLayout saved=new LinearLayout(this);saved.setGravity(Gravity.CENTER_VERTICAL);saved.setPadding(dp(16),dp(12),dp(16),dp(12));saved.setMinimumHeight(dp(82));saved.setBackground(rounded(darkMode?Color.rgb(19,23,44):Color.WHITE,Color.TRANSPARENT,dp(24)));
            TextView icon=text(device.favorite?"★":"▣",22);icon.setTextColor(CYAN);saved.addView(icon,new LinearLayout.LayoutParams(dp(42),-2));
            LinearLayout details=column();TextView name=text(device.name,16);name.setTypeface(null,Typeface.BOLD);TextView endpoint=text(device.host+":"+device.port+(device.group.isEmpty()?"":" · "+device.group),12);endpoint.setTextColor(Color.GRAY);details.addView(name);details.addView(endpoint);saved.addView(details,new LinearLayout.LayoutParams(0,-2,1));TextView chevron=text("›",24);chevron.setTextColor(Color.GRAY);saved.addView(chevron);saved.setOnClickListener(v->connect(device.host,device.port,null,0));saved.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle(device.name).setItems(new String[]{t(R.string.edit_device),t(R.string.favorite),t(R.string.delete)},(d,i)->{if(i==0)editDevice(device);else if(i==1){device.favorite=!device.favorite;model.devices.save(device);DeckWidget.updateAll(this);render();}else confirm(t(R.string.delete),()->{model.devices.remove(device.id);DeckWidget.updateAll(this);render();});}).show();return true;});LinearLayout.LayoutParams savedParams=new LinearLayout.LayoutParams(-1,-2);savedParams.setMargins(dp(16),dp(4),dp(16),dp(4));savedPanel.addView(saved,savedParams);
        }
        if(model.devices.all().isEmpty())savedPanel.addView(text(t(R.string.no_devices_yet),13));content.addView(savedPanel);content.addView(text(t(R.string.connect_help),13));
    }
    private void editDevice(DeviceStore.Device device){
        LinearLayout form=column();EditText name=field(R.string.device_name,device.name),host=field(R.string.host,device.host),port=field(R.string.port,String.valueOf(device.port)),group=field(R.string.device_group,device.group),note=field(R.string.device_note,device.note);for(EditText input:new EditText[]{name,host,port,group,note})form.addView(input);
        new AlertDialog.Builder(this).setTitle(R.string.edit_device).setView(form).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.save,(d,w)->{try{int value=Endpoints.port(port.getText().toString());String address=host.getText().toString().trim();if(address.isEmpty()||address.contains("/")||address.contains(" "))throw new IllegalArgumentException();device.name=name.getText().toString();device.host=address;device.port=value;device.group=group.getText().toString();device.note=note.getText().toString();model.devices.save(device);DeckWidget.updateAll(this);render();}catch(Exception error){error(t(R.string.invalid_endpoint));}}).show();
    }
    private void scanAerial(){startActivityForResult(new Intent(this,ScanActivity.class).putExtra("aerial",true),22);}
    private void connectDialog(boolean pair) {
        LinearLayout form=column();EditText host=field(R.string.host,(model.devices.all().isEmpty()?"":model.devices.all().get(0).host));EditText port=field(R.string.port,"5555");port.setInputType(2);form.addView(host);form.addView(port);
        EditText pairingPort=field(R.string.pairing_port,"");pairingPort.setInputType(2);EditText code=field(R.string.pairing_code,"");code.setInputType(2);
        if(pair) {form.addView(pairingPort);form.addView(code);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(pair?R.string.pair:R.string.connect).setView(form).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.connect,null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try {
            String address=host.getText().toString().trim();if(address.isEmpty()||address.contains("/")||address.contains(" ")) throw new IllegalArgumentException();
            int p=Endpoints.port(port.getText().toString());int pp=pair?Endpoints.port(pairingPort.getText().toString()):0;
            String pin=pair?code.getText().toString():null;if(pair&&!pin.matches("[0-9]{6}")) throw new IllegalArgumentException();
            dialog.dismiss();connect(address,p,pin,pp);
        } catch(Exception error) {host.setError(t(R.string.invalid_endpoint));}}));showAlignedDialog(dialog);
    }
    private void connect(String host,int port,String code,int pairingPort) {
        stopDiscovery();try{ConnectionService.hold(this);}catch(Exception ignored){}work(R.string.connecting,()->{model.connect(host,port,code,pairingPort);return true;},ok->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},21);try{ConnectionService.hold(this);}catch(Exception ignored){}tab=0;path="/sdcard";render();model.refreshAerial();});
    }
    private void discover() {
        if(!UsageConsent.accepted(this))return;
        stopDiscovery();discoveredDevices.clear();discoveryFinished=false;discoveryScanning=true;int generation=discoveryGeneration;
        renderDiscoveryPanel();NsdManager nsd=(NsdManager)getSystemService(NSD_SERVICE);
        for(String type:new String[]{"_adb._tcp.","_adb-tls-connect._tcp."}) {
            NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String type) { }
                public void onServiceFound(NsdServiceInfo info) {nsd.resolveService(info,new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo i,int code) { }
                    public void onServiceResolved(NsdServiceInfo i) {handler.post(()->{if(discoveryGeneration==generation&&discoveryScanning&&!model.connected&&i.getHost()!=null)addDiscoveredDevice(i.getHost().getHostAddress(),i.getPort(),i.getServiceName());});}
                });}
                public void onServiceLost(NsdServiceInfo info) { }
                public void onDiscoveryStopped(String type) { }
                public void onStartDiscoveryFailed(String type,int error) {handler.post(()->{if(discoveryGeneration==generation)MainActivity.this.error(t(R.string.discovery_failed));});}
                public void onStopDiscoveryFailed(String type,int error) { }
            };
            discoveries.add(listener);nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);
        }
        LanScanner scanner=new LanScanner();lanScanner=scanner;try{scanner.start(this,new LanScanner.Observer(){public void found(String host){handler.post(()->{if(discoveryGeneration==generation&&discoveryScanning&&!model.connected)addDiscoveredDevice(host,5555,host);});}public void finished(){}});}catch(Exception error){scanner.close();lanScanner=null;error(error.getLocalizedMessage());}
        handler.postDelayed(()->{if(discoveryGeneration==generation)finishDiscovery();},15000);
    }
    private void addDiscoveredDevice(String host,int port,String name){
        String endpoint=host+":"+port;if(discoveredDevices.putIfAbsent(endpoint,name)==null)renderDiscoveryPanel();
    }
    private void renderDiscoveryPanel(){
        if(discoveryPanel==null||settingsTab||model.connected)return;
        discoveryPanel.removeAllViews();
        discoveryPanel.addView(button(discoveryScanning?t(R.string.stop_scan):t(discoveryFinished?R.string.retry:R.string.scan_lan),()->{if(discoveryScanning)stopDiscovery();else discover();render();}));
        if(discoveryScanning)discoveryPanel.addView(text(t(R.string.scanning_lan),13));
        for(Map.Entry<String,String> device:discoveredDevices.entrySet()){
            String endpoint=device.getKey();String name=device.getValue();
            discoveryPanel.addView(button(name.equals(endpoint.substring(0,endpoint.lastIndexOf(':')))?endpoint:name+"  "+endpoint,()->{int split=endpoint.lastIndexOf(':');connect(endpoint.substring(0,split),Integer.parseInt(endpoint.substring(split+1)),null,0);}));
        }
        if(discoveryFinished&&discoveredDevices.isEmpty())discoveryPanel.addView(text(t(R.string.no_devices_found),13));
    }
    private void finishDiscovery(){stopDiscovery();discoveryFinished=true;render();}
    private void stopDiscovery() {discoveryGeneration++;discoveryScanning=false;if(lanScanner!=null){lanScanner.close();lanScanner=null;}NsdManager nsd=(NsdManager)getSystemService(NSD_SERVICE);for(NsdManager.DiscoveryListener l:discoveries) try{nsd.stopServiceDiscovery(l);}catch(Exception ignored){}discoveries.clear();renderDiscoveryPanel();}
    private void files() {
        LinearLayout location=card();sectionTitle(location,R.string.current_location);
        TextView currentPath=text(path,14);currentPath.setTypeface(Typeface.MONOSPACE);location.addView(currentPath);content.addView(location);
        row(new int[]{R.string.up,R.string.refresh,R.string.more},new Runnable[]{()->{if(!path.equals("/")){path=path.substring(0,path.lastIndexOf('/'));if(path.isEmpty())path="/";selectedFiles.clear();render();}},this::refreshFiles,()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.install),t(R.string.upload_folder),t(R.string.download_selection),t(R.string.delete_selection),t(R.string.new_folder)},(d,i)->{if(i==0)pick(12,"*/*");else if(i==1)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),16);else if(i==2){if(!selectedFiles.isEmpty())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),19);}else if(i==3)deleteSelectedFiles();else nameDialog(R.string.new_folder,"",name->work(R.string.files,()->model.mutateFile("mkdir",child(name),null),v->refreshFiles()));}).show()});
        row(new int[]{R.string.upload,R.string.install,R.string.new_folder},new Runnable[]{this::uploadChoice,()->pick(12,"*/*"),()->nameDialog(R.string.new_folder,"",name->work(R.string.files,()->model.mutateFile("mkdir",child(name),null),v->refreshFiles()))});
        EditText search=searchField(R.string.search_files,fileQuery);content.addView(search);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){fileQuery=s.toString();renderFileEntries();}public void afterTextChanged(Editable s){}});
        row(new int[]{R.string.sort_by,fileAscending?R.string.ascending:R.string.descending},new Runnable[]{()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.sort_name),t(R.string.sort_date),t(R.string.sort_size)},(d,i)->{fileSort=i;renderFileEntries();}).show(),()->{fileAscending=!fileAscending;renderFileEntries();}});
        if(!selectedFiles.isEmpty())row(new int[]{R.string.download_selection,R.string.delete_selection},new Runnable[]{()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),19),this::deleteSelectedFiles});
        fileEntries=Collections.emptyList();
        fileList=column();content.addView(fileList);
        fileLoadPending=true;loadPendingFiles();
    }
    private void refreshFiles(){selectedFiles.clear();fileLoadPending=true;loadPendingFiles();}
    private void loadPendingFiles(){
        if(!fileLoadPending||model.busy||!model.connected||tab!=0||fileList==null)return;
        fileLoadPending=false;
        String requestedPath=path;LinearLayout requestedList=fileList;
        work(R.string.loading,()->model.files(requestedPath),entries->{if(model.connected&&tab==0&&fileList==requestedList&&path.equals(requestedPath)){fileEntries=entries;renderFileEntries();}});
    }
    private void deleteSelectedFiles(){if(selectedFiles.isEmpty())return;Map<String,Boolean> selection=new LinkedHashMap<>(selectedFiles);confirm(t(R.string.delete_selection),()->work(R.string.delete,()->{for(String file:selection.keySet())model.mutateFile("delete",file,null);return true;},v->{selectedFiles.clear();refreshFiles();}));}
    private void renderFileEntries(){if(fileList==null)return;fileList.removeAllViews();List<AdbFiles.Entry> visible=new ArrayList<>();for(AdbFiles.Entry entry:fileEntries)if(entry.name.toLowerCase(Locale.ROOT).contains(fileQuery.toLowerCase(Locale.ROOT)))visible.add(entry);visible.sort((a,b)->{if(a.directory!=b.directory)return a.directory?-1:1;int compare=fileSort==1?Long.compare(a.modifiedAt,b.modifiedAt):fileSort==2?Long.compare(a.size,b.size):a.name.compareToIgnoreCase(b.name);if(compare==0)compare=a.name.compareToIgnoreCase(b.name);return fileAscending?compare:-compare;});if(visible.isEmpty()){TextView empty=text(t(fileQuery.isEmpty()?R.string.folder_empty:R.string.no_search_results),14);empty.setGravity(Gravity.CENTER);fileList.addView(empty);return;}for(AdbFiles.Entry entry:visible){String remote=child(entry.name);LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(12),dp(8),dp(12),dp(8));item.setMinimumHeight(dp(66));item.setBackgroundResource(android.R.drawable.list_selector_background);TextView icon=text(entry.directory?"▣":iconFor(entry.name),20);icon.setTextColor(CYAN);icon.setGravity(Gravity.CENTER);item.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout labels=column();TextView name=text(entry.name,15);name.setPadding(dp(6),dp(2),dp(4),dp(2));TextView metadata=text(entry.directory?t(R.string.folder):android.text.format.Formatter.formatFileSize(this,entry.size)+" · "+android.text.format.DateFormat.getMediumDateFormat(this).format(new Date(entry.modifiedAt*1000)),11);metadata.setTextColor(Color.GRAY);metadata.setPadding(dp(6),0,dp(4),0);labels.addView(name);labels.addView(metadata);item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView check=text(selectedFiles.containsKey(remote)?"✓":"›",20);check.setTextColor(CYAN);item.addView(check);item.setOnClickListener(v->{if(!selectedFiles.isEmpty()){if(selectedFiles.containsKey(remote))selectedFiles.remove(remote);else selectedFiles.put(remote,entry.directory);render();}else if(entry.directory){path=remote;selectedFiles.clear();render();}else fileMenu(entry);});item.setOnLongClickListener(v->{if(selectedFiles.containsKey(remote))selectedFiles.remove(remote);else selectedFiles.put(remote,entry.directory);check.setText(selectedFiles.containsKey(remote)?"✓":"›");return true;});fileList.addView(item);View divider=new View(this);divider.setBackgroundColor(CYAN&0x22444444);fileList.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}}
    private String iconFor(String name){String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);if(Arrays.asList("apk","apks","xapk","apkm").contains(ext))return "⬇";if(Arrays.asList("jpg","jpeg","png","gif","webp").contains(ext))return "▧";if(Arrays.asList("mp4","mkv","mov").contains(ext))return "▶";if(Arrays.asList("zip","7z","rar","tar","gz").contains(ext))return "▤";return "▧";}
    private String child(String name) {if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.indexOf(0)>=0) throw new IllegalArgumentException(t(R.string.invalid_name));return (path.equals("/")?"/":path+"/")+name;}
    private void uploadChoice(){new AlertDialog.Builder(this).setTitle(R.string.upload).setItems(new String[]{t(R.string.upload),t(R.string.upload_folder)},(d,i)->{if(i==0)pick(10,"*/*");else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),16);}).show();}
    private void fileMenu(AdbFiles.Entry entry) {
        String selected=child(entry.name);String[] choices=entry.directory?new String[]{t(R.string.open),t(R.string.download_folder),t(R.string.rename),t(R.string.delete)}:new String[]{t(R.string.download),t(R.string.rename),t(R.string.delete)};
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(choices,(d,i)->{
            if(entry.directory&&i==0){path=selected;selectedFiles.clear();render();return;}
            if(i==0||entry.directory&&i==1){pendingDownload=selected;if(entry.directory)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),17);else create(entry.name,"application/octet-stream",11);return;}
            if(i==(entry.directory?2:1))nameDialog(R.string.rename,entry.name,name->work(R.string.rename,()->model.mutateFile("rename",selected,child(name)),v->render()));
            else confirm(t(R.string.delete)+" "+entry.name,()->work(R.string.delete,()->model.mutateFile("delete",selected,null),v->render()));
        }).show();
    }
    private void nameDialog(int title,String initial,java.util.function.Consumer<String> action) {EditText input=field(title,initial);new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.confirm,(d,w)->{try{action.accept(input.getText().toString());}catch(Exception e){error(t(R.string.invalid_name));}}).show();}
    private void pick(int request,String mime) {startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,request==12||request==10).addCategory(Intent.CATEGORY_OPENABLE),request);}
    private void create(String name,String mime,int request) {startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).putExtra(Intent.EXTRA_TITLE,name).addCategory(Intent.CATEGORY_OPENABLE),request);}
    private String documentName(Uri uri) {try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}return "upload.bin";}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(!UsageConsent.accepted(this))return;if(result!=RESULT_OK||data==null)return;
        if(request==22||(request==13&&data.hasExtra("aerial_url"))){try{java.net.URI endpoint=Endpoints.aerial(data.getStringExtra("aerial_url"));startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(endpoint.toASCIIString())).addCategory(Intent.CATEGORY_BROWSABLE));}catch(Exception error){error(t(R.string.aerial_open_failed));}return;}
        if(request==13) {connect(data.getStringExtra("host"),data.getIntExtra("port",5555),null,0);return;}
        if(request==12) {List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());if(uris.size()==1)inspectPackage(uris.get(0));else work(R.string.install,()->model.install(uris),out->{model.rememberInstall("APK × "+uris.size(),out);if(tab==1)render();output(t(R.string.install),out);});return;}
        if(request==10){List<Uri> sources=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)sources.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)sources.add(data.getData());String folder=path;confirm(t(R.string.replace_files),()->work(R.string.upload,()->{Transfers transfers=new Transfers(model);for(Uri source:sources){String name=Transfers.safeName(documentName(source));transfers.upload(source,folder+(folder.equals("/")?"":"/")+name,true);}return true;},ok->render()));return;}
        Uri uri=data.getData();if(uri==null)return;
        if(request==11) {String remote=pendingDownload;work(R.string.download,()->{new Transfers(model).download(remote,uri);return true;},ok->Toast.makeText(this,R.string.completed,0).show());}
        if(request==16){String folder=path;confirm(t(R.string.replace_files),()->work(R.string.upload_folder,()->{new Transfers(model).uploadTree(uri,folder);return true;},ok->render()));}
        if(request==17){String source=pendingDownload;confirm(t(R.string.replace_files),()->work(R.string.download_folder,()->{new Transfers(model).downloadTree(source,uri);return true;},ok->Toast.makeText(this,R.string.completed,0).show()));}
        if(request==19){Map<String,Boolean> selection=new LinkedHashMap<>(selectedFiles);confirm(t(R.string.replace_files),()->work(R.string.download_selection,()->{new Transfers(model).downloadSelection(selection,uri);return true;},ok->{selectedFiles.clear();render();}));}
        if(request==15) {List<String> paths=new ArrayList<>(pendingApkPaths);work(R.string.export_apk,()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){model.exportApks(paths,out);}return true;},ok->Toast.makeText(this,R.string.completed,0).show());}
        if(request==18) {String logs=filteredLogs();work(R.string.save,()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(logs.getBytes(java.nio.charset.StandardCharsets.UTF_8));}return true;},v->Toast.makeText(this,R.string.completed,0).show());}
        if(request==14) {byte[] image=lastScreenshot;work(R.string.save,()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(image);}return true;},v->Toast.makeText(this,R.string.completed,0).show());}
    }
    private void inspectPackage(Uri uri){
        work(R.string.apk_analysis,()->{InstallBundle bundle=new InstallBundle(this,uri);try{bundle.report=bundle.analysis();return bundle;}catch(Exception error){bundle.close();throw error;}},bundle->{TextView details=text(bundle.report,13);details.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(details);final boolean[] installing={false};AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.apk_analysis).setView(scroll).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.install,(d,w)->{installing[0]=true;work(R.string.install,()->{try{return model.installBundle(bundle);}finally{bundle.close();}},out->{if(tab==1)render();output(t(R.string.install),out);});}).create();dialog.setOnDismissListener(d->{if(!installing[0])bundle.close();});showAlignedDialog(dialog);});
    }
    private void apps() {
        EditText search=searchField(R.string.search,appQuery);content.addView(search);
        segmented(new int[]{R.string.app_filter_user,R.string.app_filter_system,R.string.app_filter_all},appFilter,new Runnable[]{()->{appFilter=0;render();},()->{appFilter=1;render();},()->{appFilter=2;render();}});
        LinearLayout installBanner=card();installBanner.setOrientation(0);installBanner.setGravity(Gravity.CENTER_VERTICAL);TextView packageIcon=text("▣",24);packageIcon.setTextColor(Color.WHITE);packageIcon.setGravity(Gravity.CENTER);packageIcon.setBackground(rounded(CYAN,Color.TRANSPARENT,dp(15)));installBanner.addView(packageIcon,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout installLabels=column();TextView installTitle=text(t(R.string.install),16);installTitle.setPadding(0,0,0,0);installTitle.setTypeface(null,Typeface.BOLD);TextView installHint=text(t(R.string.install_formats_hint),12);installHint.setPadding(0,0,0,0);installHint.setTextColor(Color.GRAY);installLabels.addView(installTitle);installLabels.addView(installHint);installBanner.addView(installLabels,new LinearLayout.LayoutParams(0,-2,1));TextView addPackage=text("+",28);addPackage.setTextColor(CYAN);installBanner.addView(addPackage);installBanner.setOnClickListener(v->pick(12,"*/*"));content.addView(installBanner);
        LinearLayout page=content;int filter=appFilter;String token=model.devices.token();
        pendingAppLoad=()->work(R.string.loading,()->model.apps(filter),out->{if(tab!=1||content!=page||!token.equals(model.devices.token()))return;
            LinearLayout appList=card();
            Map<String,TextView> buttons=new LinkedHashMap<>();Map<String,ImageView> icons=new LinkedHashMap<>();Map<String,LinearLayout> rows=new LinkedHashMap<>();
            for(String line:out.split("\\r?\\n")) {int split=line.lastIndexOf('=');String pkg=split>=0?line.substring(split+1):line.replace("package:","");if(pkg.isEmpty())continue;
                LinearLayout appRow=new LinearLayout(this);appRow.setGravity(Gravity.CENTER_VERTICAL);appRow.setPadding(dp(10),dp(7),dp(6),dp(7));appRow.setMinimumHeight(dp(68));appRow.setBackgroundResource(android.R.drawable.list_selector_background);ImageView icon=new ImageView(this);icon.setImageResource(android.R.drawable.sym_def_app_icon);appRow.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout labels=column();TextView title=text(pkg,14);title.setPadding(dp(10),dp(2),dp(4),dp(2));TextView packageName=text(pkg,10);packageName.setTextColor(Color.GRAY);packageName.setPadding(dp(10),0,dp(4),0);labels.addView(title);labels.addView(packageName);appRow.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView more=text("⋮",20);more.setTextColor(CYAN);more.setGravity(Gravity.CENTER);more.setOnClickListener(v->appMenu(pkg));appRow.addView(more,new LinearLayout.LayoutParams(dp(40),dp(48)));appRow.setOnClickListener(v->appMenu(pkg));appList.addView(appRow);View divider=new View(this);divider.setBackgroundColor(CYAN&0x22444444);LinearLayout.LayoutParams dividerParams=new LinearLayout.LayoutParams(-1,dp(1));dividerParams.leftMargin=dp(56);appList.addView(divider,dividerParams);buttons.put(pkg,title);icons.put(pkg,icon);rows.put(pkg,appRow);
            }
            TextView empty=text(t(R.string.no_apps_found),14);empty.setGravity(Gravity.CENTER);appList.addView(empty);
            content.addView(appList);
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int start,int before,int count){appQuery=s.toString();empty.setVisibility(filterAppRows(rows,model.presentations,appQuery)==0?View.VISIBLE:View.GONE);}public void afterTextChanged(Editable s){}});
            empty.setVisibility(filterAppRows(rows,model.presentations,appQuery)==0?View.VISIBLE:View.GONE);
            if(!preferences.getBoolean("load-icons",true))return;
            List<String> packages=new ArrayList<>(buttons.keySet());
            if(!packages.isEmpty())work(R.string.loading,()->model.loadPresentations(packages.subList(0,Math.min(40,packages.size()))),presentations->{if(tab!=1||content!=page||!token.equals(model.devices.token()))return;applyAppPresentations(buttons,icons,presentations);empty.setVisibility(filterAppRows(rows,model.presentations,appQuery)==0?View.VISIBLE:View.GONE);});
        });
        loadPendingApps();
    }
    private void loadPendingApps(){
        if(pendingAppLoad==null||model.busy||!model.connected||tab!=1)return;
        Runnable load=pendingAppLoad;pendingAppLoad=null;load.run();
    }
    static int filterAppRows(Map<String,LinearLayout> rows,Map<String,DeckModel.AppPresentation> presentations,String query){
        String q=query.toLowerCase(Locale.ROOT);
        int count=0;
        for(Map.Entry<String,LinearLayout> entry:rows.entrySet()){
            DeckModel.AppPresentation app=presentations.get(entry.getKey());
            boolean visible=entry.getKey().toLowerCase(Locale.ROOT).contains(q)||app!=null&&app.name.toLowerCase(Locale.ROOT).contains(q);
            if(visible)count++;
            LinearLayout row=entry.getValue();row.setVisibility(visible?View.VISIBLE:View.GONE);
            ViewGroup parent=(ViewGroup)row.getParent();parent.getChildAt(parent.indexOfChild(row)+1).setVisibility(visible?View.VISIBLE:View.GONE);
        }
        return count;
    }
    static void applyAppPresentations(Map<String,TextView> titles,Map<String,ImageView> icons,Map<String,DeckModel.AppPresentation> presentations){
        for(String pkg:titles.keySet()){
            DeckModel.AppPresentation app=presentations.get(pkg);if(app==null)continue;
            TextView title=titles.get(pkg);title.setText(app.name);
            if(!app.version.isEmpty())title.setContentDescription(pkg+" · v"+app.version);
            Bitmap icon=BitmapFactory.decodeByteArray(app.icon,0,app.icon.length);if(icon!=null)icons.get(pkg).setImageBitmap(icon);
        }
    }
    private void appMenu(String pkg) {
        int[] labels={R.string.app_details,R.string.launch,R.string.force_stop,R.string.clear_cache,R.string.clear_data,R.string.uninstall,R.string.export_apk};
        String[] choices=Arrays.stream(labels).mapToObj(this::t).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(pkg).setItems(choices,(d,index)->{
            if(index==0){work(R.string.app_details,()->model.appDetails(pkg),this::showAppDetails);return;}
            if(index==6) {work(R.string.export_apk,()->model.appAction("paths",pkg),paths->{String[] files=paths.trim().split("\\r?\\n");if(files.length!=1){pendingApkPaths=new ArrayList<>();for(String file:files)if(file.startsWith("package:"))pendingApkPaths.add(file.substring(8));create(pkg+".zip","application/zip",15);return;}pendingDownload=files[0].replaceFirst("^package:","");create(pkg+".apk","application/vnd.android.package-archive",11);});return;}
            String action=new String[]{"details","launch","stop","cache","clear","uninstall"}[index];
            Runnable execute=()->work(labels[index],()->model.appAction(action,pkg),result->{if(index==0)output(t(labels[index]),result);else if(result.toLowerCase(Locale.ROOT).contains("failure")||result.toLowerCase(Locale.ROOT).contains("exception"))error(result);else{Toast.makeText(this,result.isEmpty()?t(R.string.completed):result,0).show();if(index==5&&tab==1&&model.connected)render();}});
            if(index>=2)confirm(t(labels[index])+" "+pkg,execute);else execute.run();
        }).show();
    }
    private void showAppDetails(DeckModel.AppDetails app){
        LinearLayout rows=column();rows.setPadding(dp(18),dp(8),dp(18),dp(8));
        appDetailRow(rows,R.string.app_name_label,app.name);
        appDetailRow(rows,R.string.package_name_label,app.packageName);
        appDetailRow(rows,R.string.app_type,app.system?t(R.string.app_filter_system):t(R.string.app_filter_user));
        if(!app.version.isEmpty())appDetailRow(rows,R.string.app_version,app.version);
        if(!app.path.isEmpty())appDetailRow(rows,R.string.apk_path_label,app.path);
        ScrollView scroll=new ScrollView(this);scroll.addView(rows);
        showAlignedDialog(new AlertDialog.Builder(this).setTitle(R.string.app_details).setView(scroll).setPositiveButton(R.string.close,null).create());
    }
    private void appDetailRow(LinearLayout rows,int label,String value){
        LinearLayout row=new LinearLayout(this);row.setOrientation(1);row.setPadding(0,dp(10),0,dp(10));
        TextView heading=text(t(label),12);heading.setTextColor(Color.GRAY);heading.setPadding(0,0,0,dp(3));row.addView(heading);
        TextView content=text(value,15);content.setTextIsSelectable(true);content.setPadding(0,0,0,0);row.addView(content);rows.addView(row);
        View divider=new View(this);divider.setBackgroundColor(CYAN&0x22444444);rows.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void key(int code) {model.inputWorker.execute(()->{try{model.key(code);}catch(Exception e){handler.post(()->error(e.getLocalizedMessage()));}});}
    private void remote() {
        String selectedMode=preferences.getString("remote-mode","screen");
        if(selectedMode.equals("buttons")){selectedMode="screen";preferences.edit().putString("remote-mode",selectedMode).apply();}
        LinearLayout device=card();device.setOrientation(0);device.setGravity(Gravity.CENTER_VERTICAL);TextView tv=text("▣",24);tv.setTextColor(Color.WHITE);tv.setGravity(Gravity.CENTER);tv.setBackground(rounded(CYAN,Color.TRANSPARENT,dp(15)));device.addView(tv,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout identity=column();TextView name=text(model.deviceName,16);name.setTypeface(null,Typeface.BOLD);LinearLayout connection=new LinearLayout(this);connection.setGravity(Gravity.CENTER_VERTICAL);View dot=new View(this);dot.setBackground(rounded(Color.rgb(52,199,89),Color.TRANSPARENT,dp(8)));LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(dp(8),dp(8));dotParams.leftMargin=dp(8);dotParams.rightMargin=dp(7);connection.addView(dot,dotParams);TextView connected=text(t(R.string.connected),12);connected.setTextColor(Color.GRAY);connected.setPadding(0,dp(8),dp(8),dp(8));connection.addView(connected);identity.addView(name);identity.addView(connection);device.addView(identity,new LinearLayout.LayoutParams(0,-2,1));TextView check=text("✓",22);check.setTextColor(CYAN);device.addView(check);content.addView(device);
        segmented(new int[]{R.string.remote_screen,R.string.mouse},selectedMode.equals("screen")?0:1,new Runnable[]{()->{preferences.edit().putString("remote-mode","screen").apply();render();},()->{preferences.edit().putString("remote-mode","mouse").apply();render();}});
        if(selectedMode.equals("screen")){
            previewPanel=column();
            previewViewport=new PreviewViewport(this,preferences.getString("live-screen-control","touch").equals("mouse"),new PreviewViewport.Listener(){
                public void playback(boolean start){if(start)startPreview();else stopPreview();}
                public void fullscreen(boolean full){setPreviewFullScreen(full);}
                public void mode(boolean mouse){preferences.edit().putString("live-screen-control",mouse?"mouse":"touch").apply();}
                public void orientation(int orientation){setRequestedOrientation(orientation);}
                public void controls(boolean expanded){layoutPreviewFloatingControls(expanded);}
                public void touch(int action,float x,float y,long time){previewTouch(action,x,y,time);}
            });
            PreviewViewport viewport=previewViewport;
            viewport.surface.getHolder().addCallback(new SurfaceHolder.Callback(){
                public void surfaceCreated(SurfaceHolder holder){if(preview&&viewport==previewViewport)startPreviewVideo(previewGeneration);}
                public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}
                public void surfaceDestroyed(SurfaceHolder holder){if(viewport==previewViewport&&videoPreview!=null){videoPreview.close();videoPreview=null;}}
            });
            previewPanel.addView(viewport,new LinearLayout.LayoutParams(-1,dp(380)));content.addView(previewPanel);
        }else mousePad();
    }
    private LinearLayout previewFloatingControls;
    private void remoteFloatingControls(FrameLayout page){
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);previewFloatingControls=controls;
        LinearLayout remote=new LinearLayout(this);remote.setGravity(Gravity.CENTER);remote.setPadding(dp(16),0,dp(16),0);remote.setBackground(rounded(darkMode?0xff35394a:0xffeef0f5,Color.TRANSPARENT,dp(29)));remote.setElevation(dp(8));remote.setContentDescription(t(R.string.remote_center));remote.setOnClickListener(v->remoteCenter());
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_remote_control);icon.setColorFilter(FG);remote.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(28)));
        TextView label=text(t(R.string.remote_center),14);label.setTextColor(FG);label.setTypeface(null,Typeface.BOLD);label.setPadding(dp(8),0,0,0);remote.addView(label);controls.addView(remote,new LinearLayout.LayoutParams(-2,dp(58)));
        TextView keyboard=text("⌨",25);keyboard.setTextColor(Color.WHITE);keyboard.setGravity(Gravity.CENTER);keyboard.setContentDescription(t(R.string.sync_keyboard));keyboard.setBackground(rounded(CYAN,Color.TRANSPARENT,dp(29)));keyboard.setElevation(dp(8));keyboard.setOnClickListener(v->keyboard());LinearLayout.LayoutParams keyParams=new LinearLayout.LayoutParams(dp(58),dp(58));keyParams.leftMargin=dp(12);controls.addView(keyboard,keyParams);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.BOTTOM);params.rightMargin=dp(18);params.bottomMargin=dp(18);page.addView(controls,params);
    }
    private void layoutPreviewFloatingControls(boolean expanded){if(previewFloatingControls==null)return;FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)previewFloatingControls.getLayoutParams();params.bottomMargin=dp(previewFullScreen&&expanded?86:18);previewFloatingControls.setLayoutParams(params);}
    private void remoteCenter(){
        LinearLayout panel=column();panel.setPadding(dp(12),dp(8),dp(12),dp(8));panel.setBackgroundColor(Color.rgb(18,23,39));
        remoteRow(panel,new int[]{R.string.power,R.string.remote_input,R.string.mute},new Runnable[]{()->key(26),()->key(178),()->key(164)});
        panel.addView(direction(R.string.up_key,19));
        LinearLayout directions=new LinearLayout(this);
        for(int code:new int[]{21,23,22}){Button b=direction(code==21?R.string.left:code==22?R.string.right:R.string.ok,code);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(60),1);params.setMargins(dp(6),dp(6),dp(6),dp(6));directions.addView(b,params);}
        panel.addView(directions);panel.addView(direction(R.string.down,20));
        remoteRow(panel,new int[]{R.string.back,R.string.home,R.string.menu},new Runnable[]{()->key(4),()->key(3),()->key(82)});
        LinearLayout adjustments=new LinearLayout(this);
        adjustments.addView(remoteAdjustColumn(R.string.remote_volume,R.string.volume_down,25,R.string.volume_up,24,true),new LinearLayout.LayoutParams(0,-2,1));
        adjustments.addView(remoteAdjustColumn(R.string.remote_channel,R.string.channel_down,167,R.string.channel_up,166,false),new LinearLayout.LayoutParams(0,-2,1));
        panel.addView(adjustments);
        remoteRow(panel,new int[]{R.string.search,R.string.settings,R.string.play_pause},new Runnable[]{()->key(84),()->key(176),()->key(85)});
        remoteRow(panel,new int[]{R.string.rewind,R.string.more_keys,R.string.forward},new Runnable[]{()->key(89),()->remoteNumberPad(),()->key(90)});
        for(int i=0;i<panel.getChildCount();i++)if(panel.getChildAt(i) instanceof Button){
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)panel.getChildAt(i).getLayoutParams();params.height=dp(60);params.setMargins(dp(6),dp(6),dp(6),dp(6));
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        FrameLayout overlay=new FrameLayout(this){@Override public boolean dispatchTouchEvent(MotionEvent event){boolean handled=super.dispatchTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)closeVolumeGesture();return handled;}};overlay.addView(scroll,new FrameLayout.LayoutParams(-1,-2));remoteOverlay=overlay;
        TextView heading=text(t(R.string.remote_center),22);heading.setTextColor(Color.WHITE);heading.setPadding(dp(24),dp(18),dp(12),dp(10));
        AlertDialog dialog=new AlertDialog.Builder(this).setCustomTitle(heading).setView(overlay).setPositiveButton(R.string.close,null).create();
        dialog.setOnDismissListener(d->{directionGeneration++;closeVolumeGesture();remoteOverlay=null;});showAlignedDialog(dialog);
        dialog.getWindow().setBackgroundDrawable(rounded(Color.rgb(18,23,39),0x22ffffff,dp(28)));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
    }
    private void remoteRow(LinearLayout panel,int[] titles,Runnable[] actions){
        LinearLayout row=new LinearLayout(this);row.setBaselineAligned(false);
        for(int i=0;i<titles.length;i++){Button item=remoteGlass(button(t(titles[i]),actions[i]));item.setTextSize(12);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(60),1);params.setMargins(dp(6),dp(6),dp(6),dp(6));row.addView(item,params);}
        panel.addView(row);
    }
    private LinearLayout remoteAdjustColumn(int title,int downTitle,int downCode,int upTitle,int upCode,boolean volume){
        LinearLayout column=column();column.setGravity(Gravity.CENTER);
        Button up=remoteGlass(volume?volumeButton(upTitle,upCode):channelButton(upTitle,upCode));
        Button down=remoteGlass(volume?volumeButton(downTitle,downCode):channelButton(downTitle,downCode));
        up.setText("+");down.setText("−");up.setTextSize(28);down.setTextSize(28);
        up.setContentDescription(t(upTitle));down.setContentDescription(t(downTitle));
        LinearLayout.LayoutParams keyParams=new LinearLayout.LayoutParams(-1,dp(60));keyParams.setMargins(dp(18),dp(5),dp(18),dp(5));column.addView(up,keyParams);
        TextView label=text(t(title),12);label.setTextColor(0xffc8d0e0);label.setPadding(0,0,0,0);label.setGravity(Gravity.CENTER);column.addView(label,new LinearLayout.LayoutParams(-1,dp(28)));
        LinearLayout.LayoutParams downParams=new LinearLayout.LayoutParams(-1,dp(60));downParams.setMargins(dp(18),dp(5),dp(18),dp(5));column.addView(down,downParams);
        return column;
    }
    private void closeVolumeGesture(){if(cancelVolumeGesture!=null){cancelVolumeGesture.run();cancelVolumeGesture=null;}if(remoteOverlay!=null&&remoteOverlay.getChildCount()>1)remoteOverlay.removeViews(1,remoteOverlay.getChildCount()-1);volumePopup=null;}
    private void remoteHaptic(View view,int type){if(preferences.getBoolean("remote-haptics",true))view.performHapticFeedback(type);}
    private String remoteVolumeError(Exception failure){return failure.getMessage()!=null&&failure.getMessage().contains("TV volume range unavailable")?t(R.string.volume_unsupported):failure.getLocalizedMessage();}
    private String remoteVolumeLabel(DeckModel.VolumeState state,int step){return "🔊 "+Math.round(100f*(step-state.min)/Math.max(1,state.max-state.min))+"%";}
    private Button channelButton(int title,int code){
        Button button=button(t(title),()->key(code));final boolean[] held={false},activated={false};final Runnable[] timer={null};final float[] down={0,0};
        button.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){held[0]=true;activated[0]=false;down[0]=event.getRawX();down[1]=event.getRawY();view.getParent().requestDisallowInterceptTouchEvent(true);
                timer[0]=()->{if(held[0]){activated[0]=true;remoteHaptic(view,HapticFeedbackConstants.LONG_PRESS);channelKeypad();}};
                handler.postDelayed(timer[0],280);return true;}
            if(action==MotionEvent.ACTION_MOVE&&!activated[0]&&(Math.abs(event.getRawX()-down[0])>dp(10)||Math.abs(event.getRawY()-down[1])>dp(10))){held[0]=false;handler.removeCallbacks(timer[0]);return true;}
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){boolean valid=held[0];held[0]=false;handler.removeCallbacks(timer[0]);
                if(action==MotionEvent.ACTION_UP&&valid&&!activated[0]){remoteHaptic(view,HapticFeedbackConstants.KEYBOARD_TAP);view.performClick();}return true;}
            return true;
        });return button;
    }
    private void channelKeypad(){
        LinearLayout panel=column();panel.setPadding(dp(16),dp(12),dp(16),dp(8));
        TextView number=text("",24),error=text("",12);number.setGravity(Gravity.CENTER);panel.addView(number);panel.addView(error);
        StringBuilder digits=new StringBuilder();GridLayout grid=new GridLayout(this);grid.setColumnCount(3);panel.addView(grid);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.remote_channel).setView(panel).setNegativeButton(R.string.close,(d,w)->{}).create();
        Button[] confirm={null};Runnable refresh=()->{number.setText(digits.length()==0?t(R.string.channel_empty):digits.toString());if(confirm[0]!=null)confirm[0].setEnabled(digits.length()>0);};
        for(int value=1;value<=9;value++){final int digit=value;Button key=button(String.valueOf(value),()->{if(digits.length()<4){digits.append(digit);error.setText("");refresh.run();remoteHaptic(grid,HapticFeedbackConstants.KEYBOARD_TAP);}});grid.addView(key,remoteGridCell());}
        grid.addView(button(t(R.string.remote_delete),()->{if(digits.length()>0){digits.deleteCharAt(digits.length()-1);refresh.run();remoteHaptic(grid,HapticFeedbackConstants.KEYBOARD_TAP);}}),remoteGridCell());
        grid.addView(button("0",()->{if(digits.length()<4){digits.append('0');refresh.run();remoteHaptic(grid,HapticFeedbackConstants.KEYBOARD_TAP);}}),remoteGridCell());
        confirm[0]=button(t(R.string.keyboard_confirm),()->{String target=digits.toString();if(target.isEmpty())return;confirm[0].setEnabled(false);
            model.inputWorker.execute(()->{try{model.tuneChannel(target);handler.post(()->{if(dialog.isShowing())dialog.dismiss();});}
            catch(Exception failure){handler.post(()->{if(dialog.isShowing()){error.setText(failure.getLocalizedMessage());refresh.run();}});}});});
        grid.addView(confirm[0],remoteGridCell());refresh.run();showAlignedDialog(dialog);
    }
    private Button volumeButton(int title,int code){
        Button button=button(t(title),()->key(code));final boolean[] held={false},activated={false};final float[] startY={0},lastY={0},downX={0};
        final DeckModel.VolumeState[] state={null};final AtomicInteger target=new AtomicInteger(),sent=new AtomicInteger(Integer.MIN_VALUE);
        final int[] hapticStep={0},boundary={Integer.MIN_VALUE};final long[] lastTick={0};
        final AtomicBoolean sending=new AtomicBoolean();final AtomicInteger generation=new AtomicInteger();final Runnable[] timer={null},sendLatest={null};final TextView value=text("",16);
        Runnable update=()->{if(state[0]!=null)value.setText(remoteVolumeLabel(state[0],target.get()));};
        sendLatest[0]=()->{if(!sending.compareAndSet(false,true))return;int session=generation.get();model.inputWorker.execute(()->{
            try{while(session==generation.get()&&sent.get()!=target.get()&&state[0]!=null){int next=target.get();DeckModel.VolumeState confirmed=model.setVolume(next);sent.set(next);
                    handler.post(()->{if(held[0]&&state[0]!=null){value.setText(remoteVolumeLabel(confirmed,confirmed.current));}});}}
            catch(Exception failure){handler.post(()->error(remoteVolumeError(failure)));}
            finally{sending.set(false);if(sent.get()!=target.get()&&state[0]!=null)handler.post(sendLatest[0]);}
        });};
        Runnable updatePosition=()->{if(!activated[0]||state[0]==null)return;int range=state[0].max-state[0].min;
            int next=Math.max(state[0].min,Math.min(state[0].max,state[0].current+Math.round((startY[0]-lastY[0])*range/dp(200))));
            if(next!=target.get()){
                if(next==state[0].min||next==state[0].max){if(boundary[0]!=next)remoteHaptic(button,HapticFeedbackConstants.LONG_PRESS);boundary[0]=next;}
                else{boundary[0]=Integer.MIN_VALUE;long now=SystemClock.uptimeMillis();if(Math.abs(next-hapticStep[0])>=4&&now-lastTick[0]>=100){remoteHaptic(button,HapticFeedbackConstants.CLOCK_TICK);hapticStep[0]=next;lastTick[0]=now;}}
                target.set(next);update.run();if(value.getTag() instanceof View[]){View[] parts=(View[])value.getTag();int fillHeight=Math.max(0,dp(200)*(next-state[0].min)/Math.max(1,range));FrameLayout.LayoutParams fillParams=(FrameLayout.LayoutParams)parts[0].getLayoutParams();fillParams.height=fillHeight;parts[0].setLayoutParams(fillParams);FrameLayout.LayoutParams knobParams=(FrameLayout.LayoutParams)parts[1].getLayoutParams();knobParams.bottomMargin=dp(5)+Math.max(0,fillHeight-dp(12));parts[1].setLayoutParams(knobParams);}sendLatest[0].run();}};
        button.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){closeVolumeGesture();generation.incrementAndGet();held[0]=true;activated[0]=false;state[0]=null;sent.set(Integer.MIN_VALUE);
                startY[0]=lastY[0]=event.getRawY();downX[0]=event.getRawX();view.getParent().requestDisallowInterceptTouchEvent(true);
                cancelVolumeGesture=()->{generation.incrementAndGet();held[0]=false;state[0]=null;if(timer[0]!=null)handler.removeCallbacks(timer[0]);};
                final float popupY=lastY[0];timer[0]=()->{if(!held[0])return;activated[0]=true;startY[0]=lastY[0];remoteHaptic(view,HapticFeedbackConstants.LONG_PRESS);
                        LinearLayout popup=column();popup.setGravity(Gravity.CENTER_HORIZONTAL);
                        if(value.getParent() instanceof ViewGroup)((ViewGroup)value.getParent()).removeView(value);
                        value.setTextColor(Color.WHITE);value.setGravity(Gravity.CENTER);value.setPadding(dp(4),dp(6),dp(4),dp(6));value.setBackground(rounded(Color.rgb(51,59,78),0x55ffffff,dp(16)));
                        value.setText("…");
                        popup.addView(value,new LinearLayout.LayoutParams(-1,dp(42)));
                        FrameLayout track=new FrameLayout(this);GradientDrawable trackBackground=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(58,67,89),Color.rgb(34,42,61)});trackBackground.setCornerRadius(dp(26));trackBackground.setStroke(dp(1),0x55ffffff);track.setBackgroundDrawable(trackBackground);
                        LinearLayout.LayoutParams trackParams=new LinearLayout.LayoutParams(dp(52),dp(210));trackParams.topMargin=dp(10);popup.addView(track,trackParams);
                        View fill=new View(this);GradientDrawable fillBackground=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.rgb(79,140,255),Color.rgb(148,143,249)});fillBackground.setCornerRadius(dp(22));fill.setBackground(fillBackground);
                        FrameLayout.LayoutParams fillParams=new FrameLayout.LayoutParams(dp(42),0,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);fillParams.bottomMargin=dp(5);track.addView(fill,fillParams);
                        View knob=new View(this);knob.setBackground(rounded(Color.WHITE,Color.TRANSPARENT,dp(12)));knob.setElevation(dp(4));FrameLayout.LayoutParams knobParams=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);knobParams.bottomMargin=dp(5);track.addView(knob,knobParams);knob.setVisibility(View.INVISIBLE);
                        if(remoteOverlay!=null){int[] location=new int[2],origin=new int[2];view.getLocationOnScreen(location);remoteOverlay.getLocationOnScreen(origin);
                            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(100),dp(266));params.leftMargin=Math.max(0,Math.min(remoteOverlay.getWidth()-dp(100),location[0]-origin[0]+dp(90)));params.topMargin=Math.max(0,Math.min(remoteOverlay.getHeight()-dp(266),(int)popupY-origin[1]-dp(252)));
                            remoteOverlay.addView(popup,params);volumePopup=popup;}
                        value.setTag(new View[]{fill,knob});
                    model.inputWorker.execute(()->{try{DeckModel.VolumeState read=model.volumeState();handler.post(()->{if(!held[0]||popup.getParent()==null)return;
                        state[0]=read;target.set(read.current);sent.set(read.current);hapticStep[0]=read.current;boundary[0]=Integer.MIN_VALUE;startY[0]=lastY[0];update.run();
                        int fillHeight=Math.max(0,dp(200)*(read.current-read.min)/Math.max(1,read.max-read.min));FrameLayout.LayoutParams currentFill=(FrameLayout.LayoutParams)fill.getLayoutParams();currentFill.height=fillHeight;fill.setLayoutParams(currentFill);
                        FrameLayout.LayoutParams currentKnob=(FrameLayout.LayoutParams)knob.getLayoutParams();currentKnob.bottomMargin=dp(5)+Math.max(0,fillHeight-dp(12));knob.setLayoutParams(currentKnob);knob.setVisibility(View.VISIBLE);
                    });}catch(Exception failure){handler.post(()->{closeVolumeGesture();error(remoteVolumeError(failure));});}});
                };handler.postDelayed(timer[0],280);return true;}
            if(action==MotionEvent.ACTION_MOVE){lastY[0]=event.getRawY();if(!activated[0]&&(Math.abs(event.getRawX()-downX[0])>dp(10)||Math.abs(lastY[0]-startY[0])>dp(10))){held[0]=false;handler.removeCallbacks(timer[0]);return true;}updatePosition.run();return true;}
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){boolean tap=action==MotionEvent.ACTION_UP&&held[0]&&!activated[0];held[0]=false;handler.removeCallbacks(timer[0]);
                if(tap){remoteHaptic(view,HapticFeedbackConstants.KEYBOARD_TAP);view.performClick();}
                if(action==MotionEvent.ACTION_CANCEL){generation.incrementAndGet();state[0]=null;}
                else if(activated[0]&&state[0]!=null&&target.get()!=sent.get())sendLatest[0].run();
                cancelVolumeGesture=null;if(volumePopup!=null){if(volumePopup.getParent() instanceof ViewGroup)((ViewGroup)volumePopup.getParent()).removeView(volumePopup);volumePopup=null;}return true;}
            return true;
        });return button;
    }
    private void remoteNumberPad(){
        LinearLayout panel=column();GridLayout grid=new GridLayout(this);grid.setColumnCount(3);
        for(int digit=1;digit<=9;digit++)addRemoteDigit(grid,digit);
        Button delete=remoteGlass(button(t(R.string.remote_delete),()->key(67)));grid.addView(delete,remoteGridCell());
        addRemoteDigit(grid,0);Button enter=remoteGlass(button(t(R.string.keyboard_confirm),()->key(66)));grid.addView(enter,remoteGridCell());panel.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        showAlignedDialog(new AlertDialog.Builder(this).setTitle(R.string.number_pad).setView(panel).setPositiveButton(R.string.close,null).create());
    }
    private GridLayout.LayoutParams remoteGridCell(){GridLayout.LayoutParams params=new GridLayout.LayoutParams();params.width=0;params.height=dp(60);params.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,GridLayout.FILL,1f);params.rowSpec=GridLayout.spec(GridLayout.UNDEFINED,GridLayout.TOP);params.setMargins(dp(6),dp(6),dp(6),dp(6));return params;}
    private void addRemoteDigit(GridLayout grid,int digit){Button number=remoteGlass(button(String.valueOf(digit),()->key(digit==0?7:digit+7)));number.setTextSize(20);grid.addView(number,remoteGridCell());}
    private Button direction(int title,int code) {
        Button button=remoteGlass(button(t(title),()->key(code)));if(code==23)return button;
        final AtomicBoolean held=new AtomicBoolean(),repeated=new AtomicBoolean();final long[] token={0};
        button.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);held.set(true);repeated.set(false);token[0]=++directionGeneration;
                handler.postDelayed(new Runnable(){public void run(){
                    if(!held.get()||token[0]!=directionGeneration)return;repeated.set(true);
                    model.inputWorker.execute(()->{try {if(held.get()&&token[0]==directionGeneration)model.key(code);}catch(Exception e){handler.post(()->error(e.getLocalizedMessage()));held.set(false);}finally{handler.postDelayed(this,65);}});
                }},350);return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                held.set(false);directionGeneration++;if(event.getActionMasked()==MotionEvent.ACTION_UP&&!repeated.get())view.performClick();return true;
            }
            return true;
        });return button;
    }
    private void mousePad() {
        mousePad=new MousePad();
        content.addView(mousePad.body);
        if(resumed)mousePad.start();
    }

    static int mouseSwipeKey(float dx,float dy,float threshold) {
        float x=Math.abs(dx),y=Math.abs(dy);
        if(Math.max(x,y)<threshold||Math.max(x,y)<Math.min(x,y)*1.25f)return 0;
        if(x>y)return dx>0?KeyEvent.KEYCODE_DPAD_RIGHT:KeyEvent.KEYCODE_DPAD_LEFT;
        return dy>0?KeyEvent.KEYCODE_DPAD_UP:KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private final class MousePad {
        final LinearLayout body=card();
        final TextView pad=text(t(R.string.mouse_help),16);
        final Button drag=button(t(R.string.drag),this::toggleDrag);
        final GestureDetector taps;
        AlertDialog actionsDialog;
        final AtomicInteger moveX=new AtomicInteger(),moveY=new AtomicInteger();
        final AtomicBoolean movePending=new AtomicBoolean();
        volatile int generation,repeatGeneration;
        boolean active,ready,multi,moved;
        int direction,firstPointer,secondPointer;
        float startX,startY,lastX,lastY,padX=.5f,padY=.5f;
        // Mouse transport and drag state belong to inputWorker.
        boolean dragging;
        int[] touchScreen;

        MousePad() {
            pad.setGravity(Gravity.CENTER);
            pad.setBackground(rounded(darkMode?Color.rgb(25,32,58):Color.rgb(235,235,242),CYAN&0x22444444,dp(22)));
            pad.setOnClickListener(v->click(1));
            taps=new GestureDetector(MainActivity.this,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent event){return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent event){
                    if(!multi&&!moved&&ready)pad.performClick();
                    return true;
                }
                @Override public boolean onDoubleTapEvent(MotionEvent event){
                    if(event.getActionMasked()==MotionEvent.ACTION_UP&&!multi&&!moved&&ready)click(2);
                    return true;
                }
            });
            taps.setIsLongpressEnabled(false);
            pad.setOnTouchListener(this::touch);
            body.addView(pad,new LinearLayout.LayoutParams(-1,dp(300)));
            LinearLayout actions=new LinearLayout(MainActivity.this);
            Button center=button(t(R.string.center_pointer),()->{
                padX=padY=.5f;
                run(()->{if(touchScreen==null){model.centerMousePad();updateDrag(false);}});
            });
            Button more=button(t(R.string.more),this::more);
            for(Button button:new Button[]{center,drag,more})
                actions.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
            body.addView(actions);
            TextView hint=text(t(R.string.mouse_two_finger_navigation),12);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(8),dp(6),dp(8),dp(8));
            body.addView(hint);
        }

        void start() {
            if(active||!model.connected)return;
            active=true;
            ready=false;
            padX=padY=.5f;
            int session=++generation;
            model.inputWorker.execute(()->{
                if(session!=generation||!model.connected)return;
                try {
                    touchScreen=null;
                    dragging=false;
                    model.stopMouse();
                    try {model.prepareMouse();}
                    catch(Exception unavailable){touchScreen=model.screenSize();}
                    boolean coordinate=touchScreen!=null;
                    handler.post(()->{
                        if(session!=generation)return;
                        ready=true;
                        pad.setText(coordinate?R.string.mouse_coordinate_help:R.string.mouse_help);
                        drag.setVisibility(coordinate?View.GONE:View.VISIBLE);
                        drag.setText(R.string.drag);
                    });
                } catch(Exception failure){report(session,failure);}
            });
        }

        void stop() {
            if(!active)return;
            active=false;
            ready=false;
            generation++;
            repeatGeneration++;
            moveX.set(0);
            moveY.set(0);
            cancelTap();
            if(actionsDialog!=null)actionsDialog.dismiss();
            model.inputWorker.execute(()->{model.stopMouse();dragging=false;touchScreen=null;});
        }

        boolean touch(View view,MotionEvent event) {
            if(!ready||!model.connected)return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    repeatGeneration++;
                    multi=moved=false;
                    direction=0;
                    startX=lastX=event.getX();
                    startY=lastY=event.getY();
                    taps.onTouchEvent(event);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if(event.getPointerCount()==2&&!multi){
                        firstPointer=event.getPointerId(0);
                        secondPointer=event.getPointerId(1);
                        startX=(event.getX(0)+event.getX(1))/2;
                        startY=(event.getY(0)+event.getY(1))/2;
                    }else{
                        direction=-1;
                        repeatGeneration++;
                    }
                    multi=true;
                    cancelTap();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if(multi){
                        if(direction!=0||event.getPointerCount()!=2)break;
                        int first=event.findPointerIndex(firstPointer),second=event.findPointerIndex(secondPointer);
                        if(first<0||second<0)break;
                        float x=(event.getX(first)+event.getX(second))/2;
                        float y=(event.getY(first)+event.getY(second))/2;
                        direction=mouseSwipeKey(x-startX,y-startY,dp(14));
                        if(direction==KeyEvent.KEYCODE_DPAD_LEFT||direction==KeyEvent.KEYCODE_DPAD_RIGHT){
                            remoteHaptic(view,HapticFeedbackConstants.CLOCK_TICK);
                            int code=direction,session=generation,token=++repeatGeneration;
                            run(()->{
                                model.key(code);
                                handler.postDelayed(()->repeat(code,session,token,1),100);
                            });
                        }else if(direction!=0){
                            int code=direction;
                            run(()->model.key(code));
                        }
                    }else{
                        float x=event.getX(),y=event.getY();
                        if(!moved&&Math.hypot(x-startX,y-startY)<dp(3))break;
                        moved=true;
                        cancelTap();
                        position(x,y);
                        moveX.addAndGet(Math.round((x-lastX)*2));
                        moveY.addAndGet(Math.round((y-lastY)*2));
                        lastX=x;
                        lastY=y;
                        drainMoves();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    direction=-1;
                    repeatGeneration++;
                    break;
                case MotionEvent.ACTION_UP:
                    repeatGeneration++;
                    if(!multi&&!moved){
                        position(event.getX(),event.getY());
                        taps.onTouchEvent(event);
                    }
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    repeatGeneration++;
                    cancelTap();
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:break;
            }
            return true;
        }

        void position(float x,float y){
            padX=Math.max(0,Math.min(1,x/Math.max(1,pad.getWidth())));
            padY=Math.max(0,Math.min(1,y/Math.max(1,pad.getHeight())));
        }

        void cancelTap(){
            long now=SystemClock.uptimeMillis();
            MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
            taps.onTouchEvent(cancel);
            cancel.recycle();
        }

        void drainMoves(){
            if(!movePending.compareAndSet(false,true))return;
            int session=generation;
            model.inputWorker.execute(()->{
                try{
                    int x=moveX.getAndSet(0),y=moveY.getAndSet(0);
                    if(session==generation&&model.connected&&touchScreen==null&&(x!=0||y!=0)){
                        try {model.mouse("M "+x+" "+y);}
                        catch(Exception unavailable){useCoordinateTouch(session);}
                    }
                }catch(Exception failure){report(session,failure);}
                finally{
                    movePending.set(false);
                    handler.post(()->{if(ready&&(moveX.get()!=0||moveY.get()!=0))drainMoves();});
                }
            });
        }

        void repeat(int key,int session,int token,int index){
            if(!ready||session!=generation||token!=repeatGeneration)return;
            model.inputWorker.execute(()->{
                if(session!=generation||token!=repeatGeneration||!model.connected)return;
                try{
                    model.key(key);
                    long delay=index<5?90:index<13?55:35;
                    handler.postDelayed(()->repeat(key,session,token,index+1),delay);
                }catch(Exception failure){report(session,failure);}
            });
        }

        void click(int count){
            float x=padX,y=padY;
            int session=generation;
            run(()->{
                if(dragging){
                    model.mouse("U 1");
                    updateDrag(false);
                    if(count==1)return;
                }
                for(int i=0;i<count&&session==generation;i++){
                    if(i>0)Thread.sleep(120);
                    if(session!=generation||!model.connected)return;
                    if(touchScreen==null){
                        try{model.mouse("C");}
                        catch(Exception failure){
                            useCoordinateTouch(session);
                            throw failure;
                        }
                    }else{
                        int px=Math.round(x*(touchScreen[0]-1)),py=Math.round(y*(touchScreen[1]-1));
                        if(!model.mousePadTap(px,py))model.key(KeyEvent.KEYCODE_DPAD_CENTER);
                    }
                }
            });
        }

        void toggleDrag(){
            run(()->{
                if(touchScreen!=null)return;
                model.mouse(dragging?"U 1":"D 1");
                updateDrag(!dragging);
            });
        }

        void updateDrag(boolean held){
            dragging=held;
            int session=generation;
            handler.post(()->{if(session==generation)drag.setText(held?R.string.release_drag:R.string.drag);});
        }

        void more(){
            int[] labels={R.string.double_click,R.string.right_click,R.string.middle_click,R.string.scroll_up,R.string.scroll_down};
            String[] titles=new String[labels.length];
            for(int i=0;i<labels.length;i++)titles[i]=t(labels[i]);
            String[] commands={null,"C 2","C 4","W 1","W -1"};
            int[] fallback={0,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_HOME,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN};
            if(!ready)return;
            actionsDialog=new AlertDialog.Builder(MainActivity.this).setTitle(R.string.more).setItems(titles,(dialog,which)->{
                dialog.dismiss();
                if(which==0){click(2);return;}
                int session=generation;
                run(()->{
                    if(touchScreen==null){
                        try{
                            if(which<=2&&dragging){model.mouse("U 1");updateDrag(false);}
                            model.mouse(commands[which]);
                            return;
                        }catch(Exception unavailable){useCoordinateTouch(session);}
                    }
                    if(session==generation&&model.connected)model.key(fallback[which]);
                });
            }).create();
            actionsDialog.setOnDismissListener(dialog->actionsDialog=null);
            showAlignedDialog(actionsDialog);
        }

        void useCoordinateTouch(int session) throws Exception {
            model.stopMouse();
            touchScreen=model.screenSize();
            updateDrag(false);
            handler.post(()->{
                if(session!=generation)return;
                pad.setText(R.string.mouse_coordinate_help);
                drag.setVisibility(View.GONE);
            });
        }

        void run(MouseAction action){
            if(!ready||!model.connected)return;
            int session=generation;
            model.inputWorker.execute(()->{
                if(session!=generation||!model.connected)return;
                try{action.run();}
                catch(Exception failure){report(session,failure);}
            });
        }

        void report(int session,Exception failure){
            handler.post(()->{if(session==generation)error(failure.getLocalizedMessage());});
        }
    }

    private interface MouseAction {void run() throws Exception;}

    private void drainMouse() {if(!mouseSending.compareAndSet(false,true))return;model.inputWorker.execute(()->{try{while(true){int x=dx.getAndSet(0),y=dy.getAndSet(0);if(x==0&&y==0)break;model.mouse("M "+x+" "+y);}}catch(Exception e){dx.set(0);dy.set(0);handler.post(()->error(e.getLocalizedMessage()));}finally{mouseSending.set(false);if(dx.get()!=0||dy.get()!=0)drainMouse();}});}
    private void keyboard() {
        work(R.string.loading,model::textSession,session->{
            EditText input=field(R.string.sync_keyboard,"");input.setSingleLine(false);input.setMinLines(3);
            TextView state=text(t(R.string.loading),12);LinearLayout panel=column();panel.addView(input);panel.addView(state);
            final boolean[] applying={false},pendingComposition={false};final KeyboardSync[] reference=new KeyboardSync[1];
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.sync_keyboard).setView(panel).setNegativeButton(R.string.close,null).setNeutralButton(R.string.resync,null).setPositiveButton(R.string.confirm,null).create();keyboardDialog=dialog;
            KeyboardSync sync=new KeyboardSync(new KeyboardSync.Endpoint(){public String read()throws Exception{return session.get();}public String write(String value)throws Exception{return session.set(value);}public void confirm()throws Exception{session.confirm();}public void close()throws Exception{session.close();}},new KeyboardSync.Observer(){
                public void remote(String value,long revision){handler.post(()->{if(keyboardDialog!=dialog||reference[0].revision()!=revision||pendingComposition[0]||android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.getText())>=0)return;applying[0]=true;if(!input.getText().toString().equals(value)){int selection=input.getSelectionStart();input.setText(value);input.setSelection(Math.min(Math.max(selection,0),value.length()));}applying[0]=false;state.setText(R.string.keyboard_live);});}
                public void failure(Exception error){handler.post(()->{if(keyboardDialog==dialog)state.setText(error.getLocalizedMessage());});}
                public void confirmed(){handler.post(dialog::dismiss);}
            });reference[0]=sync;
            input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable value){if(!applying[0])handler.post(()->{if(keyboardDialog!=dialog)return;boolean composing=android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.getText())>=0;pendingComposition[0]=composing;sync.edit(input.getText().toString(),composing);});}});
            Runnable composingWatcher=new Runnable(){public void run(){if(keyboardDialog!=dialog)return;boolean composing=android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.getText())>=0;sync.composition(composing);if(!composing&&pendingComposition[0]){pendingComposition[0]=false;sync.edit(input.getText().toString(),false);}handler.postDelayed(this,100);}};
            dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->{input.clearFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);sync.confirm();});dialog.getButton(-3).setOnClickListener(v->{session.reset();sync.resume();});sync.start();handler.postDelayed(composingWatcher,100);});
            dialog.setOnDismissListener(d->{keyboardDialog=null;handler.removeCallbacks(composingWatcher);sync.close();});showAlignedDialog(dialog);
        });
    }
    private void shell() {
        TextView out=text(shellOutput.isEmpty()?t(R.string.shell_ready):shellOutput,13);out.setTypeface(Typeface.MONOSPACE);out.setTextIsSelectable(true);out.setPadding(dp(14),dp(14),dp(14),dp(14));out.setBackground(rounded(Color.rgb(4,8,12),Color.TRANSPARENT,dp(18)));out.setTextColor(Color.rgb(89,238,174));ScrollView terminal=new ScrollView(this);terminal.setFillViewport(true);terminal.setBackground(rounded(Color.rgb(4,8,12),Color.TRANSPARENT,dp(18)));terminal.addView(out);terminal.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->terminal.scrollTo(0,Math.max(0,out.getBottom()-terminal.getHeight())));LinearLayout.LayoutParams terminalParams=new LinearLayout.LayoutParams(-1,0,1);terminalParams.setMargins(0,0,0,dp(4));content.addView(terminal,terminalParams);
        EditText command=field(R.string.shell_hint,shellDraft);LinearLayout keywords=card();sectionTitle(keywords,R.string.shell_keywords);GridLayout keywordGrid=new GridLayout(this);keywordGrid.setColumnCount(4);for(String keyword:new String[]{"pm","am","dumpsys","getprop","input","settings","logcat","ls","cat","grep","head","tail","|","-n","-s","-f"}){Button chip=button(keyword,()->insertKeyword(command,keyword));chip.setTextSize(11);chip.setMinHeight(dp(36));GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=dp(40);lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);lp.setMargins(dp(2),dp(2),dp(2),dp(2));keywordGrid.addView(chip,lp);}keywords.addView(keywordGrid);keywords.setVisibility(shellKeywordsVisible?View.VISIBLE:View.GONE);content.addView(keywords);
        LinearLayout commandBar=card();commandBar.setOrientation(0);command.setTypeface(Typeface.MONOSPACE);command.setTextSize(14);command.setSingleLine(true);commandBar.addView(command,new LinearLayout.LayoutParams(0,dp(52),1));TextView send=text("➤",22);send.setGravity(Gravity.CENTER);send.setTextColor(CYAN);send.setOnClickListener(v->{if(model.busy)return;String value=command.getText().toString();if(value.trim().isEmpty())return;shellDraft="";shellHistory.remove(value);shellHistory.add(0,value);if(shellHistory.size()>50)shellHistory.remove(50);saveCommandHistory();work(R.string.shell,()->model.shell(value),result->{shellOutput+="\n$ "+value+"\n"+result;if(shellOutput.length()>262144)shellOutput=shellOutput.substring(shellOutput.length()-262144);if(tab==3)render();});});commandBar.addView(send,new LinearLayout.LayoutParams(dp(42),dp(48)));TextView more=text("⋯",24);more.setGravity(Gravity.CENTER);more.setTextColor(CYAN);more.setOnClickListener(v->shellMenu(command));commandBar.addView(more,new LinearLayout.LayoutParams(dp(42),dp(48)));content.addView(commandBar);
        command.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){shellDraft=s.toString();}public void afterTextChanged(Editable s){}});
    }
    private void shellMenu(EditText command){String[] items={t(R.string.presets),t(R.string.command_history),t(R.string.command_favorites),t(R.string.save_favorite),t(R.string.clear),t(R.string.shell_keywords)};new AlertDialog.Builder(this).setItems(items,(d,index)->{if(index==0)chooseShellPreset(command);else if(index==1)chooseCommand(command,shellHistory,false);else if(index==2)chooseCommand(command,commandFavorites(),true);else if(index==3){String value=command.getText().toString().trim();if(!value.isEmpty()){List<String> favorites=commandFavorites();if(!favorites.contains(value))favorites.add(value);saveCommandFavorites(favorites);}}else if(index==4){shellOutput="";render();}else{shellKeywordsVisible=!shellKeywordsVisible;render();}}).show();}
    private void insertKeyword(EditText field,String keyword){String value=field.getText().toString();int end=Math.max(0,field.getSelectionStart());String before=value.substring(0,Math.min(end,value.length()));String after=value.substring(Math.min(end,value.length()));String fragment="";int start=before.length();while(start>0&&!Character.isWhitespace(before.charAt(start-1)))start--;fragment=before.substring(start);if(!fragment.isEmpty()&&fragment.matches("[A-Za-z-]+")&&keyword.startsWith(fragment))before=before.substring(0,start)+keyword+" ";else before=before+(before.isEmpty()||Character.isWhitespace(before.charAt(before.length()-1))?"":" ")+keyword+" ";field.setText(before+after);field.setSelection(before.length());}
    private void chooseShellPreset(EditText field){String[] categories={t(R.string.device_info),t(R.string.network),t(R.string.apps),t(R.string.performance),t(R.string.logs),t(R.string.mouse)};String[][] commands={{"getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk","getprop","getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist","uptime","wm size; wm density"},{"ip addr show","ip route","dumpsys connectivity | head -n 100","settings get global http_proxy"},{"pm list packages -3","pm list packages -s","pm list packages -f","dumpsys window windows | grep -E \"mCurrentFocus|mFocusedApp\"","dumpsys activity activities | head -n 100","dumpsys activity services | head -n 100"},{"dumpsys meminfo | head -n 100","df -h","dumpsys cpuinfo | head -n 60","top -b -n 1 | head -n 40","dumpsys battery"},{"logcat -d -t 200","logcat -d -t 200 '*:E'"},{"id; getenforce; ls -lZ /dev/uhid /dev/uinput","dumpsys input | grep -A 24 -B 2 'TVADB Virtual Mouse'","getevent -lp","ps -A | grep -E 'tvadb|uinput'","ime list -s","settings get secure default_input_method"}};new AlertDialog.Builder(this).setTitle(R.string.presets).setItems(categories,(d,category)->new AlertDialog.Builder(this).setTitle(categories[category]).setItems(commands[category],(dialog,index)->field.setText(commands[category][index])).show()).show();}
    private List<String> commandFavorites(){List<String> result=new ArrayList<>();try{org.json.JSONArray array=new org.json.JSONArray(getSharedPreferences("shell-favorites",0).getString("items","[]"));for(int i=0;i<array.length();i++)result.add(array.getString(i));}catch(org.json.JSONException ignored){}return result;}
    private List<String> loadStringList(String raw){List<String> result=new ArrayList<>();try{org.json.JSONArray array=new org.json.JSONArray(raw);for(int i=0;i<array.length();i++)result.add(array.getString(i));}catch(org.json.JSONException ignored){}return result;}
    private void saveCommandHistory(){getSharedPreferences("shell-history",0).edit().putString("items",new org.json.JSONArray(shellHistory).toString()).apply();}
    private void saveCommandFavorites(List<String> values){getSharedPreferences("shell-favorites",0).edit().putString("items",new org.json.JSONArray(values).toString()).apply();}
    private void chooseCommand(EditText field,List<String> commands,boolean favorites){if(commands.isEmpty())return;new AlertDialog.Builder(this).setItems(commands.toArray(new String[0]),(d,i)->field.setText(commands.get(i))).setNeutralButton(R.string.clear,(d,w)->{commands.clear();if(favorites)saveCommandFavorites(commands);else saveCommandHistory();}).show();}
    private byte[] lastScreenshot;
    private void showUsageNotice(){
        startActivity(new Intent(this,UsageNoticeActivity.class).putExtra("readOnly",true));
    }

    private void settingsPage() {
        LinearLayout general=card();sectionTitle(general,R.string.general);
        general.addView(button(t(R.string.language)+"   ›",()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.follow_system),"简体中文","English"},(d,i)->{preferences.edit().putString("language",new String[]{"","zh-Hans-CN","en"}[i]).apply();recreate();}).show()));
        general.addView(button(t(R.string.visual_theme)+"   ·   "+t(preferences.getString("visual-theme","cyberpunk").equals("cyberpunk")?R.string.theme_cyberpunk:R.string.theme_standard),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.theme_cyberpunk),t(R.string.theme_standard)},(d,i)->{preferences.edit().putString("visual-theme",i==0?"cyberpunk":"standard").apply();recreate();}).show()));
        general.addView(button(t(R.string.appearance)+"   ·   "+t(preferences.getString("appearance","system").equals("light")?R.string.light:preferences.getString("appearance","system").equals("dark")?R.string.dark:R.string.follow_system),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.follow_system),t(R.string.light),t(R.string.dark)},(d,i)->{preferences.edit().putString("appearance",new String[]{"system","light","dark"}[i]).apply();recreate();}).show()));
        general.addView(button(t(R.string.app_icon),this::chooseAppIcon));content.addView(general);
        LinearLayout connection=card();sectionTitle(connection,R.string.connect);addToggle(connection,R.string.auto_connect,"auto-connect",false);content.addView(connection);
        LinearLayout remoteSettings=card();sectionTitle(remoteSettings,R.string.remote_preferences);addToggle(remoteSettings,R.string.remote_recording,"remote-recording",false);addToggle(remoteSettings,R.string.remote_haptics,"remote-haptics",true);remoteSettings.addView(button(t(R.string.default_remote_mode),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.screen_preview),t(R.string.mouse)},(d,i)->preferences.edit().putString("remote-mode",i==0?"screen":"mouse").apply()).show()));remoteSettings.addView(button(t(R.string.default_live_screen_control),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.touch_mode),t(R.string.mouse)},(d,i)->preferences.edit().putString("live-screen-control",i==0?"touch":"mouse").apply()).show()));content.addView(remoteSettings);
        LinearLayout appSettings=card();sectionTitle(appSettings,R.string.apps);addToggle(appSettings,R.string.load_icons,"load-icons",true);addToggle(appSettings,R.string.replace_install,"replace-install",true);content.addView(appSettings);
        LinearLayout about=card();sectionTitle(about,R.string.about);about.addView(text(t(R.string.intro),14));about.addView(button(t(R.string.legal_title),this::showUsageNotice));about.addView(text(t(R.string.cyberpunk_theme_hint),12));content.addView(about);
    }
    private void addToggle(LinearLayout target,int label,String key,boolean defaultValue) {
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView title=text(t(label),14);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setChecked(preferences.getBoolean(key,defaultValue));toggle.setOnCheckedChangeListener((view,checked)->preferences.edit().putBoolean(key,checked).apply());row.addView(toggle);target.addView(row);
    }
    private void chooseAppIcon(){new AlertDialog.Builder(this).setTitle(R.string.app_icon).setItems(new String[]{t(R.string.icon_neon),t(R.string.icon_pearl)},(d,index)->{try {
        String enabled=index==0?"NeonLauncher":"PearlLauncher",disabled=index==0?"PearlLauncher":"NeonLauncher";android.content.pm.PackageManager pm=getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(this,getPackageName()+"."+enabled),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(new ComponentName(this,getPackageName()+"."+disabled),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP);Toast.makeText(this,R.string.icon_changed,0).show();
    }catch(Exception e){error(e.getLocalizedMessage());}}).show();}
    private void settings(){
        LinearLayout panel=column();ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        LinearLayout general=card();sectionTitle(general,R.string.general);
        general.addView(button(t(R.string.language),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.follow_system),"简体中文","English"},(d,i)->{preferences.edit().putString("language",new String[]{"","zh-Hans-CN","en"}[i]).apply();recreate();}).show()));
        general.addView(button(t(R.string.visual_theme),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.theme_cyberpunk),t(R.string.theme_standard)},(d,i)->{preferences.edit().putString("visual-theme",i==0?"cyberpunk":"standard").apply();recreate();}).show()));
        general.addView(button(t(R.string.appearance),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.follow_system),t(R.string.light),t(R.string.dark)},(d,i)->{preferences.edit().putString("appearance",new String[]{"system","light","dark"}[i]).apply();recreate();}).show()));
        general.addView(button(t(R.string.app_icon),this::chooseAppIcon));panel.addView(general);
        LinearLayout connectSettings=card();sectionTitle(connectSettings,R.string.connect);addToggle(connectSettings,R.string.auto_connect,"auto-connect",false);panel.addView(connectSettings);
        LinearLayout remoteSettings=card();sectionTitle(remoteSettings,R.string.remote_preferences);addToggle(remoteSettings,R.string.remote_recording,"remote-recording",false);addToggle(remoteSettings,R.string.remote_haptics,"remote-haptics",true);
        remoteSettings.addView(button(t(R.string.default_remote_mode),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.screen_preview),t(R.string.mouse)},(d,i)->preferences.edit().putString("remote-mode",i==0?"screen":"mouse").apply()).show()));
        remoteSettings.addView(button(t(R.string.default_live_screen_control),()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.touch_mode),t(R.string.mouse)},(d,i)->preferences.edit().putString("live-screen-control",i==0?"touch":"mouse").apply()).show()));panel.addView(remoteSettings);
        LinearLayout appSettings=card();sectionTitle(appSettings,R.string.apps);addToggle(appSettings,R.string.load_icons,"load-icons",true);addToggle(appSettings,R.string.replace_install,"replace-install",true);panel.addView(appSettings);
        LinearLayout about=card();sectionTitle(about,R.string.about);about.addView(text(t(R.string.intro),13));about.addView(button(t(R.string.legal_title),this::showUsageNotice));panel.addView(about);
        new AlertDialog.Builder(this).setTitle(R.string.client_settings).setView(scroll).setPositiveButton(R.string.close,null).show();
    }
    private void confirmAerialOperation(boolean installing,String requested){
        AerialIntegration.Status status=model.aerialStatus;
        if(!model.connected||model.busy||model.aerialRefreshing||status==null||status.sdk<14)return;
        if(!installing&&status.installed==null)return;
        String token=model.devices.token();
        String flavor=status.installed!=null?status.installed.flavor:status.sdk<21?"lite":requested;
        String message=String.join("\n\n",
            t(R.string.aerial_consent_target)+": "+model.deviceName+" ("+model.host+":"+model.port+")",
            t(R.string.aerial_consent_edition)+": "+flavor,
            t(installing?R.string.aerial_consent_install_body:R.string.aerial_consent_configure_body),
            t(flavor.equals("lite")?R.string.aerial_consent_permissions_lite:R.string.aerial_consent_permissions),
            t(R.string.aerial_consent_source),t(R.string.aerial_consent_result));
        new AlertDialog.Builder(this)
            .setTitle(installing?R.string.aerial_consent_install_title:R.string.aerial_consent_configure_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(installing?R.string.aerial_consent_install_agree:R.string.aerial_consent_configure_agree,(dialog,which)->{
                if(!model.connected||!token.equals(model.devices.token())){
                    error(t(R.string.aerial_device_changed));
                    return;
                }
                work(installing?R.string.aerial_installing:R.string.aerial_configuring,
                    ()->installing?model.installAerial(flavor,token):model.configureAerial(flavor,token),
                    this::showAerialSetupResult);
            }).show();
    }

    private void showAerialSetupResult(String summary){if(isFinishing()||isDestroyed())return;output(t(R.string.aerial_setup_report),summary+"\n\n"+(model.aerialSetupReport==null?"":model.aerialSetupReport));renderAerialCard();}
    private void renderAerialCard(){
        if(aerialPanel==null)return;aerialPanel.removeAllViews();AerialIntegration.Status status=model.aerialStatus;
        if(!aerialDetails){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(this);icon.setImageResource(android.R.drawable.ic_media_play);icon.setColorFilter(CYAN);icon.setPadding(dp(9),dp(9),dp(9),dp(9));icon.setBackground(rounded(CYAN&0x18333333,Color.TRANSPARENT,dp(12)));row.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout labels=column();TextView name=text("Aerial",15);name.setTypeface(null,Typeface.BOLD);name.setPadding(dp(12),dp(2),dp(4),dp(2));TextView value=text(aerialStatusText(status),12);value.setTextColor(model.aerialError==null?Color.GRAY:Color.RED);value.setPadding(dp(12),0,dp(4),dp(2));labels.addView(name);labels.addView(value);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView chevron=text("›",24);chevron.setTextColor(Color.GRAY);row.addView(chevron);row.setMinimumHeight(dp(60));row.setBackgroundResource(android.R.drawable.list_selector_background);row.setOnClickListener(v->{aerialDetails=true;render();});aerialPanel.addView(row);return;}
        aerialPanel.addView(text("Aerial",20));aerialPanel.addView(text(aerialStatusText(status),14));
        if(model.aerialWorking&&model.busy){aerialPanel.addView(text(model.operation+(Double.isNaN(model.aerialProgress)?"":"  "+Math.round(model.aerialProgress*100)+"%"),12));ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setIndeterminate(Double.isNaN(model.aerialProgress));if(!Double.isNaN(model.aerialProgress))progress.setProgress((int)(model.aerialProgress*1000));aerialPanel.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));}
        if(status!=null&&status.installed!=null){aerialPanel.addView(text(t(R.string.aerial_address),12));aerialPanel.addView(text("http://"+model.host+":8790",14));}
        if(status!=null&&status.sdk<14)aerialPanel.addView(text(t(R.string.aerial_unsupported_sdk),12));
        if(model.aerialError!=null)aerialPanel.addView(text(model.aerialError,12));
        if(status!=null&&status.installed==null&&status.sdk>=21){Spinner edition=new Spinner(this);edition.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Standard","Guard"}));edition.setSelection(aerialFlavor.equals("guard")?1:0);edition.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){aerialFlavor=i==0?"standard":"guard";}public void onNothingSelected(AdapterView<?> p){}});edition.setEnabled(!model.busy&&!model.aerialRefreshing);aerialPanel.addView(edition);aerialPanel.addView(text(t(R.string.aerial_guard_hint),12));}
        if(status==null||status.installed==null||Boolean.TRUE.equals(model.aerialUpdateAvailable))aerialAction(android.R.drawable.stat_sys_download_done,status!=null&&status.installed!=null?R.string.aerial_upgrade:R.string.aerial_install,()->confirmAerialOperation(true,aerialFlavor),status!=null&&status.sdk>=14&&!model.busy&&!model.aerialRefreshing);
        if(model.aerialGuardRetry&&status!=null&&status.installed==null){aerialDivider();aerialAction(android.R.drawable.ic_menu_rotate,R.string.aerial_retry_guard,()->confirmAerialOperation(true,"guard"),!model.busy&&!model.aerialRefreshing);}
        if(status!=null&&status.installed!=null){aerialDivider();aerialAction(android.R.drawable.ic_media_play,R.string.aerial_configure,()->confirmAerialOperation(false,aerialFlavor),!model.busy&&!model.aerialRefreshing);}
        if(status!=null&&status.installed!=null){
            aerialDivider();aerialAction(android.R.drawable.ic_popup_sync,R.string.aerial_check_update,()->work(R.string.aerial_check_update,model::checkAerialUpdate,available->{Toast.makeText(this,available?R.string.aerial_update_available:R.string.aerial_current,Toast.LENGTH_LONG).show();renderAerialCard();}),!model.busy&&!model.aerialRefreshing);
            aerialDivider();aerialAction(android.R.drawable.ic_menu_info_details,R.string.aerial_home_status,()->work(R.string.aerial_home_status,model::aerialHomeStatus,result->output(t(R.string.aerial_home_status),t(R.string.aerial_default_home)+": "+t(result[0]?R.string.yes:R.string.no)+(status.installed.flavor.equals("lite")?"":"\n"+t(R.string.aerial_home_guard)+": "+t(result[1]?R.string.yes:R.string.no)))),!model.busy&&!model.aerialRefreshing);
        }
        aerialDivider();aerialAction(android.R.drawable.ic_popup_sync,R.string.refresh,model::refreshAerial,!model.busy&&!model.aerialRefreshing);
        aerialDivider();aerialAction(android.R.drawable.ic_menu_camera,R.string.scan_aerial_qr,this::scanAerial,true);
    }
    private void aerialAction(int icon,int label,Runnable action,boolean enabled){Button item=new Button(this);item.setText(t(label));item.setTextSize(14);item.setTextColor(CYAN);item.setAllCaps(false);item.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);item.setMinHeight(dp(52));item.setPadding(dp(8),0,dp(8),0);item.setBackgroundResource(android.R.drawable.list_selector_background);android.graphics.drawable.Drawable leading=getDrawable(icon).mutate(),trailing=getDrawable(android.R.drawable.ic_media_next).mutate();leading.setColorFilter(CYAN,android.graphics.PorterDuff.Mode.SRC_IN);trailing.setColorFilter(Color.GRAY,android.graphics.PorterDuff.Mode.SRC_IN);leading.setBounds(0,0,dp(22),dp(22));trailing.setBounds(0,0,dp(18),dp(18));item.setCompoundDrawables(leading,null,trailing,null);item.setCompoundDrawablePadding(dp(12));item.setEnabled(enabled);item.setOnClickListener(v->action.run());aerialPanel.addView(item);}
    private void aerialDivider(){View divider=new View(this);divider.setBackgroundColor(CYAN&0x22444444);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(1));params.setMargins(dp(54),0,0,0);aerialPanel.addView(divider,params);}
    private String aerialStatusText(AerialIntegration.Status status){return status==null?t(model.aerialRefreshing?R.string.aerial_checking:R.string.aerial_unknown):status.installed==null?t(R.string.aerial_not_installed):status.installed.version+" · "+status.installed.flavor;}
    private void aerialDetailsPage(){aerialPanel=card();content.addView(aerialPanel);renderAerialCard();}
    private void tools() {
        aerialPanel=card();content.addView(aerialPanel);renderAerialCard();
        Runnable screenshot=()->work(R.string.screenshot,model::screenshot,bytes->{lastScreenshot=bytes;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null){error(t(R.string.capture_failed));return;}ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);new AlertDialog.Builder(this).setTitle(R.string.screenshot).setView(image).setPositiveButton(R.string.save,(d,w)->create("TVDeck.png","image/png",14)).setNegativeButton(R.string.close,null).show();});
        Runnable record=()->{if(recording)return;int[] seconds={15,30,60};new AlertDialog.Builder(this).setItems(new String[]{"15 s","30 s","60 s"},(d,i)->{
            recording=true;String remote="/sdcard/tvdeck-record-"+UUID.randomUUID()+".mp4";
            if(preferences.getBoolean("remote-recording",false)){tab=2;render();}
            new Thread(()->{try{model.read("shell:screenrecord --time-limit "+seconds[i]+" "+DeckModel.quote(remote),1024*1024,seconds[i]+20);handler.post(()->{recording=false;if(!isFinishing()&&!isDestroyed()){pendingDownload=remote;create("TVDeck.mp4","video/mp4",11);}});}catch(Exception error){handler.post(()->{recording=false;error(error.getLocalizedMessage());});}},"tvdeck-record").start();
        }).show();};
        toolSection(R.string.screen_debugging,new int[]{R.string.screenshot,R.string.record,R.string.logs,R.string.device_info,R.string.network,R.string.install_history},new Runnable[]{screenshot,record,this::logs,()->work(R.string.device_info,()->model.diagnostic(false),out->output(t(R.string.device_info),out)),()->work(R.string.network,()->model.diagnostic(true),out->output(t(R.string.network),out)),this::showInstallHistory});
        toolSection(R.string.device_management,new int[]{R.string.open_tv_settings,R.string.developer_settings},new Runnable[]{()->work(R.string.settings,()->model.shell("am start -a android.settings.SETTINGS"),out->output(t(R.string.settings),out)),()->work(R.string.settings,()->model.shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),out->output(t(R.string.settings),out))});
        toolSection(R.string.risky_actions,new int[]{R.string.reboot},new Runnable[]{()->new AlertDialog.Builder(this).setItems(new String[]{t(R.string.reboot),"Recovery","Bootloader"},(d,i)->{String target=new String[]{"","recovery","bootloader"}[i];confirm(t(R.string.reboot)+" "+target,()->work(R.string.reboot,()->{model.reboot(target);model.disconnect();return true;},ok->render()));}).show()});
        toolSection(R.string.about,new int[]{R.string.client_settings,R.string.app_icon,R.string.licenses},new Runnable[]{this::settings,this::chooseAppIcon,()->{try(InputStream in=getAssets().open("THIRD_PARTY_NOTICES.txt")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);output(t(R.string.licenses),out.toString("UTF-8"));}catch(IOException e){error(e.getLocalizedMessage());}}});
        content.addView(text(t(R.string.intro),14));
    }
    private void showInstallHistory(){
        org.json.JSONArray items=model.installationHistory();LinearLayout list=column();list.setPadding(dp(16),dp(4),dp(16),dp(4));
        if(items.length()==0){TextView empty=text(t(R.string.no_install_history),15);empty.setGravity(Gravity.CENTER);empty.setTextColor(Color.GRAY);empty.setPadding(0,dp(48),0,dp(48));list.addView(empty);}
        java.text.DateFormat dates=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,Locale.getDefault());
        for(int i=0;i<items.length();i++){
            org.json.JSONObject item=items.optJSONObject(i);if(item==null)continue;
            String result=item.optString("result","");boolean success=!result.startsWith("Failed:");
            LinearLayout row=column();row.setPadding(0,dp(8),0,dp(10));LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=text(item.optString("name",""),15);name.setTypeface(null,Typeface.BOLD);name.setPadding(0,0,dp(8),0);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView state=text(t(success?R.string.install_success:R.string.install_failed),13);state.setTextColor(success?Color.rgb(35,150,94):Color.rgb(210,65,65));state.setPadding(0,0,0,0);top.addView(state);row.addView(top);
            String device=item.optString("device",model.deviceName),source=item.optString("source","");if(source.equals("files"))source=t(R.string.files);
            TextView detail=text(device+(source.isEmpty()?"":" · "+source),12);detail.setTextColor(Color.GRAY);detail.setPadding(0,dp(3),0,0);row.addView(detail);
            String version=item.optString("version","");if(!version.isEmpty()){TextView versionText=text("v"+version,11);versionText.setTypeface(Typeface.MONOSPACE);versionText.setTextColor(Color.GRAY);versionText.setPadding(0,dp(3),0,0);row.addView(versionText);}
            TextView date=text(dates.format(new Date(item.optLong("at",0))),11);date.setTextColor(Color.GRAY);date.setPadding(0,dp(3),0,0);row.addView(date);list.addView(row);
            if(i<items.length()-1){View divider=new View(this);divider.setBackgroundColor(CYAN&0x22444444);list.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(list);scroll.setFillViewport(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.install_history).setView(scroll).setNegativeButton(R.string.close,null).setPositiveButton(R.string.clear,(d,w)->{model.clearInstallationHistory();showInstallHistory();}).create();
        showAlignedDialog(dialog);Window window=dialog.getWindow();if(window!=null){WindowManager.LayoutParams params=window.getAttributes();params.height=(int)(getResources().getDisplayMetrics().heightPixels*0.78);window.setAttributes(params);}
    }
    private void toolSection(int title,int[] actions,Runnable[] callbacks){LinearLayout panel=card();sectionTitle(panel,title);for(int i=0;i<actions.length;i++){final int label=actions[i];final Runnable action=callbacks[i];LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(8),dp(6),dp(8),dp(6));ImageView icon=new ImageView(this);icon.setImageResource(toolIcon(label));icon.setColorFilter(label==R.string.reboot?Color.RED:CYAN);icon.setPadding(dp(9),dp(9),dp(9),dp(9));icon.setBackground(rounded(label==R.string.reboot?0x18ff0000:0x18232323,Color.TRANSPARENT,dp(12)));item.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));TextView name=text(t(label),14);name.setPadding(dp(12),0,dp(6),0);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));item.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView chevron=text("›",24);chevron.setTextColor(Color.GRAY);item.addView(chevron);item.setMinimumHeight(dp(56));item.setBackgroundResource(android.R.drawable.list_selector_background);item.setOnClickListener(v->action.run());panel.addView(item);if(i<actions.length-1){View divider=new View(this);divider.setBackgroundColor(CYAN&0x22444444);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.setMargins(dp(54),0,0,0);panel.addView(divider,line);}}content.addView(panel);}
    private int toolIcon(int label){if(label==R.string.screenshot)return android.R.drawable.ic_menu_camera;if(label==R.string.record)return android.R.drawable.ic_menu_slideshow;if(label==R.string.logs)return android.R.drawable.ic_menu_view;if(label==R.string.device_info)return android.R.drawable.ic_menu_info_details;if(label==R.string.network)return android.R.drawable.ic_menu_share;if(label==R.string.install_history)return android.R.drawable.ic_menu_recent_history;if(label==R.string.open_tv_settings)return android.R.drawable.ic_menu_preferences;if(label==R.string.developer_settings)return android.R.drawable.ic_menu_manage;if(label==R.string.reboot)return android.R.drawable.ic_menu_rotate;if(label==R.string.client_settings)return android.R.drawable.ic_menu_preferences;if(label==R.string.app_icon)return android.R.drawable.ic_menu_gallery;return android.R.drawable.ic_menu_info_details;}
    private void stopLogs() {
        logsGeneration++;io.github.muntashirakon.adb.AdbStream stream=logsStream;logsStream=null;
        if(stream!=null)new Thread(()->{try{stream.close();}catch(IOException ignored){}},"tvdeck-log-close").start();
    }
    private void logs() {
        TextView view=text(filteredLogs(),12);view.setTypeface(Typeface.MONOSPACE);view.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(view);
        LinearLayout panel=column();EditText search=field(R.string.search,logSearch),tag=field(R.string.log_tag,logTag),pid=field(R.string.log_pid,logPid);pid.setInputType(2);panel.addView(search);LinearLayout filters=new LinearLayout(this);filters.addView(tag,new LinearLayout.LayoutParams(0,-2,1));filters.addView(pid,new LinearLayout.LayoutParams(0,-2,1));panel.addView(filters);
        Spinner levels=new Spinner(this);String[] choices={t(R.string.all_levels),"V","D","I","W","E","F"};levels.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));levels.setSelection(logLevel.isEmpty()?0:"VDIWEF".indexOf(logLevel)+1);panel.addView(levels);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(300)));
        TextWatcher filter=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){logSearch=search.getText().toString();logTag=tag.getText().toString().trim();logPid=pid.getText().toString().trim();view.setText(filteredLogs());}public void afterTextChanged(Editable s){}};search.addTextChangedListener(filter);tag.addTextChangedListener(filter);pid.addTextChangedListener(filter);
        levels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){logLevel=position==0?"":choices[position];view.setText(filteredLogs());}public void onNothingSelected(AdapterView<?> p){}});
        logsDialog=new AlertDialog.Builder(this).setTitle(R.string.logs).setView(panel).setPositiveButton(R.string.close,null).setNeutralButton(R.string.save,(d,w)->create("TVDeck-logcat.txt","text/plain",18)).setNegativeButton(R.string.pause,null).create();
        final boolean[] paused={false};logsDialog.setOnShowListener(d->logsDialog.getButton(-2).setOnClickListener(v->{paused[0]=!paused[0];logsDialog.getButton(-2).setText(paused[0]?R.string.resume:R.string.pause);if(paused[0])stopLogs();else readLogs(view);}));
        logsDialog.setOnDismissListener(d->{stopLogs();logsDialog=null;});showAlignedDialog(logsDialog);readLogs(view);
    }
    private String filteredLogs(){return LogFilter.apply(logBuffer,logSearch,logTag,logPid,logLevel);}
    private void readLogs(TextView view) {
        long generation=++logsGeneration;
        new Thread(()->{try(io.github.muntashirakon.adb.AdbStream stream=model.open("shell:logcat -v threadtime -T 100")) {
            if(generation!=logsGeneration)return;logsStream=stream;Reader reader=new InputStreamReader(model.input(stream),java.nio.charset.StandardCharsets.UTF_8);char[] buffer=new char[4096];int n;StringBuilder accumulated=new StringBuilder(logBuffer);long last=0;
            while(generation==logsGeneration&&(n=reader.read(buffer))!=-1) {accumulated.append(buffer,0,n);if(accumulated.length()>131072)accumulated.delete(0,accumulated.length()-131072);long now=System.currentTimeMillis();if(now-last<250)continue;last=now;String update=accumulated.toString();handler.post(()->{if(generation==logsGeneration){logBuffer=update;view.setText(filteredLogs());}});}
        }catch(Exception e){handler.post(()->{if(generation==logsGeneration)error(e.getLocalizedMessage());});}},"tvdeck-logcat").start();
    }
    private void startPreview() {
        if(previewViewport==null||!model.connected)return;
        stopPreview();preview=true;long generation=++previewGeneration;previewViewport.setRequested(true);
        if(previewViewport.surface.getHolder().getSurface().isValid())startPreviewVideo(generation);
    }
    private void startPreviewVideo(long generation){
        if(!preview||generation!=previewGeneration||videoPreview!=null||previewViewport==null)return;
        PreviewViewport viewport=previewViewport;
        final VideoPreview[] owner={null};
        VideoPreview player=new VideoPreview(model,viewport.surface.getHolder().getSurface(),new VideoPreview.Observer(){
            public void frame(int width,int height){handler.post(()->{
                if(generation!=previewGeneration||videoPreview!=owner[0]||viewport!=previewViewport)return;
                viewport.videoFrame(videoPreview.sourceWidth,videoPreview.sourceHeight);
            });}
            public void fallback(Exception reason){handler.post(()->{
                if(generation!=previewGeneration||!preview||videoPreview!=owner[0]||viewport!=previewViewport)return;
                if(videoPreview!=null){videoPreview.close();videoPreview=null;}
                viewport.fallback(reason.getLocalizedMessage()==null?reason.getClass().getSimpleName():reason.getLocalizedMessage());previewFrame(generation);
            });}
        });
        owner[0]=player;videoPreview=player;player.start();
    }
    private void previewTouch(int action,float x,float y,long time){
        if(action==MotionEvent.ACTION_CANCEL){
            previewGestureActive=false;
            if(previewMouseDown){previewMouseDown=false;model.inputWorker.execute(()->{try{model.mouse("U 1");}catch(Exception ignored){}});}
            return;
        }
        if(!preview||!model.connected)return;
        boolean mouse=preferences.getString("live-screen-control","touch").equals("mouse");
        if(action==MotionEvent.ACTION_DOWN){
            previewGestureActive=true;previewStartX=previewLastX=x;previewStartY=previewLastY=y;previewTouchStarted=time;
            if(mouse){previewMouseDown=true;model.inputWorker.execute(()->{try{model.mouse("H "+Math.round(x)+" "+Math.round(y));model.mouse("D 1");}catch(Exception e){handler.post(()->error(e.getLocalizedMessage()));}});}
        }else if(previewGestureActive&&action==MotionEvent.ACTION_MOVE&&mouse){dx.addAndGet(Math.round(x-previewLastX));dy.addAndGet(Math.round(y-previewLastY));previewLastX=x;previewLastY=y;drainMouse();}
        else if(previewGestureActive&&action==MotionEvent.ACTION_UP){
            previewGestureActive=false;int x1=Math.round(previewStartX),y1=Math.round(previewStartY),x2=Math.round(x),y2=Math.round(y);
            int duration=(int)Math.min(2000,Math.max(100,time-previewTouchStarted));boolean swipe=Math.hypot(x2-x1,y2-y1)>12||time-previewTouchStarted>=550;
            previewMouseDown=false;
            model.inputWorker.execute(()->{try{if(mouse)model.mouse("U 1");else model.touch(x1,y1,x2,y2,duration,swipe);}catch(Exception e){handler.post(()->error(e.getLocalizedMessage()));}});
        }
    }
    private void setPreviewFullScreen(boolean full){
        if(full==previewFullScreen||previewViewport==null)return;
        previewViewport.cancelTouch();previewFullScreen=full;
        if(full){
            previewPreviousOrientation=getRequestedOrientation();previewPreviousConfigurationOrientation=getResources().getConfiguration().orientation;
            View page=content;while(page.getParent() instanceof View&&page.getParent()!=root)page=(View)page.getParent();
            for(int i=0;i<root.getChildCount();i++)if(root.getChildAt(i)!=page)hideForPreview(root.getChildAt(i));
            for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i)!=previewPanel)hideForPreview(content.getChildAt(i));
            root.setPadding(0,0,0,0);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            previewViewport.setFullScreen(true);resizeFullScreenPreview();
            if(content.getParent() instanceof ScrollView)((ScrollView)content.getParent()).scrollTo(0,0);
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }else{
            for(Map.Entry<View,Integer> entry:previewHiddenViews.entrySet())entry.getKey().setVisibility(entry.getValue());previewHiddenViews.clear();
            getWindow().getDecorView().setSystemUiVisibility(darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            previewViewport.setFullScreen(false);ViewGroup.LayoutParams params=previewViewport.getLayoutParams();params.height=dp(380);previewViewport.setLayoutParams(params);
            int previous=previewPreviousOrientation;
            if(previous==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED||previous==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR||previous==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR){
                int fixed=previewPreviousConfigurationOrientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                setRequestedOrientation(fixed);
                handler.postDelayed(()->{if(!previewFullScreen&&!isFinishing()&&!isDestroyed()&&getRequestedOrientation()==fixed)setRequestedOrientation(previous);},500);
            }else setRequestedOrientation(previous);
            root.requestApplyInsets();
        }
    }
    private void hideForPreview(View view){previewHiddenViews.put(view,view.getVisibility());view.setVisibility(View.GONE);}
    private void resizeFullScreenPreview(){
        if(previewViewport==null||root==null||root.getHeight()<1)return;
        ViewGroup.LayoutParams params=previewViewport.getLayoutParams();if(params.height!=root.getHeight()){params.height=root.getHeight();previewViewport.setLayoutParams(params);}
    }
    static void fitPreviewSurface(SurfaceView surface,View container,int sourceWidth,int sourceHeight) {
        if(container.getWidth()<1||container.getHeight()<1||sourceWidth<1||sourceHeight<1)return;
        double scale=Math.min((double)container.getWidth()/sourceWidth,(double)container.getHeight()/sourceHeight);
        int width=Math.max(1,(int)Math.round(sourceWidth*scale)),height=Math.max(1,(int)Math.round(sourceHeight*scale));
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if(params.width==width&&params.height==height&&params.gravity==Gravity.CENTER)return;
        params.width=width;params.height=height;params.gravity=Gravity.CENTER;surface.setLayoutParams(params);
    }
    private void previewFrame(long generation) {
        if(!preview||generation!=previewGeneration||!model.connected)return;
        model.previewWorker.execute(()->{if(!preview||generation!=previewGeneration)return;long started=SystemClock.elapsedRealtime();try{byte[] bytes=model.screenshot();Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)throw new IOException(t(R.string.capture_failed));handler.post(()->{if(!preview||generation!=previewGeneration)return;if(previewViewport!=null)previewViewport.screenshot(bitmap);handler.postDelayed(()->previewFrame(generation),DeckModel.screenshotPreviewDelay(SystemClock.elapsedRealtime()-started));});}catch(Exception e){handler.post(()->{if(generation==previewGeneration){stopPreview();error(e.getLocalizedMessage());}});}});
    }
    private void stopPreview() {previewTouch(MotionEvent.ACTION_CANCEL,0,0,0);preview=false;previewGeneration++;if(videoPreview!=null){videoPreview.close();videoPreview=null;}if(previewViewport!=null)previewViewport.setRequested(false);}
}
