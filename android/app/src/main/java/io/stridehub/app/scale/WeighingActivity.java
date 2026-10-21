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
    private static final int PAPER=0xfff6f7f2, INK=0xff203d31, MUTED=0xff758078, GREEN=0xff387a61, SOFT=0xffe8efe7, LINE=0xffe3e8df;
    private static final int PERMISSIONS=51, ENABLE_BT=52, EXPORT=53, IMPORT=54;
    private LocalStore store;
    private ScaleClient client;
    private LinearLayout shell, content, nav;
    private ScrollView scroll;
    private TextView statusLabel, liveValue, liveCaption, liveFat, liveBmi, liveSource, pickerStatus;
    private Button measureButton;
    private int page=0, days=30, historyLimit=50, weightAverageDays=0;
    private boolean forcePicker=false;
    private Metric selectedMetric=Metric.WEIGHT;
    private String status="准备好，就站上秤", exportKind="csv";
    private List<Measurement> records=new ArrayList<>();
    private AlertDialog deviceDialog;
    private LinearLayout deviceList;
    private final LinkedHashMap<String,ScaleClient.Device> found=new LinkedHashMap<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null) { page=saved.getInt("page"); days=saved.getInt("days",30); try{selectedMetric=Metric.valueOf(saved.getString("metric","WEIGHT"));}catch(IllegalArgumentException ignored){} forcePicker=saved.getBoolean("forcePicker"); exportKind=saved.getString("exportKind","csv"); }
        if(saved!=null) {int average=saved.getInt("weightAverageDays",0);weightAverageDays=average==7||average==30?average:0;}
        io.stridehub.app.sync.NasSync.initialize(this);
        store=new LocalStore(this); client=new ScaleClient(this,this);
        buildShell(); render();
    }
    private void buildShell() {
        getWindow().setStatusBarColor(PAPER); getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell=column(); shell.setBackgroundColor(PAPER);
        shell.setOnApplyWindowInsetsListener((view,insets)-> {
            if(Build.VERSION.SDK_INT>=35) { android.graphics.Insets system=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(system.left,system.top,system.right,system.bottom); }
            return insets;
        });
        LinearLayout brand=row(); brand.setPadding(dp(24),dp(18),dp(24),dp(12)); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text("身体记录",25,INK,true); brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView offline=text("●  本机记录 · NAS 见设置",11,GREEN,false); offline.setPadding(dp(12),dp(7),dp(12),dp(7)); offline.setBackground(shape(SOFT,30)); brand.addView(offline);
        Button back=button("返回 StrideHub",false); back.setOnClickListener(v->finish());
        shell.addView(back);
        shell.addView(brand);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content=column(); content.setPadding(dp(24),dp(10),dp(24),dp(24)); scroll.addView(content);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=row(); nav.setPadding(dp(12),dp(8),dp(12),dp(8)); nav.setBackgroundColor(PAPER); shell.addView(nav);
        setContentView(shell);
    }
    private void render() {
        records=store.all(); content.removeAllViews(); statusLabel=null; measureButton=null; liveValue=null; liveCaption=null; liveFat=null; liveBmi=null; liveSource=null;
        if(page==0) renderHome(); else if(page==1) renderTrend(); else if(page==2) renderHistory(); else renderSettings();
        nav.removeAllViews(); String[] titles={"称重","趋势","记录","设置"};
        for(int i=0;i<titles.length;i++) { final int target=i; Button item=button(titles[i],false); item.setTextColor(page==i?GREEN:MUTED); item.setBackground(shape(page==i?SOFT:PAPER,16)); item.setTypeface(null,page==i?Typeface.BOLD:Typeface.NORMAL); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1); p.setMargins(dp(2),0,dp(2),0); nav.addView(item,p); item.setOnClickListener(v->{page=target;render();scroll.smoothScrollTo(0,0);}); }
    }
    private void intro(String title,String subtitle) { content.addView(text(title,29,INK,true)); TextView sub=text(subtitle,13,MUTED,false); sub.setLineSpacing(dp(3),1); add(content,sub,7,22); }
    private void renderHome() {
        intro("记录身体的日常", "StrideHub  /  留下身体的日常变化");
        LinearLayout hero=card(); hero.setPadding(dp(24),dp(23),dp(24),dp(23)); hero.setBackground(shape(0xffe8efe6,24));
        Measurement latest=records.isEmpty()?null:records.get(0);
        liveCaption=text(latest==null?"你的第一条记录，从这里开始":"最近一次  ·  "+formatTime(latest.measuredAt),12,MUTED,false); hero.addView(liveCaption);
        LinearLayout weight=row(); weight.setGravity(Gravity.BOTTOM);
        liveValue=text(latest==null?"—":format(latest.weightKg),66,INK,true); weight.addView(liveValue);
        TextView unit=text("kg",20,MUTED,false); unit.setPadding(dp(9),0,0,dp(13)); weight.addView(unit); add(hero,weight,10,16);
        View line=new View(this); line.setBackgroundColor(0xffd4dfd0); hero.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout metrics=row();
        LinearLayout fatMetric=metric("体脂率",latest==null||latest.bodyFatPercent==null?"—":format(latest.bodyFatPercent)+" %");liveFat=(TextView)fatMetric.getChildAt(1);
        LinearLayout bmiMetric=metric("BMI",latest==null||latest.bmi==null?"—":format(latest.bmi));liveBmi=(TextView)bmiMetric.getChildAt(1);
        metrics.addView(fatMetric,new LinearLayout.LayoutParams(0,-2,1));metrics.addView(bmiMetric,new LinearLayout.LayoutParams(0,-2,1));add(hero,metrics,19,0);
        liveSource=text(latest==null?"暂无已保存的测量":sourceLabel(latest)+"  ·  已保存到本机",11,MUTED,false);add(hero,liveSource,15,0);
        if(latest!=null){Button details=button("查看全部身体指标  ›",false);add(hero,details,15,0);details.setOnClickListener(v->showDetail(latest));}
        content.addView(hero);
        LinearLayout connection=card();
        TextView title=text("与身体，保持连接",17,INK,true); connection.addView(title);
        statusLabel=text(status,13,MUTED,false); statusLabel.setLineSpacing(dp(3),1); add(connection,statusLabel,8,15);
        measureButton=button(client.isBusy()?"停止连接":"连接体脂秤",true); connection.addView(measureButton,new LinearLayout.LayoutParams(-1,dp(52)));
        measureButton.setOnClickListener(v->{if(client.isBusy()){client.stop();status="已停止连接";render();}else{forcePicker=false;startMeasurement();}});
        if(store.device()!=null) { Button change=button("选择另一台秤",false); add(connection,change,5,0); change.setOnClickListener(v->{forcePicker=true;startMeasurement();}); }
        add(content,connection,16,0);
        Button manual=button("＋  手工记录",false); add(content,manual,11,0); manual.setOnClickListener(v->showManual());
        add(content,text("赤脚站稳，等待结果。体脂缺失时只保存体重。\n本机估算可能与原 App 不同，数据仅用于日常记录。",12,MUTED,false),16,0);
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
            add(r,text(new SimpleDateFormat("HH:mm",Locale.CHINA).format(m.measuredAt)+"  ·  "+sourceLabel(m)+"   ›",12,MUTED,false),7,0); r.setClickable(true); r.setFocusable(true); r.setContentDescription(formatTime(m.measuredAt)+"，体重 "+format(m.weightKg)+" 千克，"+(m.bodyFatPercent==null?"体脂未知":"体脂率 "+format(m.bodyFatPercent)+"%，")+"查看详情"); r.setOnClickListener(v->showDetail(m)); add(content,r,0,9);
        }
        if(historyLimit<records.size()) { Button more=button("继续查看更早记录",false); content.addView(more); more.setOnClickListener(v->{int y=scroll.getScrollY();historyLimit+=50;render();scroll.post(()->scroll.scrollTo(0,y));}); }
    }
    private void renderSettings() {
        content.addView(text(io.stridehub.app.sync.NasSync.status(this),13,MUTED,false));
        Button pairNas=button("配对 NAS 并上传本机历史",false);content.addView(pairNas);
        pairNas.setOnClickListener(v->{try {
            String destination=io.stridehub.app.sync.NasSync.server(this).baseUrl();
            new AlertDialog.Builder(this).setTitle("上传到 NAS？").setMessage("目的地："+destination+"\n将上传未绑定的全部本机稳定测量及导入历史到当前网页登录账号。同服务器及同登录名会更新原设备凭据并恢复待同步；其他绑定的记录不会自动转移。请确认网页登录账号正确。")
            .setNegativeButton("取消",null).setPositiveButton("确认配对并上传",(d,w)->{
                String cookie=android.webkit.CookieManager.getInstance().getCookie(io.stridehub.app.sync.NasSync.server(this).route("/admin/health/api/collection/csrf"));
                io.stridehub.app.sync.NasSync.pair(this,cookie,message->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){toast(message);render();}}));
            }).show();
        }catch(Exception e){toast("请返回 StrideHub 设置服务器并登录");}});
        Button syncNas=button("同步 / 网络恢复后重试",false);content.addView(syncNas);syncNas.setOnClickListener(v->io.stridehub.app.sync.NasSync.sync(this,message->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){toast(message);render();}})));
        Button disconnectNas=button("断开 NAS（保留 NAS 已上传副本）",false);content.addView(disconnectNas);disconnectNas.setOnClickListener(v->{io.stridehub.app.sync.NasSync.disconnect(this);render();});
        content.addView(text("同步错误或设备撤销时，请检查 NAS 后断开并重新配对；日记录冲突请在 NAS 检查手工日记录。删除本机记录会取消待同步，已上传 NAS 副本需在 NAS 管理端删除。",12,MUTED,false));

        intro("只属于你", "称重可离线使用；当前记录保存在此手机");
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
        Button choose=button("扫描并选择设备",false); device.addView(choose); choose.setOnClickListener(v->{forcePicker=true;startMeasurement();});
        if(store.device()!=null) { Button forget=button("忘记这台设备",false); device.addView(forget); forget.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("忘记设备？").setMessage("下次测量时重新选择体脂秤，已有记录会保留。").setNegativeButton("取消",null).setPositiveButton("忘记",(d,w)->{client.stop();store.rememberDevice(null);render();}).show()); }
        Button diagnostics=button("连接诊断",false); device.addView(diagnostics); diagnostics.setOnClickListener(v->showDiagnostics()); add(content,device,16,0);
        add(content,text("StrideHub · 身体记录\n体脂是估算值，会受饮水、运动等因素影响。\n已有分级沿用原 App 标准；FMI / FFMI 不设分级。仅作记录参考，不用于诊断。",12,MUTED,false),23,0);
    }
    private void showProfile(Runnable afterSave) {
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
        LinearLayout form=column();form.setPadding(dp(24),dp(8),dp(24),dp(12));
        EditText weight=field(form,"体重（kg）","例如 65.4",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        add(form,text("体脂率（% · 选填）",13,INK,true),14,5); EditText bodyFat=new EditText(this); bodyFat.setTextColor(INK);bodyFat.setTextSize(16);bodyFat.setHint("未知时留空");bodyFat.setSingleLine(true);bodyFat.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);form.addView(bodyFat,new LinearLayout.LayoutParams(-1,dp(54)));
        add(form,text("测量时间",13,INK,true),14,6);Calendar selected=Calendar.getInstance();selected.set(Calendar.SECOND,0);selected.set(Calendar.MILLISECOND,0);
        Button date=button(new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(selected.getTime()),false);
        Button time=button(new SimpleDateFormat("HH:mm",Locale.CHINA).format(selected.getTime()),false);
        form.addView(date,new LinearLayout.LayoutParams(-1,dp(48)));form.addView(time,new LinearLayout.LayoutParams(-1,dp(48)));
        date.setOnClickListener(v->{DatePickerDialog picker=new DatePickerDialog(this,(view,y,m,d)->{selected.set(y,m,d);date.setText(new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(selected.getTime()));},selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH));picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();});
        time.setOnClickListener(v->new TimePickerDialog(this,(view,h,m)->{selected.set(Calendar.HOUR_OF_DAY,h);selected.set(Calendar.MINUTE,m);time.setText(new SimpleDateFormat("HH:mm",Locale.CHINA).format(selected.getTime()));},selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true).show());
        TextView error=text("",13,0xffae4f40,false);add(form,error,9,0);
        ScrollView wrapper=new ScrollView(this);wrapper.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("手工记录").setView(wrapper).setNegativeButton("取消",null).setPositiveButton("保存记录",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                if(selected.getTimeInMillis()>System.currentTimeMillis()){error.setText("测量时间不能晚于现在");return;}
                String w=weight.getText().toString().trim(),f=bodyFat.getText().toString().trim();
                if(w.isEmpty()){error.setText("请输入体重");return;}
                Measurement m=Measurement.create(store.profile(),selected.getTimeInMillis(),Double.parseDouble(w),f.isEmpty()?null:Double.valueOf(f),0,"手工录入",null,null);
                store.save(m);dialog.dismiss();render();toast("记录已保存");
            } catch(IllegalArgumentException e){error.setText(e instanceof NumberFormatException?"请输入有效的数字":e.getMessage());}
            catch(Exception e){error.setText("保存失败，请检查设备存储空间后重试");}
        }));dialog.show();
    }
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
        new AlertDialog.Builder(this).setTitle("身体指标详情 · "+io.stridehub.app.sync.NasSync.rowStatus(store,m.id)).setView(wrapper).setPositiveButton("关闭",null).setNeutralButton("删除记录",(d,w)->new AlertDialog.Builder(this).setTitle("删除这次测量？").setMessage(formatTime(m.measuredAt)+" · "+format(m.weightKg)+" kg\n删除后无法恢复。待同步将取消；发送中的记录可能已到达 NAS，请在 NAS 检查。已上传的 NAS 副本会保留，需在 NAS 管理端删除。").setNegativeButton("保留",null).setPositiveButton("删除",(confirm,which)->{store.delete(m.id);render();toast("记录已删除");}).show()).show();
    }
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
    private void startMeasurement() {
        Profile profile=store.profile();
        if(profile==null||profile.validationError(System.currentTimeMillis())!=null){showProfile(this::startMeasurement);return;}
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_CONNECT);}
        else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),PERMISSIONS);return;}
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null||!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){showMessage("设备不支持蓝牙低功耗","仍可使用手工记录、趋势与 CSV 导出。");return;}
        try { if(!adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT);return;} }
        catch(SecurityException e){showMessage("蓝牙权限未开启","请在系统应用设置中允许「附近的设备」权限。");return;}
        if(Build.VERSION.SDK_INT<=30){LocationManager location=(LocationManager)getSystemService(LOCATION_SERVICE);boolean enabled=false;try{enabled=location!=null&&(location.isProviderEnabled(LocationManager.GPS_PROVIDER)||location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));}catch(Exception ignored){}
            if(!enabled){new AlertDialog.Builder(this).setTitle("请开启定位开关").setMessage("此 Android 版本需要开启定位服务才能扫描蓝牙设备。StrideHub 不会获取或保存你的位置。开启后返回，再点击连接。").setNegativeButton("取消",null).setPositiveButton("打开设置",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();return;}}
        client.stop();found.clear();status="正在寻找附近的云麦体脂秤…";page=0;render();
        String preferred=forcePicker?null:store.device();if(preferred==null)showDevicePicker();
        client.startScan(profile,preferred);
        if(measureButton!=null)measureButton.setText("停止连接");
    }
    private void showDevicePicker() {
        if(deviceDialog!=null&&deviceDialog.isShowing())deviceDialog.dismiss();
        LinearLayout box=column();box.setPadding(dp(24),dp(10),dp(24),dp(16));
        pickerStatus=text("请踩亮体脂秤，靠近手机。扫描最多持续 20 秒。",13,MUTED,false);box.addView(pickerStatus);
        deviceList=column();add(box,deviceList,12,0);ScrollView wrapper=new ScrollView(this);wrapper.addView(box);
        deviceDialog=new AlertDialog.Builder(this).setTitle("选择你的体脂秤").setView(wrapper).setNegativeButton("取消",(d,w)->{client.stop();status="已取消连接";render();}).create();
        deviceDialog.setOnCancelListener(d->{client.stop();status="已取消连接";render();});deviceDialog.show();
    }
    @Override public void onDeviceFound(ScaleClient.Device device) {
        found.put(device.address,device);
        if(deviceList==null||deviceDialog==null||!deviceDialog.isShowing())return;
        deviceList.removeAllViews();
        for(ScaleClient.Device item:found.values()){
            Button b=button((item.name==null||item.name.isEmpty()?"云麦体脂秤":item.name)+"\n"+item.address+"  ·  "+item.rssi+" dBm",false);b.setTextSize(13);b.setSingleLine(false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(12),dp(10),dp(12),dp(10));add(deviceList,b,0,8);b.getLayoutParams().height=dp(70);
            b.setOnClickListener(v->{deviceDialog.dismiss();deviceList=null;pickerStatus=null;client.connect(item,store.profile());});
        }
    }
    @Override public void onStatus(String message) {
        status=message;
        if(statusLabel!=null)statusLabel.setText(message);
        if(pickerStatus!=null)pickerStatus.setText(message+(found.isEmpty()?"\n请踩亮体脂秤，并靠近手机。":"\n点选你要使用的设备。"));
        if(measureButton!=null)measureButton.setText(client.isBusy()?"停止连接":"连接体脂秤");
    }
    @Override public void onLiveWeight(double weightKg) {
        if(liveValue!=null){liveValue.setText(format(weightKg));liveCaption.setText("实时读数 · 站稳后自动保存");if(liveFat!=null)liveFat.setText("—");if(liveBmi!=null)liveBmi.setText("—");if(liveSource!=null)liveSource.setText("等待稳定结果 · 尚未保存");}
    }
    @Override public void onMeasurement(Measurement measurement) {
        try {store.save(measurement);}
        catch(Exception e){client.stop();render();onError("记录保存失败，请检查设备存储空间后重试");return;}
        client.confirmSaved();
        if(measurement.deviceAddress!=null)store.rememberDevice(measurement.deviceAddress);
        status="测量完成，已保存到本机";
        if(deviceDialog!=null&&deviceDialog.isShowing())deviceDialog.dismiss();
        deviceList=null;pickerStatus=null;render();toast("已保存 "+format(measurement.weightKg)+" kg");
    }
    @Override public void onError(String message) {status=message;if(statusLabel!=null)statusLabel.setText(message);if(pickerStatus!=null)pickerStatus.setText(message);if(measureButton!=null)measureButton.setText(client.isBusy()?"停止连接":"连接体脂秤");toast(message);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);if(requestCode!=PERMISSIONS)return;
        boolean granted=grants.length>0;for(int value:grants)granted&=value==PackageManager.PERMISSION_GRANTED;
        if(granted)startMeasurement();else new AlertDialog.Builder(this).setTitle("连接需要蓝牙权限").setMessage(Build.VERSION.SDK_INT>=31?"请允许「附近的设备」权限后再连接体脂秤。你仍可手工记录。":"此 Android 版本需要定位权限才能扫描蓝牙。StrideHub 不会获取或保存位置信息。你仍可手工记录。").setNegativeButton("稍后",null).setPositiveButton("应用设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
    }
    private void requestExport(String kind) {
        exportKind=kind;String stamp=new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(System.currentTimeMillis());
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("csv".equals(kind)?"text/csv":"text/plain").putExtra(Intent.EXTRA_TITLE,"StrideHub-"+("csv".equals(kind)?"记录-":"连接诊断-")+stamp+("csv".equals(kind)?".csv":".txt"));
        try {startActivityForResult(intent,EXPORT);}catch(ActivityNotFoundException e){showMessage("无法选择保存位置","当前系统没有可用的文档保存程序。");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==ENABLE_BT){if(resultCode==RESULT_OK)startMeasurement();else{status="蓝牙未开启";render();}return;}
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
    @Override protected void onStart(){super.onStart();if(store!=null&&shell!=null)render();}
    @Override protected void onStop() {
        super.onStop();client.stop();if(deviceDialog!=null&&deviceDialog.isShowing())deviceDialog.dismiss();deviceList=null;pickerStatus=null;
        if(status.contains("寻找")||status.contains("扫描")||status.contains("连接中"))status="连接已暂停，返回后可重新连接";
    }
    @Override protected void onDestroy(){client.stop();store.close();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",page);out.putInt("days",days);out.putString("metric",selectedMetric.name());out.putInt("weightAverageDays",weightAverageDays);out.putBoolean("forcePicker",forcePicker);out.putString("exportKind",exportKind);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String value,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(7),dp(12),dp(7));b.setTextColor(primary?Color.WHITE:GREEN);b.setBackground(shape(primary?GREEN:SOFT,14));b.setStateListAnimator(null);return b;}
    private Button choice(String title,boolean selected,Runnable action){Button b=button(title,false);b.setTextColor(selected?Color.WHITE:MUTED);b.setBackground(shape(selected?GREEN:PAPER,14));b.setOnClickListener(v->action.run());return b;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(shape(Color.WHITE,22));return v;}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void add(LinearLayout parent,View child,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));parent.addView(child,p);}
    private EditText field(LinearLayout parent,String label,String hint,int input){parent.addView(text(label,13,INK,true));EditText edit=new EditText(this);edit.setTextColor(INK);edit.setTextSize(16);edit.setHint(hint);edit.setSingleLine(true);edit.setInputType(input);add(parent,edit,5,0);edit.getLayoutParams().height=dp(54);return edit;}
    private String sourceLabel(Measurement m){if(m.source==null)return "未知来源";if("manual".equals(m.source))return "手工录入";return m.source;}
    private String format(double value){return String.format(Locale.CHINA,"%.1f",value);}
    private String formatTime(long when){return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(when);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void showMessage(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
}
