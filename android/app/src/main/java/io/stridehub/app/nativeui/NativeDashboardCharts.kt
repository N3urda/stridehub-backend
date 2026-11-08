@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.stridehub.app.nativeui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max

object DashboardData {
    fun number(row:JSONObject?,key:String):Double? = (row?.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    /** Daily axes include unobserved dates, without manufacturing zero measurements. */
    fun calendar(rows:List<JSONObject>):List<JSONObject> {
        val dated=rows.mapNotNull {r->runCatching { LocalDate.parse(r.text("date")) to r }.getOrNull()}.toMap()
        if(dated.isEmpty()) return emptyList()
        val first=dated.keys.min();val last=dated.keys.max()
        if(java.time.temporal.ChronoUnit.DAYS.between(first,last)>366) return dated.toSortedMap().values.toList()
        return generateSequence(first){it.plusDays(1)}.takeWhile {it<=last}.map {dated[it] ?: JSONObject().put("date",it.toString())}.toList()
    }
    fun daily(rows:List<JSONObject>,group:String,key:String) = calendar(rows.map {r->JSONObject().put("date",r.opt("date")).put("value",number(r.optJSONObject(group),key) ?: JSONObject.NULL)})
    fun stages(row:JSONObject):List<Double?> {
        val total=number(row,"totalMinutes")?.takeIf {it>=0} ?: return List(4){null}
        val stages=listOf("deepMinutes","lightMinutes","remMinutes").map {number(row,it)?.takeIf {v->v>=0}}
        val known=stages.filterNotNull().sum()
        if(known>total+0.1) return listOf(null,null,null,total)
        return stages + (total-known).coerceAtLeast(0.0)
    }
}
object ChartPalette {
    val activity=Color(0xFF087E9B)
    val weight=Color(0xFFC76A26)
    val sleep=listOf(Color(0xFF5942A6),Color(0xFF9E8BDD),Color(0xFF6CABC1),Color(0xFFA8ADB8))
    val food=listOf(Color(0xFF087E9B),Color(0xFFB67B20),Color(0xFF9A61A1))
}
@Composable fun ChartPanel(title:String,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable fun ChartRangeLabels(first:String,last:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(first,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(last,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun ChartSelection(rows:List<JSONObject>,selected:Int,onSelect:(Int)->Unit,value:String,onOpen:((JSONObject)->Unit)?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={onSelect((selected-1).coerceAtLeast(0))},enabled=selected>0,contentPadding=PaddingValues(0.dp),modifier=Modifier.width(40.dp).semantics {contentDescription="上一条记录"}){Text("‹")}
        Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={onSelect((selected+1).coerceAtMost(rows.lastIndex))},enabled=selected<rows.lastIndex,contentPadding=PaddingValues(0.dp),modifier=Modifier.width(40.dp).semantics {contentDescription="下一条记录"}){Text("›")}
        if(onOpen!=null && selected in rows.indices && rows[selected].length()>1) TextButton(onClick={onOpen(rows[selected])},contentPadding=PaddingValues(horizontal=4.dp)){Text("详情")}
    }
}
@Composable fun DailyBars(title:String,rows:List<JSONObject>,key:String,unit:String,color:Color=ChartPalette.activity,onOpen:((JSONObject)->Unit)?=null) {
    val values=ChartData.values(rows,key)
    var selected by remember(rows.toString(),key){mutableIntStateOf(rows.indexOfLast {DashboardData.number(it,key)!=null}.coerceAtLeast(0))}
    val known=values.filterNotNull()
    ChartPanel(title) {
        if(known.isEmpty()) Text("暂无记录",style=MaterialTheme.typography.bodySmall) else {
            val upper=known.max().coerceAtLeast(1.0);val grid=MaterialTheme.colorScheme.outlineVariant
            ChartRangeLabels("${"%.0f".format(upper)} $unit", "${known.size} 天有记录")
            Canvas(Modifier.fillMaxWidth().height(128.dp).semantics {contentDescription="$title，${known.size}个有效值，最高${"%.1f".format(upper)}$unit。下方按钮可逐条查看。"}.pointerInput(rows.toString()){detectTapGestures {p->selected=(p.x/size.width*rows.size).toInt().coerceIn(0,rows.lastIndex)}}) {
                (0..2).forEach {i->val y=size.height*i/2;drawLine(grid,Offset(0f,y),Offset(size.width,y))}
                val w=size.width/rows.size
                values.forEachIndexed {i,v->if(v!=null) {val h=(v.coerceAtLeast(0.0)/upper*size.height).toFloat();drawRect(color.copy(alpha=if(i==selected) 1f else .65f),Offset(i*w+w*.15f,size.height-h),Size(w*.7f,max(h,1.dp.toPx())))}}
            }
            ChartRangeLabels(rows.first().text("date").takeLast(5),rows.last().text("date").takeLast(5))
            ChartSelection(rows,selected,{selected=it},"${rows[selected].text("date")}  ${values[selected]?.let {"%.1f".format(it)+" $unit"} ?: "未记录"}",onOpen)
        }
    }
}
@Composable fun SleepStackChart(input:List<JSONObject>,onOpen:((JSONObject)->Unit)?=null) {
    val rows=DashboardData.calendar(input)
    var selected by remember(rows.toString()){mutableIntStateOf(rows.indexOfLast {DashboardData.number(it,"totalMinutes")!=null}.coerceAtLeast(0))}
    ChartPanel("睡眠结构 · 小时") {
        if(rows.none {DashboardData.number(it,"totalMinutes")!=null}) Text("暂无睡眠记录",style=MaterialTheme.typography.bodySmall) else {
            val upper=rows.mapNotNull {DashboardData.number(it,"totalMinutes")}.max().coerceAtLeast(60.0)
            val grid=MaterialTheme.colorScheme.outlineVariant
            ChartRangeLabels("${"%.1f".format(upper/60)} 小时","睡眠分期来自设备")
            Canvas(Modifier.fillMaxWidth().height(144.dp).semantics {contentDescription="每日睡眠分期堆叠柱状图。深睡、浅睡、REM及未分期。下方按钮可逐日查看。"}.pointerInput(rows.toString()){detectTapGestures {p->selected=(p.x/size.width*rows.size).toInt().coerceIn(0,rows.lastIndex)}}) {
                (0..2).forEach {i->drawLine(grid,Offset(0f,size.height*i/2),Offset(size.width,size.height*i/2))}
                val w=size.width/rows.size
                rows.forEachIndexed {i,r->var bottom=size.height;DashboardData.stages(r).forEachIndexed {stage,v->if(v!=null && v>0){val h=(v/upper*size.height).toFloat();bottom-=h;drawRect(ChartPalette.sleep[stage].copy(alpha=if(i==selected) 1f else .75f),Offset(i*w+w*.12f,bottom),Size(w*.76f,h))}}}
            }
            ChartRangeLabels(rows.first().text("date").takeLast(5),rows.last().text("date").takeLast(5))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("深睡","浅睡","REM","未分期").forEachIndexed {i,label->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){Box(Modifier.size(8.dp).background(ChartPalette.sleep[i]));Text(label,style=MaterialTheme.typography.labelSmall)}}
            }
            val row=rows[selected]
            ChartSelection(rows,selected,{selected=it},"${row.text("date")}  ${healthDuration(row.opt("totalMinutes"))}",onOpen)
            Text("深睡 ${healthDuration(row.opt("deepMinutes"))}  浅睡 ${healthDuration(row.opt("lightMinutes"))}  REM ${healthDuration(row.opt("remMinutes"))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun HorizontalValues(title:String,items:List<Triple<String,Double?,Color>>,unit:String) {
    ChartPanel(title) {
        val maxValue=items.mapNotNull {it.second}.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        items.forEach {(label,value,color)->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,style=MaterialTheme.typography.bodySmall);Text(value?.let {"%.1f $unit".format(it)} ?: "未记录",style=MaterialTheme.typography.labelMedium)}
            if(value!=null) LinearProgressIndicator(progress={(value/maxValue).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(9.dp),color=color,trackColor=MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
@Composable fun NutritionTargetChart(day:JSONObject) {
    ChartPanel("${day.text("date")} · 已记录 / 目标") {
        listOf(Triple("energyKcal","能量","kcal"),Triple("proteinGrams","蛋白质","g"),Triple("carbohydrateGrams","碳水","g"),Triple("fatGrams","脂肪","g")).forEachIndexed {i,(key,label,unit)->
            val actual=DashboardData.number(day.optJSONObject("actualLogged"),key)
            val target=DashboardData.number(day.optJSONObject("targets"),key)
            val upper=max(actual ?: 0.0,target ?: 0.0).coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(label,style=MaterialTheme.typography.bodySmall);Text("${actual?.let {"%.0f".format(it)} ?: "未知"} / ${target?.let {"%.0f".format(it)} ?: "未设目标"} $unit",style=MaterialTheme.typography.labelMedium)}
            val color=ChartPalette.food[i%3];val track=MaterialTheme.colorScheme.surfaceVariant;val marker=MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                drawRect(track)
                if(actual!=null) drawRect(color,size=Size((size.width*actual/upper).toFloat(),size.height))
                if(target!=null) {val x=(size.width*target/upper).toFloat().coerceIn(1f,size.width-1f);drawLine(marker,Offset(x,0f),Offset(x,size.height),2.dp.toPx())}
            }
        }
        Text("目标以竖线标记；未完整记录的营养项保持未知。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun MacroDonut(day:JSONObject) {
    val actual=day.optJSONObject("actualLogged")
    val grams=listOf("proteinGrams","carbohydrateGrams","fatGrams").map {DashboardData.number(actual,it)?.takeIf {v->v>=0}}
    if(grams.any {it==null}) return
    val energy=grams.mapIndexed {i,v->v!!*if(i==2) 9 else 4};val total=energy.sum()
    if(total<=0) return
    ChartPanel("三大营养素供能占比") {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Canvas(Modifier.size(112.dp).semantics {contentDescription="按蛋白质、碳水每克4千卡，脂肪每克9千卡计算的供能比例。"}) {
                var start=-90f
                energy.forEachIndexed {i,v->val sweep=(v/total*360).toFloat();drawArc(ChartPalette.food[i],start,sweep,false,topLeft=Offset(10.dp.toPx(),10.dp.toPx()),size=Size(size.width-20.dp.toPx(),size.height-20.dp.toPx()),style=Stroke(16.dp.toPx()));start+=sweep}
            }
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {listOf("蛋白质","碳水","脂肪").forEachIndexed {i,label->Text("$label  ${"%.0f".format(energy[i]/total*100)}%",color=ChartPalette.food[i],style=MaterialTheme.typography.bodyMedium)}}
        }
        Text("${day.text("date")} · 仅包含已记录餐次",style=MaterialTheme.typography.labelSmall)
    }
}
