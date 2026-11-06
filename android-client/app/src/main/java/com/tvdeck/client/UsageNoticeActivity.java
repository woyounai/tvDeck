package com.tvdeck.client;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class UsageNoticeActivity extends Activity {
    private String region,language;
    private boolean readOnly;
    private Context localized;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        readOnly=getIntent().getBooleanExtra("readOnly",false)&&UsageConsent.accepted(this);
        setFinishOnTouchOutside(readOnly);
        region=UsageConsent.systemRegion();
        String appLanguage=getSharedPreferences("settings",0).getString("language","");
        language=(appLanguage.isEmpty()?getResources().getConfiguration().getLocales().get(0).getLanguage():appLanguage)
            .startsWith("zh")?"zh-Hans":"en";
        render();
    }

    @Override protected void onResume(){
        super.onResume();
        String current=UsageConsent.systemRegion();
        if(!current.equals(region)){region=current;render();}
    }

    @Override public void onBackPressed(){
        if(readOnly)super.onBackPressed();else finishAffinity();
    }

    private String text(int id){return localized.getString(id);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,boolean heading){
        TextView view=new TextView(localized);
        view.setText(value);
        view.setTextSize(heading?19:16);
        if(heading)view.setTypeface(null,Typeface.BOLD);
        view.setPadding(0,dp(8),0,dp(8));
        return view;
    }
    private void section(LinearLayout body,int title,int content){
        body.addView(label(text(title),true));
        body.addView(label(text(content),false));
    }
    private Button button(String title,Runnable action){
        Button button=new Button(localized);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(v->action.run());
        return button;
    }
    private void render(){
        Configuration configuration=new Configuration(getResources().getConfiguration());
        configuration.setLocales(android.os.LocaleList.forLanguageTags(language));
        localized=createConfigurationContext(configuration);
        LinearLayout root=new LinearLayout(localized);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24),dp(16),dp(24),dp(16));
        root.setFitsSystemWindows(true);
        root.addView(label(text(R.string.legal_title),true));
        ScrollView scroll=new ScrollView(localized);
        LinearLayout body=new LinearLayout(localized);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(label(text(R.string.legal_intro),false));
        body.addView(label(text(R.string.legal_version)+" · "+UsageConsent.VERSION,false));
        body.addView(button(text(R.string.legal_language)+" · "+(language.equals("en")?"English":"简体中文"),()->{
            new AlertDialog.Builder(this).setTitle(text(R.string.legal_language))
                .setItems(new String[]{"简体中文","English"},(dialog,index)->{
                    language=index==0?"zh-Hans":"en";
                    render();
                }).show();
        }));
        section(body,R.string.legal_scope_title,R.string.legal_scope);
        section(body,R.string.legal_risk_title,R.string.legal_risk);
        section(body,R.string.legal_security_title,R.string.legal_security);
        section(body,R.string.legal_compatibility_title,R.string.legal_compatibility);
        section(body,R.string.legal_rights_title,
            region.equals(UsageConsent.MAINLAND)?R.string.legal_rights_cn:R.string.legal_rights_intl);
        section(body,R.string.legal_acceptance_title,R.string.legal_acceptance);
        body.addView(button(text(R.string.legal_feedback),()->startActivity(
            new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/woyounai/tvDeck/issues")))));
        if(readOnly){
            android.content.SharedPreferences saved=UsageConsent.preferences(this);
            body.addView(label(text(R.string.legal_record_hint)+"\n"+saved.getString("version","")
                +" · "+saved.getString("region","")+" · "+saved.getString("language","")
                +"\n"+java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(saved.getLong("acceptedAt",0))),false));
            root.addView(button(text(R.string.close),this::finish));
        }else{
            CheckBox acknowledgment=new CheckBox(localized);
            acknowledgment.setText(text(R.string.legal_ack));
            root.addView(acknowledgment);
            TextView status=label("",false);
            status.setVisibility(View.GONE);
            root.addView(status);
            LinearLayout buttons=new LinearLayout(localized);
            Button decline=button(text(R.string.legal_decline),()->{
                acknowledgment.setChecked(false);
                status.setText(text(R.string.legal_declined));
                status.setVisibility(View.VISIBLE);
            });
            Button agree=button(text(R.string.legal_agree),()->{
                if(!acknowledgment.isChecked())return;
                if(!UsageConsent.accept(this,region,language)){
                    Toast.makeText(this,text(R.string.legal_store_failed),Toast.LENGTH_LONG).show();
                    return;
                }
                DeckWidget.updateAll(this);
                if(isTaskRoot())startActivity(new Intent(this,MainActivity.class));
                finish();
            });
            agree.setEnabled(false);
            acknowledgment.setOnCheckedChangeListener((view,checked)->agree.setEnabled(checked));
            buttons.addView(decline,new LinearLayout.LayoutParams(0,-2,1));
            buttons.addView(agree,new LinearLayout.LayoutParams(0,-2,1));
            root.addView(buttons);
        }
        setContentView(root);
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        getWindow().setLayout(Math.min(dp(640),Math.max(1,metrics.widthPixels-dp(32))),
            Math.max(1,(int)(metrics.heightPixels*0.85f)));
    }
}
