package io.stridehub.app.scale.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.core.BodyMetrics.Metric;
import io.stridehub.app.scale.core.WeightInsights;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

/** Each day is selected before metric extraction, so missing values never use an earlier reading. */
public final class TrendChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Measurement> daily = new ArrayList<>();
    private Metric metric=Metric.WEIGHT;
    private int averageDays;
    private Map<String,Double> averages=Collections.emptyMap();
    private final float density;
    public TrendChart(Context context) { super(context); density=getResources().getDisplayMetrics().density; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
    public void setData(List<Measurement> values, Metric selected) {
        setData(values,selected,values,0,System.currentTimeMillis());
    }
    /** Visible dates stay unchanged; trailing means can use earlier observed days outside the viewport. */
    public void setData(List<Measurement> values,Metric selected,List<Measurement> history,int windowDays,long now) {
        daily=values;metric=selected;
        averageDays=selected==Metric.WEIGHT&&(windowDays==7||windowDays==30)?windowDays:0;
        averages=averageDays==0?Collections.emptyMap():WeightInsights.movingAverages(history,averageDays,now);
        int count=0;for(Measurement m:daily)if(valueFor(m,metric)!=null)count++;
        setContentDescription(metric.title+"趋势图，"+count+" 个有效日期。"+(averageDays==0?"每日实测。":"绿色为 "+averageDays+" 日均线，灰色为每日实测。")+"每天取最后一条测量，缺测日期不补值。");
        invalidate();
    }
    private Double plottedValue(Measurement measurement) {
        return averageDays==0?valueFor(measurement,metric):averages.get(measurement.localDay);
    }
    public static Double valueFor(Measurement measurement,Metric metric) {
        Double value=measurement.metrics==null?null:measurement.metrics.get(metric);
        if(value!=null&&Double.isFinite(value))return value;
        if(metric==Metric.WEIGHT)return measurement.weightKg;
        if(metric==Metric.BODY_FAT)return measurement.bodyFatPercent;
        if(metric==Metric.BMI)return measurement.bmi;
        return null;
    }
    public static String formatValue(Double value,Metric metric) {
        return value==null?"—":String.format(Locale.CHINA,"%."+metric.digits+"f",value);
    }
    private float dp(float value) { return density*value; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        List<Measurement> points=new ArrayList<>();
        double minimum=Double.MAX_VALUE, maximum=-Double.MAX_VALUE;
        for (Measurement m:daily) {
            Double value=plottedValue(m);
            if (value!=null) { points.add(m); minimum=Math.min(minimum,value); maximum=Math.max(maximum,value); }
            if(averageDays>0) { Double actual=valueFor(m,metric);if(actual!=null){minimum=Math.min(minimum,actual);maximum=Math.max(maximum,actual);} }
        }
        if (points.isEmpty()) {
            paint.setColor(Color.rgb(111,122,115)); paint.setTextSize(dp(14)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("这个时间段还没有"+metric.title+"记录",getWidth()/2f,getHeight()/2f,paint);
            return;
        }
        double padding=Math.max(metric.digits==0?1:.4,(maximum-minimum)*.2);
        minimum=minimum>=0?Math.max(0,minimum-padding):minimum-padding;maximum+=padding;
        paint.setStrokeWidth(dp(1));paint.setTextSize(dp(11));
        float labelWidth=Math.max(paint.measureText(formatValue(minimum,metric)),paint.measureText(formatValue(maximum,metric)));
        float left=Math.max(dp(43),labelWidth+dp(12)),right=getWidth()-dp(15),top=dp(18),bottom=getHeight()-dp(34);
        for (int n=0;n<4;n++) {
            float y=top+(bottom-top)*n/3f;
            paint.setColor(Color.rgb(231,235,227)); canvas.drawLine(left,y,right,y,paint);
            paint.setColor(Color.rgb(119,131,123)); paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatValue(maximum-(maximum-minimum)*n/3,metric),left-dp(8),y+dp(4),paint);
        }
        long start=LocalDate.parse(daily.get(0).localDay).toEpochDay();
        long end=LocalDate.parse(daily.get(daily.size()-1).localDay).toEpochDay();
        if(averageDays>0) {
            Path actualLine=new Path();boolean first=true;
            paint.setColor(Color.rgb(178,187,180));paint.setStrokeWidth(dp(1.3f));paint.setStyle(Paint.Style.STROKE);
            for(Measurement m:daily) {
                Double value=valueFor(m,metric);if(value==null)continue;
                long day=LocalDate.parse(m.localDay).toEpochDay();
                float x=end==start?(left+right)/2:left+(right-left)*(day-start)/(float)(end-start);
                float y=bottom-(float)((value-minimum)/(maximum-minimum))*(bottom-top);
                if(first){actualLine.moveTo(x,y);first=false;}else actualLine.lineTo(x,y);
                canvas.drawCircle(x,y,dp(2),paint);
            }
            canvas.drawPath(actualLine,paint);paint.setStyle(Paint.Style.FILL);
        }
        float[] xs=new float[points.size()],ys=new float[points.size()];
        Path line=new Path();
        for(int i=0;i<points.size();i++) {
            Measurement m=points.get(i); double value=plottedValue(m);
            long day=LocalDate.parse(m.localDay).toEpochDay();
            float x=end==start?(left+right)/2:left+(right-left)*(day-start)/(float)(end-start);
            float y=bottom-(float)((value-minimum)/(maximum-minimum))*(bottom-top);
            xs[i]=x; ys[i]=y;
            if(i==0) line.moveTo(x,y); else line.lineTo(x,y);
        }
        if(points.size()>1) {
            if(averageDays==0) {
                Path fill=new Path(line); fill.lineTo(xs[xs.length-1],bottom); fill.lineTo(xs[0],bottom); fill.close();
                paint.setColor(Color.rgb(233,243,237)); paint.setStyle(Paint.Style.FILL); canvas.drawPath(fill,paint);
            }
            paint.setColor(Color.rgb(58,120,91)); paint.setStrokeWidth(dp(2.3f)); paint.setStyle(Paint.Style.STROKE); canvas.drawPath(line,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<xs.length;i++) {
            paint.setColor(Color.WHITE); canvas.drawCircle(xs[i],ys[i],dp(4.4f),paint);
            paint.setColor(Color.rgb(58,120,91)); canvas.drawCircle(xs[i],ys[i],dp(2.8f),paint);
        }
        paint.setTextSize(dp(11)); paint.setColor(Color.rgb(119,131,123)); paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(daily.get(0).localDay.substring(5).replace('-','/'),left,bottom+dp(24),paint);
        if(end!=start) { paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText(daily.get(daily.size()-1).localDay.substring(5).replace('-','/'),right,bottom+dp(24),paint); }
    }
}
