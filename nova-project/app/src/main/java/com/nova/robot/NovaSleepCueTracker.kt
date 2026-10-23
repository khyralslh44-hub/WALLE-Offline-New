package com.nova.robot
/** Several seconds of closed-eye evidence can suggest drowsiness, but cannot prove sleep. */
internal class NovaSleepCueTracker(
    private val requiredClosedMillis: Long = 8_000L,
    private val closedThreshold: Float = 0.18f,
    private val openThreshold: Float = 0.52f,
    private val maximumSampleGapMillis: Long = 4_500L
) {
    private var closedSince: Long? = null
    private var lastValidSampleAt = Long.MIN_VALUE
    private var alreadyReported = false
    @Synchronized fun observe(facePresent:Boolean,leftEyeOpen:Float?,rightEyeOpen:Float?,nowMillis:Long):String? {
        if(!facePresent){reset();return null}
        if(lastValidSampleAt!=Long.MIN_VALUE&&nowMillis<lastValidSampleAt) reset()
        val left=leftEyeOpen?.takeIf{it.isFinite()&&it in 0f..1f}
        val right=rightEyeOpen?.takeIf{it.isFinite()&&it in 0f..1f}
        if(left==null||right==null){
            if(lastValidSampleAt==Long.MIN_VALUE||nowMillis<lastValidSampleAt||nowMillis-lastValidSampleAt>maximumSampleGapMillis) reset()
            return null
        }
        if(left<=closedThreshold&&right<=closedThreshold){
            if(closedSince==null)closedSince=nowMillis
            lastValidSampleAt=nowMillis
            val start=closedSince?:nowMillis
            if(!alreadyReported&&nowMillis>=start&&nowMillis-start>=requiredClosedMillis){
                alreadyReported=true
                return "ألاحظ أن عيني الشخص تبدوان مغلقتين باستمرار منذ عدة ثوانٍ؛ ربما ينعس أو يستريح، لكن هذا لا يؤكد أنه نائم."
            }
            return null
        }
        if(left>=openThreshold||right>=openThreshold)reset()
        else if(lastValidSampleAt!=Long.MIN_VALUE&&nowMillis>=lastValidSampleAt&&nowMillis-lastValidSampleAt>maximumSampleGapMillis)reset()
        else lastValidSampleAt=nowMillis
        return null
    }
    @Synchronized fun reset(){closedSince=null;lastValidSampleAt=Long.MIN_VALUE;alreadyReported=false}
}
