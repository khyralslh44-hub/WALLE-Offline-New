package com.nova.robot
internal enum class NovaMotionDirection{STOP,FORWARD,BACKWARD,TURN_LEFT,TURN_RIGHT}
internal data class NovaMotionRequest(val direction:NovaMotionDirection,val speed:Float)
internal data class NovaBaseSafetyState(val connected:Boolean,val sensorsHealthy:Boolean,val edgeClear:Boolean?,val distanceCm:Float?,val emergencyStop:Boolean)
internal data class NovaMotionDecision(val mayTransmit:Boolean,val direction:NovaMotionDirection,val speed:Float,val reason:String)
/** Fail-closed movement policy for a future adapter; not a Bluetooth driver. */
internal object NovaBaseSafetyGate{
 const val MAX_SPEED=0.25f;const val MIN_CLEARANCE_CM=18f
 fun evaluate(r:NovaMotionRequest,s:NovaBaseSafetyState):NovaMotionDecision{
  if(r.direction==NovaMotionDirection.STOP)return NovaMotionDecision(s.connected,NovaMotionDirection.STOP,0f,if(s.connected)"تُسمح بإشارة التوقف" else "القاعدة غير متصلة")
  if(!s.connected)return deny(r,"القاعدة غير متصلة")
  if(s.emergencyStop)return deny(r,"إيقاف الطوارئ مفعّل")
  if(!s.sensorsHealthy)return deny(r,"حالة المستشعرات غير سليمة أو غير معروفة")
  if(s.edgeClear!=true)return deny(r,"لم يتم تأكيد خلو المسار من الحواف")
  val d=s.distanceCm;if(d==null||!d.isFinite()||d<MIN_CLEARANCE_CM)return deny(r,"المسافة الآمنة من العائق غير مؤكدة أو قصيرة")
  if(!r.speed.isFinite()||r.speed !in 0.01f..MAX_SPEED)return deny(r,"السرعة خارج المجال الآمن المحدد")
  return NovaMotionDecision(true,r.direction,r.speed,"اجتازت الحركة بوابة الأمان البرمجية فقط")
 }
 private fun deny(r:NovaMotionRequest,why:String)=NovaMotionDecision(false,r.direction,0f,why)
}
