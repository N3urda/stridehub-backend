package io.stridehub.app.scale;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.HapticFeedbackConstants;
import android.widget.Switch;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.stridehub.app.scale.ble.ScaleClient;
import io.stridehub.app.scale.core.DailySeries;
import io.stridehub.app.scale.core.BodyMetrics;
import io.stridehub.app.scale.core.BodyMetrics.Metric;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.core.Profile;
import io.stridehub.app.scale.core.WeightInsights;
import io.stridehub.app.scale.data.LocalStore;
import io.stridehub.app.scale.ui.TrendChart;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

public final class WeighingActivity extends Activity implements ScaleClient.Listener {
    private int PAPER=0xfff2f5f9, INK=0xff132b46, MUTED=0xff57657a, GREEN=0xff4666ed, SOFT=0xffe8ecfc, LINE=0xffe1e5ef;
    private int SURFACE=Color.WHITE, ON_ACCENT=Color.WHITE;
    private final int[] pageScroll=new int[4];
    private static final int PERMISSIONS=51, ENABLE_BT=52, EXPORT=53, IMPORT=54;
    private LocalStore store;
    private ScaleClient client;
    private LinearLayout shell, content, nav;
    private ScrollView scroll;
    private TextView statusLabel, stageTitle, liveValue, liveCaption, liveFat, liveBmi, liveSource, pickerStatus;
    private Button measureButton, liveDetail;
    private AlertDialog manualDialog;
    private EditText manualWeight, manualFat;
    private Calendar manualTime;
    private long lastSavedId;
    private int page=0, days=30, historyLimit=50, weightAverageDays=0;
    private boolean forcePicker=false, foreground, autoResume=true, sessionFinished, permissionPending, externalSettings;
    private Double currentWeight;
    private Measurement pendingReview;
    private String reviewExplanation="";
    private ScaleClient.State captureState=ScaleClient.State.IDLE;
    private Metric selectedMetric=Metric.WEIGHT;
    private String status="添加一次体脂秤，以后打开这里即可直接上秤。", exportKind="csv";
    private List<Measurement> records=new ArrayList<>();
    private AlertDialog deviceDialog;
    private LinearLayout deviceList;
    private final LinkedHashMap<String,ScaleClient.Device> found=new LinkedHashMap<>();

