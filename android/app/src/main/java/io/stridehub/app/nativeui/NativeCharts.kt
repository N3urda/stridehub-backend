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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
@Composable fun NativeLineChart(title:String,rows:List<JSONObject>,key:String,color:Color,expectedCadenceSeconds:Double?=null) {
    val values=remember(rows.toString(),key){ChartData.values(rows,key)}
    val known=values.filterNotNull();var selected by remember {mutableIntStateOf(-1)}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        if(known.isEmpty()) Text("暂无实际测量值",color=MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val min=known.min();val max=known.max();val low=if(min==max) min-1 else min;val high=if(min==max) max+1 else max
            Text("${"%.1f".format(min)} – ${"%.1f".format(max)} · ${known.size} 个有效点",style=MaterialTheme.typography.labelMedium)
            val grid=MaterialTheme.colorScheme.outlineVariant
            val xs=rows.mapIndexed {i,row->ChartData.x(row,i)};val xLow=xs.minOrNull() ?: 0.0;val xHigh=xs.maxOrNull()?.takeIf {it>xLow} ?: xLow+1
            Canvas(Modifier.fillMaxWidth().height(155.dp).semantics {contentDescription="$title，最低 ${"%.1f".format(min)}，最高 ${"%.1f".format(max)}，${known.size} 个有效测量点。缺失数据留空。"}.pointerInput(rows,key){detectTapGestures {point->val target=xLow+point.x/size.width*(xHigh-xLow);selected=xs.indices.minByOrNull {abs(xs[it]-target)} ?: -1}}) {
                (0..3).forEach {i->val y=size.height*i/3;drawLine(grid,Offset(0f,y),Offset(size.width,y),1f)}
                var previous:Offset?=null;var previousX:Double?=null
                values.forEachIndexed {index,v->
                    if(v==null) {previous=null;previousX=null} else {
                        val point=Offset(((xs[index]-xLow)/(xHigh-xLow)*size.width).toFloat(),(size.height-(v-low)/(high-low)*size.height).toFloat())
                        val dateGap=ChartData.breakBefore(rows,index,expectedCadenceSeconds)
                        if(previous!=null && !dateGap) drawLine(color,previous!!,point,3.dp.toPx())
                        if(values.size<30 || index==selected) drawCircle(color,if(index==selected) 5.dp.toPx() else 2.5.dp.toPx(),point)
                        previous=point;previousX=xs[index]
                    }
                }
            }
            if(selected in rows.indices) Text("${rows[selected].text("date",rows[selected].text("at",rows[selected].text("seconds","样本 ${selected+1}")))} · ${values[selected]?.let {"%.2f".format(it)} ?: "缺失"}",style=MaterialTheme.typography.bodySmall)
            Text("点按查看测量值 · 缺失值保留空白",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }}
}
data class RoutePoint(val latitude:Double,val longitude:Double)
@Composable fun NativeRoute(points:List<RoutePoint>) {
    val color=MaterialTheme.colorScheme.primary;val muted=MaterialTheme.colorScheme.outlineVariant
    Card {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
