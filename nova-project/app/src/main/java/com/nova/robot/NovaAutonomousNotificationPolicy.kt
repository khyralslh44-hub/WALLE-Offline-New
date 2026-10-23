package com.nova.robot
/** User-enabled local rule for low-frequency notifications; this is not a claim of subjective desire. */
internal object NovaAutonomousNotificationPolicy{
 const val MIN_IDLE_MILLIS=6L*60*60*1000
 const val MIN_GAP_MILLIS=22L*60*60*1000
 fun shouldNotify(nowWallMillis:Long,lastInteractionWallMillis:Long,lastNotificationWallMillis:Long,localHour:Int):Boolean{
  if(localHour !in 10..20)return false
  if(lastInteractionWallMillis<=0L||nowWallMillis<lastInteractionWallMillis)return false
  if(nowWallMillis-lastInteractionWallMillis<MIN_IDLE_MILLIS)return false
  if(lastNotificationWallMillis>0L&&(nowWallMillis<lastNotificationWallMillis||nowWallMillis-lastNotificationWallMillis<MIN_GAP_MILLIS))return false
  return true
 }
}
