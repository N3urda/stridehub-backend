package io.stridehub.app.nativeui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun PageColumn(content:@Composable ColumnScope.()->Unit) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=760.dp).fillMaxWidth().fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp)
            .padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp),content=content)
    }
}

@Composable fun PageHeader(eyebrow:String,title:String,subtitle:String) {
    Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(eyebrow,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
        Text(title,style=MaterialTheme.typography.headlineLarge)
        Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SectionTitle(title:String,subtitle:String?=null) {
    Column(Modifier.padding(top=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable fun ErrorCard(message:String,onRetry:(()->Unit)?=null) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(message,style=MaterialTheme.typography.bodyMedium)
            onRetry?.let {TextButton(onClick=it){Text("重试")}}
        }
    }
}

@Composable fun InfoCard(title:String,body:String,onClick:(()->Unit)?=null) {
    val content:@Composable ()->Unit = {
        Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(onClick!=null) Icon(Icons.Default.ChevronRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(onClick!=null) Surface(onClick=onClick,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {content()}
    else Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {content()}
}

@Composable fun ActionRow(label:String,icon:ImageVector=Icons.Default.ChevronRight,onClick:()->Unit) {
    Surface(onClick=onClick,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(icon!=Icons.Default.ChevronRight) Icon(icon,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
            Icon(Icons.Default.ChevronRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun FeatureActionCard(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit,modifier:Modifier=Modifier,emphasized:Boolean=false) {
    val background=if(emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=background) {
        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface.copy(alpha=.7f)) {
                Icon(icon,null,Modifier.padding(12.dp).size(24.dp),tint=MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable fun MetricTile(label:String,value:String,note:String,modifier:Modifier=Modifier,onClick:()->Unit) {
    Card(onClick=onClick,modifier=modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.headlineMedium)
            Text(note,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
