package io.stridehub.app.scale.core;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads the quoted UTF-8 export from TinyYumi 0.3, without changing captured values. */
public final class LegacyCsv {
    private LegacyCsv() {}
    public static List<List<String>> parse(String text) {
        if (text.startsWith("\ufeff")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (quoted) {
                if (c=='"') {
                    if (i+1<text.length() && text.charAt(i+1)=='"') {cell.append('"');i++;}
                    else {quoted=false;closed=true;}
                } else cell.append(c);
            } else if (c=='"') {
                if (cell.length()>0 || closed) throw new IllegalArgumentException("CSV 引号格式错误");
                quoted=true;
            } else if (c==',' || c=='\r' || c=='\n') {
                row.add(cell.toString());cell.setLength(0);closed=false;
                if (c!=',') {
                    if (c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                    if (!(row.size()==1 && row.get(0).isEmpty())) rows.add(row);
                    row=new ArrayList<>();
                }
            } else {
                if (closed) throw new IllegalArgumentException("CSV 引号后存在无效字符");
                cell.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV 文件不完整");
        if (cell.length()>0 || closed || !row.isEmpty()) {row.add(cell.toString());rows.add(row);}
        if (rows.isEmpty() || rows.get(0).size()<10 || !"记录ID".equals(rows.get(0).get(0)) || !"体重kg".equals(rows.get(0).get(3)))
            throw new IllegalArgumentException("请选择 TinyYumi 导出的完整 CSV");
        for (int i=1;i<rows.size();i++) if(rows.get(i).size()!=rows.get(0).size()) throw new IllegalArgumentException("CSV 列数不一致，第 "+(i+1)+" 行");
        return rows;
    }
    public static Measurement measurement(List<String> row) {
        Measurement m=new Measurement();
        m.measuredAt=OffsetDateTime.parse(row.get(1)).toInstant().toEpochMilli();
        m.localDay=LocalDate.parse(row.get(2)).toString();
        m.weightKg=Double.parseDouble(row.get(3));
        m.bodyFatPercent=number(row.get(4));m.bmi=number(row.get(5));
        m.impedance=row.get(6).isEmpty()?0:Integer.parseInt(row.get(6));
        m.source=row.get(7);m.deviceAddress=row.get(8);m.profileSnapshot=row.get(9).isEmpty()?null:row.get(9);
        if (!Double.isFinite(m.weightKg) || m.weightKg<2 || m.weightKg>300 || m.measuredAt<=0 || m.source.isEmpty() || m.impedance<0
            || (m.bodyFatPercent!=null && (m.bodyFatPercent<1 || m.bodyFatPercent>75)) || (m.bmi!=null && m.bmi<=0))
            throw new IllegalArgumentException("记录中存在无效测量值");
        return m;
    }
    public static void restoreDerived(Measurement m, List<String> header, List<String> row) {
        String algorithm=field(header,row,"算法版本");
        if (algorithm.isEmpty()) return;
        BodyMetrics metrics=new BodyMetrics();metrics.algorithmVersion=Integer.parseInt(algorithm);
        if (metrics.algorithmVersion!=BodyMetrics.ALGORITHM_VERSION) throw new IllegalArgumentException("此历史算法版本暂不支持，请保留原导出文件");
        for (BodyMetrics.Metric metric:BodyMetrics.Metric.values()) {
            Double value=number(field(header,row,"计算_"+metric.title+metric.unit));
            if (value!=null) metrics.put(metric,value);
            String grade=field(header,row,metric.title+"_分级"), reference=field(header,row,metric.title+"_参考范围");
            if(!grade.isEmpty())metrics.grades.put(metric,grade);
            if(!reference.isEmpty())metrics.references.put(metric,reference);
            Double score=number(field(header,row,metric.title+"_评分"));if(score!=null)metrics.scores.put(metric,score);
        }
        metrics.bodyShape=field(header,row,"体型");metrics.calculationBasis=field(header,row,"计算依据");
        String calculated=field(header,row,"计算时间");
        if(calculated.isEmpty())throw new IllegalArgumentException("缺少历史计算时间");
        m.metricsCalculatedAt=OffsetDateTime.parse(calculated).toInstant().toEpochMilli();
        m.metricsProfileSnapshot=field(header,row,"计算资料快照");m.metrics=metrics;
    }
    private static String field(List<String> header,List<String> row,String name) {
        int index=header.indexOf(name);return index<0?"":row.get(index);
    }
    private static Double number(String s) {
        if (s.isEmpty()) return null;
        double n=Double.parseDouble(s);
        if (!Double.isFinite(n)) throw new IllegalArgumentException("数值无效");
        return n;
    }
}
