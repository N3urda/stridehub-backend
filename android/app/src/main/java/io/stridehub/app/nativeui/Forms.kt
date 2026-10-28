package io.stridehub.app.nativeui

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class FieldType { TEXT, NUMBER, INTEGER, DATE, DATETIME, TIME, CHOICE, BOOLEAN, OBJECT, ROWS, STRINGS }
data class FormField(val key:String,val label:String,val type:FieldType=FieldType.TEXT,val required:Boolean=false,val options:List<String> = emptyList(),val children:List<FormField> = emptyList(),val default:Any? = null,val optionLabels:Map<String,String> = emptyMap())
data class RecordForm(val kind:String,val title:String,val path:String,val fields:List<FormField>,val createPost:Boolean=false,val singleton:Boolean=false)
private fun t(k:String,l:String,required:Boolean=false,default:Any?="")=FormField(k,l,required=required,default=default)
private fun n(k:String,l:String,required:Boolean=false,integer:Boolean=false,default:Any?=null)=FormField(k,l,if(integer) FieldType.INTEGER else FieldType.NUMBER,required,default=default)
private fun d(k:String,l:String,required:Boolean=true)=FormField(k,l,FieldType.DATE,required,default=if(required) LocalDate.now().toString() else null)
private fun c(k:String,l:String,vararg values:String)=FormField(k,l,FieldType.CHOICE,true,values.toList(),default=values.first())
private fun b(k:String,l:String)=FormField(k,l,FieldType.BOOLEAN,default=null)
private fun obj(k:String,l:String,vararg fields:FormField)=FormField(k,l,FieldType.OBJECT,children=fields.toList())
private fun rows(k:String,l:String,vararg fields:FormField)=FormField(k,l,FieldType.ROWS,children=fields.toList())
private fun dt(k:String,l:String)=FormField(k,l,FieldType.DATETIME,true,default=OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
private val notes=t("notes","备注")
private val provenance=listOf(c("source","记录来源","user","ai","import"),t("sourceLabel","来源说明"),t("rationale","计划理由 / AI 依据"),notes)
private val nutrients=listOf(n("energyKcal","能量 (kcal)"),n("proteinGrams","蛋白质 (g)"),n("carbohydrateGrams","碳水化合物 (g)"),n("fatGrams","脂肪 (g)"))
private val mealFields=listOf(c("mealType","餐次","other","breakfast","lunch","dinner","snack","fuel"),t("foods","食物",true),t("portion","份量"))
private val mealItem=listOf(t("id","条目标识",true),*mealFields.toTypedArray())
private val location=obj("location","地点",t("label","名称",true),n("latitude","纬度",true),n("longitude","经度",true))
private val feedback=listOf(n("rpe","主观强度 RPE (1–10)",integer=true),c("thermalFeeling","冷热感受","comfortable","cold","hot"),b("pain","疼痛"),notes,obj("fuel","实际补给",n("carbsGrams","碳水 (g)"),n("fluidMl","饮水 (ml)"),c("giComfort","胃肠感受","unknown","good","mild","poor"),notes))
object Forms {
    val all=listOf(
        RecordForm("meals","饮食记录","/api/v1/health/meals",listOf(d("date","日期"),*mealFields.toTypedArray(),obj("nutrients","营养数据",*nutrients.toTypedArray()),c("nutritionSource","营养数据来源","unknown","label","database","estimate"),t("planId","关联饮食计划 ID",default=null),*provenance.toTypedArray())),
        RecordForm("meal-plans","饮食计划","/api/v1/health/meal-plans",listOf(d("date","日期"),c("status","状态","active","draft","cancelled"),obj("targets","每日目标",*nutrients.toTypedArray()),rows("meals","计划餐次",*mealItem.toTypedArray()),*provenance.toTypedArray())),
        RecordForm("weight-goals","减重目标","/api/v1/health/weight-goals",listOf(t("title","目标名称",true),d("startDate","开始日期"),d("endDate","结束日期"),n("startWeightKg","起始体重 (kg)",true),n("targetWeightKg","目标体重 (kg)",true),c("status","状态","active","draft","paused","completed","cancelled"),rows("milestones","阶段目标",d("date","日期"),n("weightKg","体重 (kg)",true)),*provenance.toTypedArray())),
        RecordForm("measurements","身体测量","/api/v1/health/measurements",listOf(d("date","日期"),n("waistCm","腰围 (cm)"),n("bodyFatPercent","体脂率 (%)"),c("source","来源","user","import"),t("sourceLabel","来源说明"),notes)),
        RecordForm("blood-pressure","血压记录","/api/v1/health/blood-pressure",listOf(dt("measuredAt","测量时间"),n("systolic","收缩压 (mmHg)",true,true),n("diastolic","舒张压 (mmHg)",true,true),n("pulse","脉搏 (次/分)",integer=true),c("arm","测量手臂","unknown","left","right"),c("posture","姿势","unknown","seated","standing","lying"),notes)),
        RecordForm("weight","体重记录","/api/v1/health/weight",listOf(d("date","日期"),n("weightKg","体重 (kg)",true),c("source","来源","manual","import","unknown"),t("sourceLabel","来源说明"),notes)),
        RecordForm("plans","健康计划","/api/v1/health/plans",listOf(t("title","计划名称",true),c("category","分类","general","weight","sleep","nutrition","recovery"),t("goal","目标",true),d("startDate","开始日期"),d("endDate","结束日期",false),c("status","状态","draft","active","paused","completed","cancelled"),rows("items","计划条目",t("id","条目标识",true),t("title","条目名称",true),d("date","日期",false),FormField("time","时间",FieldType.TIME),c("status","完成状态","pending","completed","skipped"),notes),c("source","计划来源","user","ai","clinician"),t("sourceLabel","来源说明"),t("rationale","计划理由 / AI 依据"),notes)),
        RecordForm("sessions","训练课表","/api/v1/training/sessions",listOf(t("title","课表名称",true),dt("startAt","开始时间"),n("durationMinutes","时长 (分钟)",true,true,60),n("distanceKm","距离 (km)"),c("type","训练类型","easy","long","tempo","interval","recovery","race","rest"),c("status","状态","planned","completed","skipped","cancelled"),t("raceId","关联赛事 ID",default=null),location,rows("steps","训练步骤",t("kind","步骤",true),n("minutes","分钟",true),n("distanceKm","距离 (km)"),t("target","目标")),rows("fuelPlan","补给计划",n("minute","第几分钟",true),t("item","补给品",true),n("carbsGrams","碳水 (g)"),n("fluidMl","饮水 (ml)")),obj("feedback","执行反馈",*feedback.toTypedArray()),FormField("activityIds","已关联运动 ID",FieldType.STRINGS),notes),true),
        RecordForm("races","赛事","/api/v1/training/races",listOf(t("name","赛事名称",true),d("date","日期"),n("distanceKm","距离 (km)",true),n("targetTimeMinutes","目标用时 (分钟)"),notes),true),
        RecordForm("check-ins","每日状态","/api/v1/training/check-ins",listOf(d("date","日期"),n("sleepHours","睡眠 (小时)"),n("fatigue","疲劳 (1–5)",integer=true),n("soreness","酸痛 (1–5)",integer=true),b("pain","疼痛"),notes),true),
        RecordForm("fuel-logs","训练补给","/api/v1/training/fuel-logs",listOf(d("date","日期"),t("sessionId","课表 ID",default=null),n("minute","第几分钟",true,default=0),t("item","补给品",true),n("carbsGrams","碳水 (g)",true,default=0),n("fluidMl","饮水 (ml)",true,default=0),c("giComfort","胃肠感受","good","mild","poor"),notes),true),
        RecordForm("context","健康背景","/api/v1/health/context",listOf(rows("reports","健康报告",t("id","报告标识",true),t("title","报告名称",true),d("reportDate","报告日期"),t("sourceLabel","出具机构",true),rows("findings","检查结果",t("name","项目",true),t("valueText","结果",true),t("unit","单位"),t("referenceRangeText","参考范围"),c("flag","标记","unknown","normal","high","low","abnormal"),n("page","报告页码",integer=true)),FormField("clinicianAdvice","医生建议",FieldType.STRINGS),t("aiInterpretation","AI 解读"),FormField("needsReview","需要复核",FieldType.BOOLEAN,required=true,default=true),notes),rows("constraints","健康约束",t("id","约束标识",true),t("description","说明",true),c("sourceType","来源","user","clinician"),t("sourceReportId","来源报告 ID",default=null),d("validFrom","生效日期",false),d("reviewOn","复核日期",false),c("status","状态","active","inactive")),FormField("lifestylePreferences","生活习惯偏好",FieldType.STRINGS),notes),singleton=true),
        RecordForm("profile","训练偏好","/api/v1/training/profile",listOf(t("timezone","时区",true,"Asia/Shanghai"),location,c("thermalPreference","温度偏好","neutral","cold","warm"),FormField("usualStartTime","通常开始时间",FieldType.TIME,true,default="06:30"),n("usualDurationMinutes","通常时长 (分钟)",true,true,60),FormField("runningDays","每周跑步日 (1=周一)",FieldType.STRINGS),FormField("notificationsEnabled","启用提醒偏好",FieldType.BOOLEAN,required=true,default=false),FormField("eveningReminderTime","晚间提醒时间",FieldType.TIME,true,default="20:00"),n("preRunReminderMinutes","提前提醒 (分钟)",true,true,60)),singleton=true)
    )
    fun get(kind:String)=all.first { it.kind==kind }
    fun initial(fields:List<FormField>):JSONObject = JSONObject().apply { fields.forEach { field ->
        put(field.key,when(field.type) {
            FieldType.ROWS,FieldType.STRINGS -> JSONArray()
            FieldType.OBJECT -> if(field.required || field.key=="targets" || field.key=="nutrients") initial(field.children) else JSONObject.NULL
            else -> if(field.key=="id") UUID.randomUUID().toString() else field.default ?: JSONObject.NULL
        })
    }}
    /** Prevent display-only metadata entering strict API payloads. */
    fun payload(form:RecordForm,source:JSONObject):JSONObject = JSONObject().apply {
        form.fields.forEach { if(source.has(it.key)) put(it.key,source.opt(it.key)) }
        if(source.has("version")) put("version",source.opt("version"))
        if(form.kind=="profile") { val values=optJSONArray("runningDays") ?: JSONArray(); put("runningDays",JSONArray().apply { for(i in 0 until values.length()) put(values.optString(i).toIntOrNull() ?: throw IllegalArgumentException("跑步日需填写 1–7")) }) }
    }
    fun validate(fields:List<FormField>,data:JSONObject,prefix:String="") {
        fields.forEach { f ->
            val value=data.opt(f.key)
            if(f.required) require(value!=null && value!=JSONObject.NULL && value.toString().isNotBlank()) { "$prefix${f.label}不能为空" }
            if(value!=null && value!=JSONObject.NULL) when(f.type) {
                FieldType.DATE -> require(runCatching { LocalDate.parse(value.toString()) }.isSuccess) { "${f.label}日期无效" }
                FieldType.DATETIME -> require(runCatching { OffsetDateTime.parse(value.toString()) }.isSuccess) { "${f.label}需包含时区，如 2026-10-23T06:30:00+08:00" }
                FieldType.NUMBER -> require(value is Number && value.toDouble().isFinite()) { "${f.label}需填写数字" }
                FieldType.INTEGER -> require(value is Int || value is Long) { "${f.label}需填写整数" }
                FieldType.OBJECT -> validate(f.children,data.getJSONObject(f.key),"${f.label} · ")
                FieldType.ROWS -> { val a=data.getJSONArray(f.key);for(i in 0 until a.length()) validate(f.children,a.getJSONObject(i),"${f.label} ${i+1} · ") }
                else -> Unit
            }
        }
    }
}
