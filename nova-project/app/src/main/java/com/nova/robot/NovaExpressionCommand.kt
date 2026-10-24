package com.nova.robot
import java.util.Locale
internal data class NovaExpressionRequest(val mood:NovaMood,val labelArabic:String)
internal object NovaExpressionCommand{
 fun parse(raw:String):NovaExpressionRequest?{
  val c=norm(raw).trim()
  val e=when{
   c.startsWith("تعبير:")->c.substringAfter("تعبير:").trim()
   c.startsWith("اظهري تعبير ")->c.removePrefix("اظهري تعبير ").trim()
   c.startsWith("اظهر تعبير ")->c.removePrefix("اظهر تعبير ").trim()
   c.startsWith("اعرضي تعبير ")->c.removePrefix("اعرضي تعبير ").trim()
   else->return null
  }.removePrefix("ال").trim()
  return when{
   e.contains("احراج")||e.contains("خجل")->NovaExpressionRequest(NovaMood.EMBARRASSED,"الإحراج")
   e.contains("غيرة")||e.contains("اغار")->NovaExpressionRequest(NovaMood.JEALOUS,"الغيرة")
   e.contains("دوار")||e.contains("دوخه")||e.contains("دوخة")->NovaExpressionRequest(NovaMood.DIZZY,"الدوار")
   e.contains("اعجاب")->NovaExpressionRequest(NovaMood.ADMIRING,"الإعجاب")
   e.contains("سعاده")||e.contains("فرح")->NovaExpressionRequest(NovaMood.HAPPY,"الفرح")
   e.contains("فضول")->NovaExpressionRequest(NovaMood.CURIOUS,"الفضول")
   e.contains("تفكير")->NovaExpressionRequest(NovaMood.THINKING,"التفكير")
   e.contains("حزن")->NovaExpressionRequest(NovaMood.SAD,"الحزن")
   e.contains("غضب")->NovaExpressionRequest(NovaMood.FRUSTRATED,"الغضب")
   e.contains("دهشه")->NovaExpressionRequest(NovaMood.SURPRISED,"الدهشة")
   e.contains("نعاس")->NovaExpressionRequest(NovaMood.SLEEPY,"النعاس")
   e.contains("خوف")->NovaExpressionRequest(NovaMood.AFRAID,"الخوف")
   e.contains("حب")->NovaExpressionRequest(NovaMood.LOVE,"الحب")
   else->null
  }
 }
 private fun norm(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("[\\u064B-\\u065F\\u0670]"),"")
  .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace(Regex("\\s+")," ")
}