    @Override public void onCreate(Bundle saved) {
        boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if(dark)setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(saved);
        if(dark){ON_ACCENT=0xff132b46;PAPER=0xff101822;INK=0xffe4ecf7;MUTED=0xffa4b0c2;GREEN=0xffa6b7ff;SOFT=0xff273658;LINE=0xff354258;SURFACE=0xff1b2634;}
        if(saved!=null){int[] positions=saved.getIntArray("pageScroll");if(positions!=null)System.arraycopy(positions,0,pageScroll,0,Math.min(4,positions.length));}
        if(saved!=null) { page=saved.getInt("page"); days=saved.getInt("days",30); try{selectedMetric=Metric.valueOf(saved.getString("metric","WEIGHT"));}catch(IllegalArgumentException ignored){} forcePicker=saved.getBoolean("forcePicker"); exportKind=saved.getString("exportKind","csv"); }
        if(saved!=null) {int average=saved.getInt("weightAverageDays",0);weightAverageDays=average==7||average==30?average:0;}
        io.stridehub.app.sync.NasSync.initialize(this);
        store=new LocalStore(this); client=new ScaleClient(this,this);
        if(saved!=null){lastSavedId=saved.getLong("lastSavedId",0);autoResume=saved.getBoolean("autoResume",true);externalSettings=saved.getBoolean("externalSettings");permissionPending=saved.getBoolean("permissionPending");sessionFinished=saved.getBoolean("sessionFinished",false);if(sessionFinished)captureState=ScaleClient.State.SAVED;}
        else autoResume=store.automaticWeighing();
        if(sessionFinished)status="测量完成，已保存到本机。";
        else if(!autoResume)status="已暂停接收；点击开始等待即可继续。";
        buildShell(); render();
        scroll.post(()->scroll.scrollTo(0,pageScroll[page]));
        if(saved!=null&&saved.getBoolean("manualOpen"))showManual(saved);
    }
    private void buildShell() {
        getWindow().setStatusBarColor(PAPER); getWindow().setNavigationBarColor(PAPER);
        boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell=column(); shell.setBackgroundColor(PAPER);
        shell.setOnApplyWindowInsetsListener((view,insets)-> {
            if(Build.VERSION.SDK_INT>=35) { android.graphics.Insets system=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(system.left,system.top,system.right,system.bottom); }
            return insets;
        });
        LinearLayout brand=row(); brand.setPadding(dp(16),dp(6),dp(16),dp(6)); brand.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("返回 StrideHub",false); back.setTextSize(12);back.setOnClickListener(v->finish());
        brand.addView(back,new LinearLayout.LayoutParams(-2,-2));
        TextView name=text("身体记录",20,INK,true);name.setGravity(Gravity.END);brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        shell.addView(brand);
        nav=row(); nav.setPadding(dp(12),dp(4),dp(12),dp(4)); nav.setBackgroundColor(PAPER); shell.addView(nav);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content=column(); content.setPadding(dp(24),dp(10),dp(24),dp(24)); scroll.addView(content);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(shell);
    }
    private void render() {
        records=store.all(); content.removeAllViews(); statusLabel=null; stageTitle=null; measureButton=null; liveValue=null; liveCaption=null; liveFat=null; liveBmi=null; liveSource=null;liveDetail=null;
        if(page==0) renderHome(); else if(page==1) renderTrend(); else if(page==2) renderHistory(); else renderSettings();
        nav.removeAllViews(); String[] titles={"称重","趋势","记录","设置"};
        for(int i=0;i<titles.length;i++) { final int target=i; Button item=button(titles[i],false); item.setTextColor(page==i?GREEN:MUTED); item.setBackground(interactive(page==i?SOFT:PAPER,16)); item.setTypeface(null,page==i?Typeface.BOLD:Typeface.NORMAL);item.setSelected(page==i);item.setContentDescription(titles[i]+(page==i?"，当前页面":"")); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); p.setMargins(dp(2),0,dp(2),0); nav.addView(item,p); item.setOnClickListener(v->{pageScroll[page]=scroll.getScrollY();page=target;render();scroll.post(()->scroll.scrollTo(0,pageScroll[page]));maybeAutoStart();}); }
    }
    private void intro(String title,String subtitle) { content.addView(text(title,29,INK,true)); TextView sub=text(subtitle,13,MUTED,false); sub.setLineSpacing(dp(3),1); add(content,sub,7,22); }
    private void renderHome() {
        intro("称重", "打开此页，直接上秤。稳定结果先保存到手机。");
        LinearLayout hero=card();hero.setBackground(shape(SOFT,24));
        stageTitle=text(stageHeading(),23,INK,true);hero.addView(stageTitle);
        statusLabel=text(status,13,MUTED,false);statusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(hero,statusLabel,8,16);
        Measurement saved=null;
        if(captureState==ScaleClient.State.SAVED)for(Measurement m:records)if(m.id==lastSavedId){saved=m;break;}
        Measurement shown=pendingReview!=null?pendingReview:saved;
        boolean live=currentWeight!=null && captureState==ScaleClient.State.MEASURING;
        liveCaption=text(pendingReview!=null?"稳定结果 · 等待确认":live?"实时读数 · 站稳后自动保存":saved!=null?"刚保存 · "+formatTime(saved.measuredAt):"本次称重 · 等待读数",12,MUTED,false);hero.addView(liveCaption);
        LinearLayout weight=row();weight.setGravity(Gravity.BOTTOM);
        liveValue=text(live?format(currentWeight):shown==null?"—":format(shown.weightKg),58,INK,true);weight.addView(liveValue);
        TextView unit=text("kg",20,MUTED,false);unit.setPadding(dp(9),0,0,dp(13));weight.addView(unit);add(hero,weight,9,12);
        LinearLayout metrics=row();
        LinearLayout fat=metric("体脂率",shown==null||shown.bodyFatPercent==null?"—":format(shown.bodyFatPercent)+" %");liveFat=(TextView)fat.getChildAt(1);
        LinearLayout bmi=metric("BMI",shown==null||shown.bmi==null?"—":format(shown.bmi));liveBmi=(TextView)bmi.getChildAt(1);
        metrics.addView(fat,new LinearLayout.LayoutParams(0,-2,1));metrics.addView(bmi,new LinearLayout.LayoutParams(0,-2,1));add(hero,metrics,4,12);
        String source=pendingReview!=null?reviewExplanation:live?"等待稳定结果 · 尚未保存":saved!=null?sourceLabel(saved)+" · 已保存到本机\n"+io.stridehub.app.sync.NasSync.rowStatus(store,saved.id):"接收时保持此页在前台，屏幕会暂时保持亮起。";
        liveSource=text(source,12,MUTED,false);liveSource.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(hero,liveSource,0,14);
        measureButton=button(primaryLabel(),!client.isBusy()||pendingReview!=null);hero.addView(measureButton,new LinearLayout.LayoutParams(-1,-2));measureButton.setOnClickListener(v->primaryAction());
        if(pendingReview!=null){Button discard=button("忽略这条，继续等待",false);add(hero,discard,8,0);discard.setOnClickListener(v->{pendingReview=null;currentWeight=null;client.discardPendingMeasurement();render();});}
        if(saved!=null){final Measurement row=saved;liveDetail=button("查看这次记录的身体指标",false);add(hero,liveDetail,8,0);liveDetail.setOnClickListener(v->showDetail(row));}
        content.addView(hero);
        LinearLayout secondary=row();Button manual=button("手工记录",false),help=button("设备与帮助",false);
        secondary.addView(manual,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams helpLayout=new LinearLayout.LayoutParams(0,-2,1);helpLayout.leftMargin=dp(8);secondary.addView(help,helpLayout);add(content,secondary,12,16);
        manual.setOnClickListener(v->showManual());help.setOnClickListener(v->showConnectionHelp());
        if(store.device()!=null)add(content,text("我的秤 · "+store.deviceName()+"\n"+(store.automaticWeighing()?"进入称重页自动寻找":"已关闭自动寻找，可手动开始"),12,MUTED,false),0,14);
        Measurement latest=records.isEmpty()?null:records.get(0);
        if(latest!=null && (saved==null || latest.id!=saved.id)){
            final Measurement previous=latest;LinearLayout history=card();history.addView(text("最近已保存 · "+formatTime(latest.measuredAt),12,MUTED,false));
            add(history,text(format(latest.weightKg)+" kg",26,INK,true),9,6);history.addView(text(sourceLabel(latest)+" · "+io.stridehub.app.sync.NasSync.rowStatus(store,latest.id),12,MUTED,false));
            history.setBackground(interactive(SURFACE,22));history.setClickable(true);history.setFocusable(true);history.setContentDescription("查看最近已保存记录，"+format(latest.weightKg)+" 千克，"+formatTime(latest.measuredAt));history.setOnClickListener(v->showDetail(previous));add(content,history,0,12);
        }
        Button sync=button("查看同步与备份",false);content.addView(sync);sync.setOnClickListener(v->{page=3;render();scroll.scrollTo(0,0);});
    }
    private String stageHeading(){
        if(pendingReview!=null)return "这是你的本次称重吗？";
        switch(captureState){
            case SEARCHING:return "直接上秤，正在等你";
            case CONNECTING:return "发现你的秤，正在准备";
            case READY:return "可以上秤了";
            case MEASURING:return "正在称重，请站稳";
            case SAVING:return "正在保存";
            case SAVED:return "这次称重已保存";
            case ERROR:return "暂时还没收到结果";
            default:return !autoResume?"已暂停等待":store.device()==null?"先添加你的体脂秤":"准备称重";
        }
    }
    private String primaryLabel(){
        if(pendingReview!=null)return "这是我的，保存到手机";
        if(client.isBusy())return "暂停等待";
        if(sessionFinished)return "再称一次";
        if(store.device()==null)return "添加体脂秤";
        if(!hasBlePermissions())return "允许"+permissionName();
        if(!bluetoothEnabled())return "打开蓝牙";
        return captureState==ScaleClient.State.ERROR?"重新寻找":"开始等待";
    }
    private void primaryAction(){
        if(pendingReview!=null){client.confirmPendingMeasurement();return;}
        if(client.isBusy()){pauseMeasurement("已暂停接收；点击开始等待即可继续。");return;}
        forcePicker=false;autoResume=true;sessionFinished=false;startMeasurement();
    }
    private void pauseMeasurement(String message){
        autoResume=false;currentWeight=null;pendingReview=null;client.stop();captureState=ScaleClient.State.IDLE;status=message;updateAwake();render();
    }
    private void showConnectionHelp(){
        LinearLayout body=column();body.setPadding(dp(22),dp(10),dp(22),dp(12));
        body.addView(text("第一次选好秤后，进入称重页会自动寻找，不必每次点连接。正常站上去即可；秤未亮时轻踩一次唤醒。",14,INK,false));
        add(body,text("搜索持续 90 秒，发现设备后最多等待 2 分钟。请将手机放在秤附近；如果原来的秤 App 正在占用连接，退出它后再试。离开 APP 会暂停接收，返回后继续尚未结束的等待。",13,MUTED,false),14,12);
        if(store.device()!=null)add(body,text("已记住："+store.deviceName()+"\n"+store.device(),12,MUTED,false),0,12);
        ScrollView wrapper=new ScrollView(this);wrapper.addView(body);
        new AlertDialog.Builder(this).setTitle("设备与连接帮助").setView(wrapper).setNegativeButton("关闭",null).setNeutralButton("连接诊断",(d,w)->showDiagnostics()).setPositiveButton("更换体脂秤",(d,w)->{forcePicker=true;autoResume=true;sessionFinished=false;startMeasurement();}).show();
    }
    private LinearLayout metric(String title,String value) { LinearLayout box=column(); box.addView(text(title,12,MUTED,false)); add(box,text(value,25,INK,true),7,0); return box; }
    private void renderTrend() {
        intro("看见变化", "每天最后一次测量，连成自己的节奏");
        LinearLayout types=row();types.addView(choice("体重",selectedMetric==Metric.WEIGHT,()->{selectedMetric=Metric.WEIGHT;render();}),new LinearLayout.LayoutParams(0,dp(48),1));
        types.addView(choice("体脂率",selectedMetric==Metric.BODY_FAT,()->{selectedMetric=Metric.BODY_FAT;render();}),new LinearLayout.LayoutParams(0,dp(48),1));
        Button more=choice("更多指标 ▾",selectedMetric!=Metric.WEIGHT&&selectedMetric!=Metric.BODY_FAT,this::chooseMetric);more.setTextSize(12);types.addView(more,new LinearLayout.LayoutParams(0,dp(48),1));content.addView(types);
        LinearLayout ranges=row();String[] names={"7 天","30 天","90 天","全部"};int[] options={7,30,90,0};
        for(int i=0;i<4;i++){final int d=options[i];Button button=choice(names[i],days==d,()->{days=d;render();});button.setTextSize(12);ranges.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));}add(content,ranges,9,16);
        long now=System.currentTimeMillis();
        List<Measurement> daily=DailySeries.latestPerDay(records,days,now);
        List<Measurement> valid=new ArrayList<>();for(Measurement m:daily)if(TrendChart.valueFor(m,selectedMetric)!=null)valid.add(m);
        boolean showAverage=selectedMetric==Metric.WEIGHT&&weightAverageDays>0;
        Map<String,Double> averages=showAverage?WeightInsights.movingAverages(records,weightAverageDays,now):Collections.emptyMap();
        LinearLayout chart=card();chart.addView(text(selectedMetric.title+(selectedMetric.unit.isEmpty()?"":"  /  "+selectedMetric.unit),12,MUTED,false));
        if(valid.isEmpty())add(chart,text("等待你的第一笔",25,INK,true),10,0);
        else {
            Measurement last=valid.get(valid.size()-1);double latest=TrendChart.valueFor(last,selectedMetric);
            Double displayed=showAverage?averages.get(last.localDay):latest;
            add(chart,text(metricNumber(displayed,selectedMetric)+(displayed==null?"":unitSuffix(selectedMetric)),32,INK,true),10,0);
            add(chart,text(last.localDay+" · "+(showAverage?weightAverageDays+" 日均线":"最近有效记录"),11,MUTED,false),6,0);
            if(showAverage)add(chart,text("当日实测  "+metricNumber(latest,selectedMetric)+" kg",12,MUTED,false),6,0);
            else {
                String grade=last.metrics==null?null:last.metrics.grades.get(selectedMetric);if(grade!=null)add(chart,text(grade,13,GREEN,false),6,0);
                if(valid.size()>1){double first=TrendChart.valueFor(valid.get(0),selectedMetric),delta=latest-first;add(chart,text("较区间首日  "+(delta>0?"+":"")+metricNumber(delta,selectedMetric)+("%".equals(selectedMetric.unit)?" 个百分点":unitSuffix(selectedMetric)),12,MUTED,false),5,0);}
                else add(chart,text("有了第二天的记录，就能看见变化",12,MUTED,false),5,0);
            }
        }
        if(selectedMetric==Metric.WEIGHT) {
            LinearLayout modes=row();String[] modeNames={"每日","7日均线","30日均线"};int[] modeDays={0,7,30};
            for(int i=0;i<modeDays.length;i++){final int average=modeDays[i];Button mode=choice(modeNames[i],weightAverageDays==average,()->{weightAverageDays=average;render();});mode.setTextSize(11);mode.setPadding(dp(2),dp(7),dp(2),dp(7));mode.setMinWidth(0);mode.setMinimumWidth(0);modes.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));}
            add(chart,modes,15,0);
            add(chart,text(showAverage?"绿线 · "+weightAverageDays+" 日均重    灰线 · 每日实测":"绿线 · 每日最后一次实测",11,MUTED,false),10,0);
        }
        TrendChart graph=new TrendChart(this);graph.setData(daily,selectedMetric,records,weightAverageDays,now);add(chart,graph,9,0);graph.getLayoutParams().height=dp(225);content.addView(chart);
        if(selectedMetric==Metric.WEIGHT) {
            add(content,text("均线按当日及此前 6 / 29 个自然日内有记录的天数计算，能平滑日常波动。窗口可使用图示范围之前的记录；缺测日不补值，也不会另存为测量。",12,MUTED,false),16,0);
            addWeightInsights(WeightInsights.summarize(records,now));
        } else {
            String note=isHeightIndex(selectedMetric)?"FMI / FFMI 由脂肪量、去脂体重与身高计算；不设原 App 分级。去脂体重包含水分、骨骼等，不能当作独立测得的肌肉量。":"身体成分为本机估算；已有分级沿用原 App 标准。";
            add(content,text("每天取最后一次测量。当天该指标缺失就留空，不用较早的测量补齐；全部记录仍保存在「记录」。"+note,12,MUTED,false),16,0);
        }
        if(!valid.isEmpty()){
            add(content,text("每日记录",17,INK,true),23,12);
            for(int i=valid.size()-1;i>=Math.max(0,valid.size()-7);i--){Measurement m=valid.get(i);LinearLayout row=row();TextView date=text(m.localDay,13,MUTED,false);row.addView(date,new LinearLayout.LayoutParams(0,-2,1));row.addView(text(metricNumber(TrendChart.valueFor(m,selectedMetric),selectedMetric)+unitSuffix(selectedMetric),14,INK,true));row.setPadding(0,dp(12),0,dp(12));row.setMinimumHeight(dp(48));row.setOnClickListener(v->showDetail(m));add(content,row,0,0);}
        }
    }
    private void addWeightInsights(WeightInsights.Summary summary) {
        LinearLayout insights=card();insights.addView(text("体重变化",18,INK,true));
        add(insights,text("截至 "+summary.average7.toDay+" · 按自然日统计",11,MUTED,false),6,14);
        LinearLayout averages=row();
        averages.addView(averageOverview("近 7 日均重",summary.average7,7),new LinearLayout.LayoutParams(0,-2,1));
        averages.addView(averageOverview("近 30 日均重",summary.average30,30),new LinearLayout.LayoutParams(0,-2,1));insights.addView(averages);
        add(insights,text("近 28 日每周变化",14,INK,true),22,7);
        add(insights,text(summary.weeklyKg==null?"—":signedWeight(summary.weeklyKg)+" kg / 周",24,INK,true),0,6);
        if(summary.weeklyPercent!=null)add(insights,text(signedWeight(summary.weeklyPercent)+" % / 周",13,MUTED,false),0,6);
        String trendDays=summary.trendCount+" 天有效记录";
        if(summary.trendFromDay!=null)trendDays+=" · "+summary.trendFromDay+" 至 "+summary.trendToDay;
        add(insights,text(trendDays,11,MUTED,false),0,5);
        add(insights,text(summary.weeklyKg==null?"至少需要 3 个不同日期的记录，首尾相隔 7 天，才显示每周趋势。":"根据每日体重拟合变化速度；百分比以近 28 日均重为基准。",11,MUTED,false),0,0);
        add(insights,text("自首日累计变化",14,INK,true),22,7);
        add(insights,text(summary.totalKg==null?"—":signedWeight(summary.totalKg)+" kg",24,INK,true),0,6);
        if(summary.totalPercent!=null)add(insights,text(signedWeight(summary.totalPercent)+" % · 相对首日体重",13,MUTED,false),0,6);
        add(insights,text(summary.firstDay==null?"保存记录后显示起止日期。":summary.firstDay+" 至 "+summary.lastDay+"\n"+summary.totalDays+" 天有效记录"+(summary.totalDays<2?" · 等待另一日的记录":""),11,MUTED,false),0,0);
        add(insights,text("每天仅取最后一次测量。没有数据时显示 —；短期变化可能来自水分，均线与趋势不用于判断肌肉增减。",11,MUTED,false),18,0);
        add(content,insights,18,0);
    }
    private LinearLayout averageOverview(String title,WeightInsights.Window window,int days) {
        LinearLayout box=column();box.addView(text(title,12,MUTED,false));
        add(box,text(window.mean==null?"—":format(window.mean)+" kg",21,INK,true),7,5);
        add(box,text(window.count+" / "+days+" 天有记录",11,MUTED,false),0,0);
        return box;
    }
    private String signedWeight(double value) {double rounded=Math.rint(value*100)/100;return String.format(Locale.CHINA,"%s%.2f",rounded>0?"+":"",rounded==0?0:rounded);}
    private boolean isHeightIndex(Metric metric) {return metric==Metric.FMI||metric==Metric.FFMI;}
    private void chooseMetric() {
        Metric[] metrics=Metric.values();String[] titles=new String[metrics.length];int selected=0;
        for(int i=0;i<metrics.length;i++){titles[i]=metrics[i].title+(metrics[i].unit.isEmpty()?"":" · "+metrics[i].unit);if(metrics[i]==selectedMetric)selected=i;}
        new AlertDialog.Builder(this).setTitle("选择趋势指标").setSingleChoiceItems(titles,selected,(dialog,index)->{selectedMetric=metrics[index];dialog.dismiss();render();}).setNegativeButton("取消",null).show();
    }
    private void renderHistory() {
        intro("日常留痕", "全部 "+records.size()+" 次测量 · 每一次都保留");
        Button add=button("＋  手工记录",true); content.addView(add,new LinearLayout.LayoutParams(-1,dp(52))); add.setOnClickListener(v->showManual());
        if(records.isEmpty()) { LinearLayout empty=card(); add(empty,text("还没有记录",23,INK,true),6,12); empty.addView(text("连接体脂秤完成第一次测量，\n也可以手工补录已有数据。",14,MUTED,false)); add(content,empty,22,0); return; }
        String day="";
        for(int i=0;i<Math.min(historyLimit,records.size());i++) { Measurement m=records.get(i); if(!m.localDay.equals(day)) { day=m.localDay; add(content,text(day,12,MUTED,true),23,10); }
            LinearLayout r=card(); r.setPadding(dp(17),dp(15),dp(17),dp(15)); LinearLayout top=row(); TextView weight=text(format(m.weightKg)+" kg",23,INK,true); top.addView(weight,new LinearLayout.LayoutParams(0,-2,1)); TextView body=text(m.bodyFatPercent==null?"体脂 —":format(m.bodyFatPercent)+" %",16,m.bodyFatPercent==null?MUTED:GREEN,true); top.addView(body); r.addView(top);
            add(r,text(new SimpleDateFormat("HH:mm",Locale.CHINA).format(m.measuredAt)+"  ·  "+sourceLabel(m)+"   ›",12,MUTED,false),7,0);add(r,text("本机已保存 · "+io.stridehub.app.sync.NasSync.rowStatus(store,m.id),12,MUTED,false),6,0);r.setBackground(interactive(SURFACE,22)); r.setClickable(true); r.setFocusable(true); r.setContentDescription(formatTime(m.measuredAt)+"，体重 "+format(m.weightKg)+" 千克，"+(m.bodyFatPercent==null?"体脂未知":"体脂率 "+format(m.bodyFatPercent)+"%，")+"本机已保存，"+io.stridehub.app.sync.NasSync.rowStatus(store,m.id)+"，查看详情"); r.setOnClickListener(v->showDetail(m)); add(content,r,0,9);
        }
        if(historyLimit<records.size()) { Button more=button("继续查看更早记录",false); content.addView(more); more.setOnClickListener(v->{int y=scroll.getScrollY();historyLimit+=50;render();scroll.post(()->scroll.scrollTo(0,y));}); }
    }
    private void renderSettings() {
        intro("同步与设置", "本机记录与 NAS 副本分别保留，可随时导出备份。");
        LinearLayout syncStatus=card();syncStatus.addView(text("NAS 同步",18,INK,true));add(syncStatus,text(io.stridehub.app.sync.NasSync.status(this),13,MUTED,false),10,0);add(content,syncStatus,0,12);
        Button pairNas=button("配对 NAS 并上传本机历史",false);content.addView(pairNas);
        pairNas.setOnClickListener(v->{try {
            if(!io.stridehub.app.NativeSession.signedIn(this)){toast("请先登录 StrideHub，再配对 NAS。称重仍可离线使用。");return;}
            String destination=io.stridehub.app.sync.NasSync.server(this).baseUrl();
            new AlertDialog.Builder(this).setTitle("上传到 NAS？").setMessage("目的地："+destination+"\n将上传未绑定的全部本机稳定测量及导入历史到当前 App 登录账号。同服务器及同登录名会更新原设备凭据并恢复待同步；其他绑定的记录不会自动转移。请确认当前账号正确。")
            .setNegativeButton("取消",null).setPositiveButton("确认配对并上传",(d,w)->{
                io.stridehub.app.sync.NasSync.pair(this,message->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){toast(message);render();}}));
            }).show();
        }catch(Exception e){toast("请返回 StrideHub 设置服务器并登录");}});
        Button syncNas=button("同步 / 网络恢复后重试",false);content.addView(syncNas);syncNas.setOnClickListener(v->io.stridehub.app.sync.NasSync.sync(this,message->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){toast(message);render();}})));
        Button disconnectNas=button("断开 NAS（保留 NAS 已上传副本）",false);content.addView(disconnectNas);disconnectNas.setOnClickListener(v->{io.stridehub.app.sync.NasSync.disconnect(this);render();});
        content.addView(text("同步错误或设备撤销时，请检查 NAS 后断开并重新配对；日记录冲突请在 NAS 检查手工日记录。删除本机记录会取消待同步，已上传 NAS 副本需在 NAS 管理端删除。",12,MUTED,false));

        add(content,text("个人资料与本机数据",20,INK,true),24,16);
        Profile p=store.profile(); LinearLayout profile=card(); profile.addView(text("个人资料",18,INK,true));
        add(profile,text(p==null?"连接前先填写身高、出生日期与性别。\n这些资料用于体脂估算；配对 NAS 后会随测量快照上传。":p.heightCm+" cm  ·  "+(p.male?"男":"女")+"  ·  "+p.ageAt(System.currentTimeMillis())+" 岁\n出生日期  "+p.birthDate,14,MUTED,false),12,14);
        Button edit=button(p==null?"填写个人资料":"编辑个人资料",false); profile.addView(edit); edit.setOnClickListener(v->showProfile(null)); content.addView(profile);
        LinearLayout data=card(); data.addView(text("数据与备份",18,INK,true)); add(data,text("已保存 "+records.size()+" 次测量。卸载应用会删除本机数据，请按需导出 CSV 留存。",13,MUTED,false),10,12);
        Button migration=button("导入旧 TinyYumi 记录",false); data.addView(migration);
        migration.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("迁移旧称重记录")
            .setMessage("先在旧 TinyYumi 的设置中导出全部记录 CSV，再选择文件。重复导入同一记录会跳过；原 App 的记录不会删除。请核对条数和日期后再停用旧 App。")
            .setNegativeButton("取消",null).setPositiveButton("选择 CSV",(d,w)->{
                try {startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT);}
                catch(ActivityNotFoundException e){showMessage("无法选择文件","请安装或启用系统文件管理器。");}
            }).show());
        Button export=button("导出全部记录 · CSV",false); data.addView(export); export.setOnClickListener(v->requestExport("csv")); add(content,data,16,0);
        LinearLayout device=card(); device.addView(text("体脂秤",18,INK,true)); add(device,text(store.device()==null?"还未记住设备": "已记住设备  "+store.device(),12,MUTED,false),10,10);
        Switch automatic=new Switch(this);automatic.setText("进入称重页自动寻找我的秤");automatic.setTextColor(INK);automatic.setMinHeight(dp(48));automatic.setChecked(store.automaticWeighing());device.addView(automatic);
        automatic.setOnCheckedChangeListener((v,checked)->{store.setAutomaticWeighing(checked);autoResume=checked;if(!checked&&client.isBusy())pauseMeasurement("自动等待已关闭，可手动开始称重。");});
        Button choose=button("扫描并选择设备",false); device.addView(choose); choose.setOnClickListener(v->{forcePicker=true;startMeasurement();});
        if(store.device()!=null) { Button forget=button("忘记这台设备",false); device.addView(forget); forget.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("忘记设备？").setMessage("下次测量时重新选择体脂秤，已有记录会保留。").setNegativeButton("取消",null).setPositiveButton("忘记",(d,w)->{pauseMeasurement("已忘记体脂秤，可重新添加。");store.rememberDevice(null);render();}).show()); }
        Button diagnostics=button("连接诊断",false); device.addView(diagnostics); diagnostics.setOnClickListener(v->showDiagnostics()); add(content,device,16,0);
        add(content,text("StrideHub · 身体记录\n体脂是估算值，会受饮水、运动等因素影响。\n已有分级沿用原 App 标准；FMI / FFMI 不设分级。仅作记录参考，不用于诊断。",12,MUTED,false),23,0);
    }
    private void showProfile(Runnable afterSave) {
        if(client.isBusy())pauseMeasurement("资料更新期间已暂停接收。");
        Profile previous=store.profile(); LinearLayout form=column(); form.setPadding(dp(24),dp(10),dp(24),dp(12));
        add(form,text("体脂估算需要这些资料。称重可离线使用；配对 NAS 后，资料会随测量快照上传，当前支持 18–80 岁成人。",13,MUTED,false),0,16);
        EditText height=field(form,"身高（厘米）","80–250",InputType.TYPE_CLASS_NUMBER); if(previous!=null) height.setText(Integer.toString(previous.heightCm));
        add(form,text("出生日期",13,INK,true),15,4);
        final String[] birth={previous==null?null:previous.birthDate}; Button birthday=button(birth[0]==null?"选择出生日期":birth[0],false); form.addView(birthday,new LinearLayout.LayoutParams(-1,dp(48)));
        birthday.setOnClickListener(v->{Calendar c=Calendar.getInstance(); c.add(Calendar.YEAR,-30); if(birth[0]!=null){String[] bits=birth[0].split("-");c.set(Integer.parseInt(bits[0]),Integer.parseInt(bits[1])-1,Integer.parseInt(bits[2]));}
            DatePickerDialog picker=new DatePickerDialog(this,(view,y,m,d)->{birth[0]=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d);birthday.setText(birth[0]);},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)); picker.getDatePicker().setMaxDate(System.currentTimeMillis()); picker.show(); });
        add(form,text("生理性别（用于体脂估算）",13,INK,true),15,4); RadioGroup sex=new RadioGroup(this); sex.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton male=new RadioButton(this); male.setId(View.generateViewId());male.setText("男");male.setTextColor(INK);male.setMinHeight(dp(48));
        RadioButton female=new RadioButton(this);female.setId(View.generateViewId());female.setText("女");female.setTextColor(INK);female.setMinHeight(dp(48));
        sex.addView(male,new RadioGroup.LayoutParams(0,dp(48),1));sex.addView(female,new RadioGroup.LayoutParams(0,dp(48),1));form.addView(sex);
        if(previous!=null) sex.check(previous.male?male.getId():female.getId());
        TextView error=text("",13,0xffae4f40,false); add(form,error,10,0);
        ScrollView wrapper=new ScrollView(this);wrapper.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("个人资料").setView(wrapper).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(sex.getCheckedRadioButtonId()==-1){error.setText("请选择生理性别");return;}
            int cm;try{cm=Integer.parseInt(height.getText().toString().trim());}catch(NumberFormatException e){error.setText("请输入身高");return;}
            int userId=previous==null?1+new java.security.SecureRandom().nextInt(65535):previous.userId;
            Profile profile=new Profile(cm,birth[0],sex.getCheckedRadioButtonId()==male.getId(),userId);String message=profile.validationError(System.currentTimeMillis());
            if(message!=null){error.setText(message);return;}
            store.saveProfile(profile);dialog.dismiss();render();if(afterSave!=null) afterSave.run();
        }));dialog.show();
    }
    private void showManual() {
        showManual(null);
    }
    private void showManual(Bundle draft) {
        pauseMeasurement("正在手工记录；保存后可再次开始称重。");
        LinearLayout form=column();form.setPadding(dp(24),dp(8),dp(24),dp(12));
        add(form,text("保存到本机。已配对 NAS 时会加入同步队列；体脂率未知可留空。",13,MUTED,false),0,16);
        EditText weight=field(form,"体重（kg）","例如 65.4",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);manualWeight=weight;
        LinearLayout fatField=column();add(form,fatField,14,0);EditText bodyFat=field(fatField,"体脂率（% · 选填）","未知时留空",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);manualFat=bodyFat;
        add(form,text("测量时间",13,INK,true),14,6);Calendar selected=Calendar.getInstance();selected.set(Calendar.SECOND,0);selected.set(Calendar.MILLISECOND,0);
        manualTime=selected;
        if(draft!=null){weight.setText(draft.getString("manualWeight",""));bodyFat.setText(draft.getString("manualFat",""));selected.setTimeInMillis(draft.getLong("manualTime",selected.getTimeInMillis()));}
        Button date=button(new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(selected.getTime()),false);
        Button time=button(new SimpleDateFormat("HH:mm",Locale.CHINA).format(selected.getTime()),false);
        form.addView(date,new LinearLayout.LayoutParams(-1,dp(48)));form.addView(time,new LinearLayout.LayoutParams(-1,dp(48)));
        date.setOnClickListener(v->{DatePickerDialog picker=new DatePickerDialog(this,(view,y,m,d)->{selected.set(y,m,d);date.setText(new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(selected.getTime()));},selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH));picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();});
        time.setOnClickListener(v->new TimePickerDialog(this,(view,h,m)->{selected.set(Calendar.HOUR_OF_DAY,h);selected.set(Calendar.MINUTE,m);time.setText(new SimpleDateFormat("HH:mm",Locale.CHINA).format(selected.getTime()));},selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true).show());
        TextView error=text("",13,0xff9b4033,false);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(form,error,9,0);
        ScrollView wrapper=new ScrollView(this);wrapper.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("手工记录").setView(wrapper).setNegativeButton("取消",null).setPositiveButton("保存到本机",null).create();manualDialog=dialog;
        dialog.setOnDismissListener(d->{if(manualDialog==dialog){manualDialog=null;manualWeight=null;manualFat=null;manualTime=null;}});
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            weight.setError(null);bodyFat.setError(null);error.setText("");
            try {
                if(selected.getTimeInMillis()>System.currentTimeMillis()){error.setText("测量时间不能晚于现在，请调整日期或时间");time.requestFocus();wrapper.smoothScrollTo(0,time.getBottom());return;}
                String w=weight.getText().toString().trim(),f=bodyFat.getText().toString().trim();
                double kg;
                try{kg=Double.parseDouble(w);}catch(NumberFormatException invalid){fieldError(weight,error,"请输入有效的体重，例如 65.4");return;}
                if(!Double.isFinite(kg)||kg<2||kg>300){fieldError(weight,error,"体重应在 2–300 千克之间");return;}
                Double fat=null;
                if(!f.isEmpty()){
                    try{fat=Double.valueOf(f);}catch(NumberFormatException invalid){fieldError(bodyFat,error,"请输入有效的体脂率，未知时请留空");return;}
                    if(!Double.isFinite(fat)||fat<1||fat>75){fieldError(bodyFat,error,"体脂率应在 1–75% 之间，未知时请留空");return;}
                }
                Measurement m=Measurement.create(store.profile(),selected.getTimeInMillis(),kg,fat,0,"手工录入",null,null);
                store.save(m);lastSavedId=m.id;sessionFinished=true;autoResume=false;captureState=ScaleClient.State.SAVED;status="已保存 "+format(m.weightKg)+" kg · "+formatTime(m.measuredAt);dialog.dismiss();render();toast("已保存到本机 · "+io.stridehub.app.sync.NasSync.rowStatus(store,m.id));
            } catch(IllegalArgumentException e){error.setText(e instanceof NumberFormatException?"请输入有效的数字":e.getMessage());}
            catch(Exception e){error.setText("保存失败，请检查设备存储空间后重试");}
        }));dialog.show();
    }
    private void fieldError(EditText input,TextView summary,String message){input.setError(message);input.requestFocus();summary.setText(message);}
    private void showDetail(Measurement m) {
        LinearLayout body=column();body.setPadding(dp(22),dp(6),dp(22),dp(20));
        add(body,text(formatTime(m.measuredAt)+"  ·  "+sourceLabel(m),12,MUTED,false),0,14);
        LinearLayout summary=card();summary.setBackground(shape(SOFT,18));summary.addView(text(format(m.weightKg)+" kg",34,INK,true));
        add(summary,text("测量原值 · 体脂 "+(m.bodyFatPercent==null?"—":format(m.bodyFatPercent)+" %")+"  /  BMI "+(m.bmi==null?"—":format(m.bmi)),12,MUTED,false),7,0);body.addView(summary);
        BodyMetrics derived=m.metrics;
        add(body,text("身体成分",18,INK,true),23,5);
        add(body,text("以下成分及分级由本机计算，受个人资料和测量状态影响，仅供日常记录。FMI / FFMI 不设分级；去脂体重包含水分、骨骼等，不能当作独立测得的肌肉量。",12,MUTED,false),3,8);
        Metric[] composition={Metric.BMI,Metric.BODY_FAT,Metric.MUSCLE,Metric.MUSCLE_MASS,Metric.WATER,Metric.WATER_MASS,Metric.PROTEIN,Metric.BONE,Metric.BONE_PERCENT,Metric.VISCERAL_FAT,Metric.FAT_MASS,Metric.LEAN_MASS,Metric.LEAN_PERCENT,Metric.FMI,Metric.FFMI};
        for(Metric metric:composition)addMetricDetail(body,m,metric);
        add(body,text("代谢与身体参考",18,INK,true),24,8);
        Metric[] reference={Metric.BMR,Metric.BODY_AGE,Metric.FAT_INDEX,Metric.OBESITY_DEGREE,Metric.SCORE};for(Metric metric:reference)addMetricDetail(body,m,metric);
        String shape=derived==null?null:derived.bodyShape;add(body,text("体型  ·  "+(shape==null?"—":shape),16,INK,true),13,13);
        Double low=TrendChart.valueFor(m,Metric.STANDARD_WEIGHT_MIN),high=TrendChart.valueFor(m,Metric.STANDARD_WEIGHT_MAX);
        add(body,text("正常体重范围",13,MUTED,false),7,6);add(body,text(low==null||high==null?"—":metricNumber(low,Metric.STANDARD_WEIGHT_MIN)+"–"+metricNumber(high,Metric.STANDARD_WEIGHT_MAX)+" kg",21,INK,true),0,12);
        Metric[] controls={Metric.WEIGHT_CONTROL,Metric.FAT_CONTROL,Metric.MUSCLE_CONTROL,Metric.WATER_CONTROL};for(Metric metric:controls)addMetricDetail(body,m,metric);
        add(body,text("未提供的测量",18,INK,true),24,8);add(body,text("心率  —\n八电极分段数据  —\n当前秤没有提供这些传感器数据。",13,MUTED,false),0,12);
        add(body,text("计算依据",18,INK,true),19,9);
        Profile profile=LocalStore.profileFromSnapshot(m.metricsProfileSnapshot,LocalStore.calculationTime(m));
        String profileText=profile==null?"缺少有效的测量时资料，依赖资料的指标保留为空。": "测量时资料  "+profile.heightCm+" cm · "+(profile.male?"男":"女")+" · "+profile.ageAt(LocalStore.calculationTime(m))+" 岁";
        add(body,text(profileText,12,MUTED,false),0,8);
        if(derived!=null){add(body,text(derived.calculationBasis==null?"本机估算":derived.calculationBasis,12,MUTED,false),0,8);add(body,text("计算版本  "+derived.algorithmVersion+(m.metricsCalculatedAt>0?"  ·  "+formatTime(m.metricsCalculatedAt):""),11,MUTED,false),0,8);}
        else add(body,text("身体指标暂未计算，原始测量已保存。",12,MUTED,false),0,8);
        add(body,text("阻抗  "+(m.impedance>0?m.impedance+" Ω":"—")+"\n原始值及当时资料会保留，修改个人资料不会重写这次测量。",12,MUTED,false),0,4);
        ScrollView wrapper=new ScrollView(this);wrapper.addView(body);
        new AlertDialog.Builder(this).setTitle("身体指标详情 · "+io.stridehub.app.sync.NasSync.rowStatus(store,m.id)).setView(wrapper).setPositiveButton("关闭",null).setNeutralButton("删除记录",(d,w)->new AlertDialog.Builder(this).setTitle("删除这次测量？").setMessage(formatTime(m.measuredAt)+" · "+format(m.weightKg)+" kg\n删除后无法恢复。待同步将取消；发送中的记录可能已到达 NAS，请在 NAS 检查。已上传的 NAS 副本会保留，需在 NAS 管理端删除。").setNegativeButton("保留",null).setPositiveButton("删除",(confirm,which)->{deleteRecord(m.id);}).show()).show();
    }
    private void deleteRecord(long id) {
        store.delete(id);
        if(id==lastSavedId){lastSavedId=0;sessionFinished=false;pauseMeasurement("这次记录已删除，可重新开始称重。");}
        else render();
        toast("记录已删除");
    }
    private String permissionName(){return Build.VERSION.SDK_INT>=31?"附近设备权限":"蓝牙扫描所需定位权限";}
    private void addMetricDetail(LinearLayout parent,Measurement m,Metric metric) {
        Double value=TrendChart.valueFor(m,metric);LinearLayout entry=column();entry.setPadding(0,dp(11),0,dp(11));
        LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(text(metric.title,14,INK,false),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(text(metricNumber(value,metric)+(value==null?"":unitSuffix(metric)),18,INK,true));entry.addView(heading);
        String grade=m.metrics==null?null:m.metrics.grades.get(metric),reference=m.metrics==null?null:m.metrics.references.get(metric);
        Double score=m.metrics==null?null:m.metrics.scores.get(metric);
        String assessment=(grade==null?"":grade)+(score==null?"":(grade==null?"":" · ")+"评分 "+format(score)+" / 99");
        if(value!=null&&!assessment.isEmpty())add(entry,text(assessment,12,GREEN,false),6,0);
        if(value!=null&&reference!=null&&!reference.isEmpty())add(entry,text((isHeightIndex(metric)?"计算说明：":"原 App 参考：")+reference,11,MUTED,false),5,0);
        parent.addView(entry);View divider=new View(this);divider.setBackgroundColor(LINE);parent.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private String metricNumber(Double value,Metric metric){return TrendChart.formatValue(value,metric);}
    private String unitSuffix(Metric metric){return metric.unit.isEmpty()?"":" "+metric.unit;}
    private boolean hasBlePermissions(){
        if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean bluetoothEnabled(){
        try{BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();return adapter!=null && adapter.isEnabled();}catch(SecurityException e){return false;}
    }
    private boolean locationReady(){
        if(Build.VERSION.SDK_INT>30)return true;
        LocationManager location=(LocationManager)getSystemService(LOCATION_SERVICE);
        try{return location!=null && (location.isProviderEnabled(LocationManager.GPS_PROVIDER)||location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));}catch(Exception e){return false;}
    }
    private void maybeAutoStart(){
        if(!foreground||page!=0||permissionPending||client.isBusy()||sessionFinished||!autoResume||manualDialog!=null)return;
        if(store.device()==null)return;
        Profile profile=store.profile();
        if(profile==null||profile.validationError(System.currentTimeMillis())!=null){status="请先补充身体资料，再开始称重。";render();return;}
        if(!hasBlePermissions()){status="开启"+permissionName()+"后，即可自动寻找你的秤。";render();return;}
        if(!bluetoothEnabled()){status="蓝牙已关闭。打开蓝牙后会继续寻找你的秤。";render();return;}
        if(!locationReady()){status="此 Android 版本需要打开系统定位开关才能发现蓝牙秤。";render();return;}
        beginScan(profile);
    }
    private void startMeasurement() {
        autoResume=true;sessionFinished=false;
        Profile profile=store.profile();
        if(profile==null||profile.validationError(System.currentTimeMillis())!=null){showProfile(this::startMeasurement);return;}
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_CONNECT);}
        else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!missing.isEmpty()){permissionPending=true;requestPermissions(missing.toArray(new String[0]),PERMISSIONS);return;}
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null||!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){onError("设备不支持蓝牙低功耗，仍可手工记录。");return;}
        try{if(!adapter.isEnabled()){permissionPending=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT);return;}}
        catch(SecurityException e){onError("请在系统设置中允许附近设备权限。");return;}
        if(!locationReady()){
            new AlertDialog.Builder(this).setTitle("开启系统定位开关").setMessage("此 Android 版本通过定位开关控制蓝牙扫描，APP 不读取位置。开启后返回，会继续寻找体脂秤。")
                .setNegativeButton("稍后",(d,w)->pauseMeasurement("系统定位开关尚未开启，可在准备好后继续。"))
                .setPositiveButton("打开设置",(d,w)->{externalSettings=true;startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));}).show();return;
        }
        beginScan(profile);
    }
    private void beginScan(Profile profile){
        client.stop();found.clear();currentWeight=null;pendingReview=null;captureState=ScaleClient.State.SEARCHING;status="正在等待体脂秤的信号，你可以直接上秤。";page=0;render();
        String preferred=forcePicker?null:store.device();if(preferred==null)showDevicePicker();
        client.startScan(profile,preferred,preferred==null?"unknown":store.deviceType());updateAwake();
    }
    private void showDevicePicker() {
        if(deviceDialog!=null&&deviceDialog.isShowing())deviceDialog.dismiss();
        LinearLayout box=column();box.setPadding(dp(24),dp(10),dp(24),dp(16));
        pickerStatus=text("踩亮体脂秤即可发现。第一次选择后会记住，下次自动寻找。",13,MUTED,false);box.addView(pickerStatus);
        deviceList=column();add(box,deviceList,12,0);ScrollView wrapper=new ScrollView(this);wrapper.addView(box);
        deviceDialog=new AlertDialog.Builder(this).setTitle("添加你的体脂秤").setView(wrapper).setNegativeButton("取消",(d,w)->pauseMeasurement("已取消添加体脂秤。")).create();
        deviceDialog.setOnCancelListener(d->pauseMeasurement("已取消添加体脂秤。"));deviceDialog.show();
    }
    @Override public void onDeviceFound(ScaleClient.Device device) {
        found.put(device.address,device);
        if(device.address.equals(store.device()))store.rememberDevice(device.address,device.name,device.transportType());
        if(deviceList==null||deviceDialog==null||!deviceDialog.isShowing())return;
        deviceList.removeAllViews();
        for(ScaleClient.Device item:found.values()){
            String strength=item.rssi>=-65?"信号较好":item.rssi>=-85?"已收到信号":"信号较弱，可将手机靠近";
            Button b=button(item.name+"\n"+strength+" · "+item.address,false);b.setTextSize(13);b.setSingleLine(false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(12),dp(10),dp(12),dp(10));add(deviceList,b,0,8);b.getLayoutParams().height=dp(76);
            b.setOnClickListener(v->{
                store.rememberDevice(item.address,item.name,item.transportType());forcePicker=false;
                deviceDialog.dismiss();deviceList=null;pickerStatus=null;
                if(client.isBusy()){client.connect(item,store.profile());render();}
                else startMeasurement();
            });
        }
    }
    @Override public void onStateChanged(ScaleClient.State state,String message){
        captureState=state;status=message;
        if(state!=ScaleClient.State.MEASURING)currentWeight=null;
        if(state!=ScaleClient.State.REVIEW)pendingReview=null;
        updateAwake();if(shell!=null)render();
        if(pickerStatus!=null)pickerStatus.setText(message+(found.isEmpty()?"":"\n请选择你要使用的设备。"));
    }
    @Override public void onStatus(String message) {
        status=message;if(statusLabel!=null)statusLabel.setText(message);if(pickerStatus!=null)pickerStatus.setText(message);updateAwake();
    }
    @Override public void onLiveWeight(double weightKg) {
        currentWeight=weightKg;captureState=ScaleClient.State.MEASURING;pendingReview=null;
        if(liveValue!=null){liveValue.setText(format(weightKg));liveCaption.setText("实时读数 · 站稳后自动保存");if(stageTitle!=null)stageTitle.setText(stageHeading());if(liveFat!=null)liveFat.setText("—");if(liveBmi!=null)liveBmi.setText("—");if(liveSource!=null)liveSource.setText("等待稳定结果 · 尚未保存");if(liveDetail!=null)liveDetail.setVisibility(View.GONE);}
    }
    @Override public void onConfirmationRequired(Measurement measurement,String explanation){pendingReview=measurement;reviewExplanation=explanation;currentWeight=null;captureState=ScaleClient.State.REVIEW;page=0;render();scroll.scrollTo(0,0);}
    @Override public void onMeasurement(Measurement measurement) {
        try{store.save(measurement);}catch(Exception e){client.stop();onError("记录保存失败，请检查设备存储空间后重试。");return;}
        lastSavedId=measurement.id;sessionFinished=true;autoResume=false;currentWeight=null;pendingReview=null;
        client.confirmSaved();captureState=ScaleClient.State.SAVED;status="测量完成，已保存到本机。";
        if(deviceDialog!=null&&deviceDialog.isShowing())deviceDialog.dismiss();deviceList=null;pickerStatus=null;
        render();updateAwake();shell.performHapticFeedback(Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.KEYBOARD_TAP);toast("已保存 "+format(measurement.weightKg)+" kg");
    }
    @Override public void onError(String message){autoResume=false;captureState=ScaleClient.State.ERROR;currentWeight=null;pendingReview=null;status=message;render();updateAwake();if(pickerStatus!=null)pickerStatus.setText(message);}
    private void updateAwake(){
        if(foreground && client!=null && client.isBusy())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);if(requestCode!=PERMISSIONS)return;permissionPending=false;
        boolean granted=grants.length>0;for(int value:grants)granted&=value==PackageManager.PERMISSION_GRANTED;
        if(granted)startMeasurement();else{onError(permissionName()+"未开启，可允许后重试，或手工记录。");new AlertDialog.Builder(this).setTitle("允许"+permissionName()).setMessage("蓝牙称重需要"+permissionName()+"。拒绝不影响查看历史或手工记录。")
            .setNegativeButton("稍后",null).setPositiveButton("应用设置",(d,w)->{externalSettings=true;startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}).show();}
    }
    private void requestExport(String kind) {
        exportKind=kind;String stamp=new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(System.currentTimeMillis());
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("csv".equals(kind)?"text/csv":"text/plain").putExtra(Intent.EXTRA_TITLE,"StrideHub-"+("csv".equals(kind)?"记录-":"连接诊断-")+stamp+("csv".equals(kind)?".csv":".txt"));
        try {startActivityForResult(intent,EXPORT);}catch(ActivityNotFoundException e){showMessage("无法选择保存位置","当前系统没有可用的文档保存程序。");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==ENABLE_BT){permissionPending=false;if(resultCode==RESULT_OK)startMeasurement();else onError("蓝牙未开启。开启后即可继续称重。");return;}
        if(requestCode==IMPORT && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
            final android.net.Uri importUri=data.getData();
            final android.content.Context appContext=getApplicationContext();
            new Thread(()->{try(LocalStore importStore=new LocalStore(appContext);java.io.InputStream input=appContext.getContentResolver().openInputStream(importUri)) {
                if(input==null)throw new java.io.IOException();
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;
                while((count=input.read(chunk))!=-1){if(buffer.size()+count>8*1024*1024)throw new java.io.IOException("too large");buffer.write(chunk,0,count);}
                int imported=importStore.importCsv(buffer.toString("UTF-8"));runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){render();showMessage("导入完成","新增 "+imported+" 条。请核对日期与体重，旧 App 暂时保留。");}});
            }catch(Exception e){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())showMessage("未导入记录","文件不完整、格式不支持或超过 8 MB。请从旧 TinyYumi 重新导出 CSV；本机数据未修改。");});}},"history-import").start();
            return;
        }
        if(requestCode!=EXPORT||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        try(OutputStream output=getContentResolver().openOutputStream(data.getData(),"wt")){
            if(output==null)throw new java.io.IOException("No document output");
            output.write(("csv".equals(exportKind)?csv():client.diagnostics()).getBytes(StandardCharsets.UTF_8));output.flush();toast("导出完成");
        }catch(Exception e){showMessage("导出失败","无法写入所选位置，请换一个文件夹重试。");}
    }
    private String csv() {
        StringBuilder result=new StringBuilder("\ufeff记录ID,测量时间,记录时本地日期,体重kg,体脂率%,BMI,阻抗,来源,设备地址,测量时资料快照");
        for(Metric metric:Metric.values()){result.append(',').append("计算_").append(metric.title).append(metric.unit);result.append(',').append(metric.title).append("_分级");result.append(',').append(metric.title).append("_参考范围");result.append(',').append(metric.title).append("_评分");}
        result.append(",体型,算法版本,计算时间,计算资料快照,计算依据\r\n");SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);
        List<Measurement> all=store.all();
        for(int i=all.size()-1;i>=0;i--){
            Measurement m=all.get(i);List<String> cells=new ArrayList<>();
            cells.add(Long.toString(m.id));cells.add(date.format(m.measuredAt));cells.add(m.localDay);cells.add(Double.toString(m.weightKg));cells.add(m.bodyFatPercent==null?null:Double.toString(m.bodyFatPercent));cells.add(m.bmi==null?null:Double.toString(m.bmi));cells.add(m.impedance==0?null:Integer.toString(m.impedance));cells.add(sourceLabel(m));cells.add(m.deviceAddress);cells.add(m.profileSnapshot);
            for(Metric metric:Metric.values()){Double value=m.metrics==null?null:m.metrics.get(metric);cells.add(value==null?null:Double.toString(value));cells.add(m.metrics==null?null:m.metrics.grades.get(metric));cells.add(m.metrics==null?null:m.metrics.references.get(metric));Double score=m.metrics==null?null:m.metrics.scores.get(metric);cells.add(score==null?null:Double.toString(score));}
            cells.add(m.metrics==null?null:m.metrics.bodyShape);cells.add(m.metrics==null?null:Integer.toString(m.metrics.algorithmVersion));cells.add(m.metricsCalculatedAt<=0?null:date.format(m.metricsCalculatedAt));cells.add(m.metricsProfileSnapshot);cells.add(m.metrics==null?null:m.metrics.calculationBasis);
            for(int j=0;j<cells.size();j++){if(j>0)result.append(',');String cell=cells.get(j);result.append('"').append(cell==null?"":cell.replace("\"","\"\"")).append('"');}result.append("\r\n");
        }
        return result.toString();
    }
    private void showDiagnostics() {
        TextView body=text(client.diagnostics(),12,INK,false);body.setTextIsSelectable(true);body.setPadding(dp(22),dp(12),dp(22),dp(12));ScrollView wrapper=new ScrollView(this);wrapper.addView(body);
        new AlertDialog.Builder(this).setTitle("连接诊断 · 本机").setView(wrapper).setNegativeButton("关闭",null).setPositiveButton("导出文本",(d,w)->requestExport("diagnostics")).show();
    }
    @Override protected void onStart(){super.onStart();foreground=true;if(store!=null&&shell!=null)render();}
    @Override protected void onResume(){
        super.onResume();
        if(externalSettings){
            externalSettings=false;permissionPending=false;
            // Returning from Settings is not consent to show the same permission prompt again.
            if(hasBlePermissions()&&bluetoothEnabled()&&locationReady())startMeasurement();
            else onError("系统设置尚未就绪，请开启"+permissionName()+"、蓝牙及所需开关后再试。");
        }else maybeAutoStart();
    }
    @Override protected void onStop() {
        foreground=false;boolean interrupted=client.isBusy();client.stop();currentWeight=null;pendingReview=null;
        if(deviceDialog!=null&&deviceDialog.isShowing())deviceDialog.dismiss();deviceList=null;pickerStatus=null;
        if(sessionFinished){captureState=ScaleClient.State.SAVED;status="测量完成，已保存到本机。";}
        if(interrupted&&!sessionFinished){captureState=ScaleClient.State.IDLE;status=autoResume?"接收已暂停，返回称重页会继续等待。":"已暂停接收。";}
        updateAwake();super.onStop();
    }
    @Override protected void onDestroy(){client.stop();store.close();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);pageScroll[page]=scroll.getScrollY();out.putIntArray("pageScroll",pageScroll);out.putInt("page",page);out.putInt("days",days);out.putString("metric",selectedMetric.name());out.putInt("weightAverageDays",weightAverageDays);out.putBoolean("forcePicker",forcePicker);out.putString("exportKind",exportKind);out.putLong("lastSavedId",lastSavedId);out.putBoolean("autoResume",autoResume);out.putBoolean("externalSettings",externalSettings);out.putBoolean("permissionPending",permissionPending);out.putBoolean("sessionFinished",sessionFinished);if(manualDialog!=null&&manualDialog.isShowing()){out.putBoolean("manualOpen",true);out.putString("manualWeight",manualWeight.getText().toString());out.putString("manualFat",manualFat.getText().toString());out.putLong("manualTime",manualTime.getTimeInMillis());}}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String value,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(7),dp(12),dp(7));b.setTextColor(primary?ON_ACCENT:GREEN);b.setBackground(interactive(primary?GREEN:SOFT,14));b.setStateListAnimator(null);return b;}
    private Button choice(String title,boolean selected,Runnable action){Button b=button(title,false);b.setTextColor(selected?ON_ACCENT:MUTED);b.setBackground(interactive(selected?GREEN:PAPER,14));b.setSelected(selected);b.setOnClickListener(v->action.run());return b;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(shape(SURFACE,22));return v;}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private RippleDrawable interactive(int color,float radius){return new RippleDrawable(ColorStateList.valueOf(0x33326f57),shape(color,radius),shape(Color.WHITE,radius));}
    private void add(LinearLayout parent,View child,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));parent.addView(child,p);}
    private EditText field(LinearLayout parent,String label,String hint,int input){TextView caption=text(label,13,INK,true);parent.addView(caption);EditText edit=new EditText(this);edit.setId(View.generateViewId());caption.setLabelFor(edit.getId());edit.setContentDescription(label);edit.setTextColor(INK);edit.setTextSize(16);edit.setHint(hint);edit.setSingleLine(true);edit.setInputType(input);edit.setMinHeight(dp(54));add(parent,edit,5,0);return edit;}
    private String sourceLabel(Measurement m){if(m.source==null)return "未知来源";if("manual".equals(m.source))return "手工录入";return m.source;}
    private String format(double value){return String.format(Locale.CHINA,"%.1f",value);}
    private String formatTime(long when){return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(when);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void showMessage(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
}
