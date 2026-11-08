package io.stridehub.app.nativeui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.*

/** Real absent values create gaps; no interpolation across missing observations. */
object ChartData {
    fun values(rows:List<JSONObject>,key:String):List<Double?> = rows.map {row->
        if(!row.has(key) || row.isNull(key)) null else (row.opt(key) as? Number)?.toDouble()?.takeIf {it.isFinite()}
    }
    fun x(row:JSONObject,index:Int):Double = listOf("seconds","at","date").firstNotNullOfOrNull {key ->
        val value=row.opt(key)
        when(value){is Number->value.toDouble();is String->runCatching {OffsetDateTime.parse(value).toEpochSecond().toDouble()}.getOrNull() ?: runCatching {LocalDate.parse(value).toEpochDay().toDouble()}.getOrNull();else->null}
    } ?: index.toDouble()
    fun plotSegments(rows:List<JSONObject>,key:String,expectedCadenceSeconds:Double?):List<List<Int>> {
        val segments=mutableListOf<MutableList<Int>>()
        var current:MutableList<Int>?=null
        values(rows,key).forEachIndexed {index,value->
            if(value==null) current=null else {
                if(current==null || breakBefore(rows,index,expectedCadenceSeconds)) {
                    current=mutableListOf();segments.add(current!!)
                }
                current!!.add(index)
            }
        }
        return segments
    }
    fun breakBefore(rows:List<JSONObject>,index:Int,expectedCadenceSeconds:Double?):Boolean {
        if(index==0) return true
        val previous=rows[index-1];val current=rows[index]
        val delta=x(current,index)-x(previous,index-1)
        if(current.has("date") && previous.has("date")) return delta>1.5 || delta<=0
        if(current.has("at") && previous.has("at")) {
            // Sparse provider streams omit invalid samples. Unknown cadence never licenses a join.
            return expectedCadenceSeconds==null || delta<=0 || delta>expectedCadenceSeconds*1.5
        }
        return expectedCadenceSeconds!=null && (delta<=0 || delta>expectedCadenceSeconds*1.5)
    }

}
/** Resolve semantic accents against the active surface, including night mode. */
@Composable fun readableChartColor(color:Color):Color =
    if(MaterialTheme.colorScheme.surface.luminance()<.2f) lerp(color,Color.White,.28f) else color

@Composable fun ChartYAxis(high:Double,low:Double,format:ChartValueFormat,height:androidx.compose.ui.unit.Dp) {
    Column(Modifier.widthIn(min=44.dp).height(height),verticalArrangement=Arrangement.SpaceBetween,horizontalAlignment=Alignment.End) {
        (0..3).forEach {index->
            Text(format.value(high-(high-low)*index/3),style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.End)
        }
    }
}

@Composable fun NativeLineChart(
    title:String,rows:List<JSONObject>,key:String,color:Color,expectedCadenceSeconds:Double?=null,
    format:ChartValueFormat=ChartValueFormat.forMetric(title,key),axis:ChartAxis=ChartAxis.AUTO
) {
    val values=remember(rows.toString(),key){ChartData.values(rows,key)}
    val known=values.filterNotNull()
    var selected by remember(rows.toString(),key) {mutableIntStateOf(values.indexOfLast {it!=null}.coerceAtLeast(0))}
    val accent=readableChartColor(color)
    val effectiveAxis=ChartPresentation.axisFor(rows,axis)
    ChartPanel(title) {
        if(known.isEmpty()) Text("暂无实际测量值",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
        else {
            val (low,high)=ChartPresentation.range(known,3.0*10.0.pow(-format.decimals))
            val grid=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(format.label(values.getOrNull(selected)),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,color=accent)
                Text("${known.size} 个有效点",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val xs=rows.mapIndexed {i,row->ChartData.x(row,i)}
            val xLow=xs.minOrNull() ?: 0.0
            val xHigh=xs.maxOrNull()?.takeIf {it>xLow} ?: xLow+1
            val chartHeight=156.dp
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                ChartYAxis(high,low,format,chartHeight)
                Canvas(Modifier.weight(1f).height(chartHeight).semantics {
                    contentDescription="$title，最低 ${format.label(known.min())}，最高 ${format.label(known.max())}。缺失数据留空，下方按钮可逐条查看。"
                }.pointerInput(rows.toString(),key,effectiveAxis){detectTapGestures {point->
                    val target=xLow+point.x/size.width*(xHigh-xLow)
                    selected=xs.indices.minByOrNull {abs(xs[it]-target)} ?: 0
                }}) {
                    val top=8.dp.toPx();val bottom=size.height-top;val plotHeight=bottom-top
                    val left=5.dp.toPx();val right=size.width-left;val plotWidth=right-left
                    (0..3).forEach {i->val y=top+plotHeight*i/3;drawLine(grid,Offset(left,y),Offset(right,y),1.dp.toPx())}
                    fun position(index:Int)=Offset((left+(xs[index]-xLow)/(xHigh-xLow)*plotWidth).toFloat(),(bottom-(values[index]!!-low)/(high-low)*plotHeight).toFloat())
                    val selectedX=(left+(xs[selected]-xLow)/(xHigh-xLow)*plotWidth).toFloat()
                    drawLine(accent.copy(alpha=.28f),Offset(selectedX,top),Offset(selectedX,bottom),1.dp.toPx())
                    ChartData.plotSegments(rows,key,expectedCadenceSeconds).forEach {segment->
                        segment.zipWithNext().forEach {(a,b)->drawLine(accent,position(a),position(b),2.5.dp.toPx())}
                        segment.forEach {index->
                            if(segment.size==1 || values.size<30 || index==selected) {
                                if(index==selected) drawCircle(accent.copy(alpha=.16f),8.dp.toPx(),position(index))
                                drawCircle(accent,if(index==selected) 4.dp.toPx() else 2.5.dp.toPx(),position(index))
                            }
                        }
                    }
                }
            }
            ChartRangeLabels(ChartPresentation.axisLabel(rows.first(),0,effectiveAxis),ChartPresentation.axisLabel(rows.last(),rows.lastIndex,effectiveAxis))
            ChartSelection(rows,selected,{selected=it},ChartPresentation.axisLabel(rows[selected],selected,effectiveAxis,full=true)+" · "+format.label(values[selected]))
            Text("最低 ${format.label(known.min())}  ·  最高 ${format.label(known.max())}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
data class RoutePoint(val latitude:Double,val longitude:Double)
@Composable fun NativeRoute(points:List<RoutePoint>) {
    val color=MaterialTheme.colorScheme.primary;val muted=MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("运动轨迹",style=MaterialTheme.typography.titleMedium)
        if(points.size<2) Text("此记录没有可显示的定位轨迹。") else {
            Text("${points.size} 个定位点 · 北向上",style=MaterialTheme.typography.bodySmall)
            val minLat=points.minOf {it.latitude};val maxLat=points.maxOf {it.latitude};val minLon=points.minOf {it.longitude};val maxLon=points.maxOf {it.longitude}
            val factor=cos((minLat+maxLat)/2*PI/180);val width=max((maxLon-minLon)*factor,0.00001);val height=max(maxLat-minLat,0.00001)
            Canvas(Modifier.fillMaxWidth().height(220.dp).semantics {contentDescription="运动轨迹，${points.size} 个定位点，北向上。绿色为起点，红色为终点。"}) {
                val scale=min((size.width-32)/width,(size.height-32)/height)
                fun position(p:RoutePoint)=Offset(((p.longitude-minLon)*factor*scale+(size.width-width*scale)/2).toFloat(),(size.height-((p.latitude-minLat)*scale+(size.height-height*scale)/2)).toFloat())
                (1..3).forEach {i->drawLine(muted,Offset(size.width*i/4,0f),Offset(size.width*i/4,size.height));drawLine(muted,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4))}
                val path=Path();points.forEachIndexed {i,p->val pt=position(p);if(i==0) path.moveTo(pt.x,pt.y) else path.lineTo(pt.x,pt.y)};drawPath(path,color,style=Stroke(3.dp.toPx()))
                drawCircle(Color(0xFF329D6D),5.dp.toPx(),position(points.first()));drawCircle(Color(0xFFCB514E),5.dp.toPx(),position(points.last()))
            }
            Text("绿色起点 · 红色终点 · 显示原始轨迹，无地图底图",style=MaterialTheme.typography.labelSmall)
        }
    }}
}
