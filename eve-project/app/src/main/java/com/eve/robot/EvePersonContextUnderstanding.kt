package com.eve.robot
import java.util.ArrayDeque

enum class EvePersonContextActivity {
    SITTING_POSSIBLE, PHONE_CALL_POSSIBLE, PHONE_BROWSING_POSSIBLE, INTERACTION_UNCLEAR
}
data class EvePersonContextEvidence(
 val timestampMs: Long, val personConfidence: Float,
 val poseObservationAtMs: Long = 0L, val sittingPoseConfidence: Float = 0f,
 val objectObservationAtMs: Long = 0L, val phoneConfidence: Float = 0f,
 val phoneNearEarConfidence: Float = 0f, val phoneInFrontConfidence: Float = 0f,
 val handObservationAtMs: Long = 0L, val handNearPhoneConfidence: Float = 0f,
 val nearbySeatConfidence: Float = 0f,
 val visiblePersonCount: Int = 0,
 val faceProximityConfidence: Float = 0f,
 val motionObservationAtMs: Long = 0L,
 val motionScore: Float = 0f
)
data class EvePersonContextSnapshot(
 val sittingConfidence: Float = 0f, val phoneCallConfidence: Float = 0f,
 val phoneBrowsingConfidence: Float = 0f, val interactionConfidence: Float = 0f,
 val summaryArabic: String = "", val observedAtMs: Long = 0L
) {
 fun confidenceFor(activity: EvePersonContextActivity): Float = when (activity) {
  EvePersonContextActivity.SITTING_POSSIBLE -> sittingConfidence
  EvePersonContextActivity.PHONE_CALL_POSSIBLE -> phoneCallConfidence
  EvePersonContextActivity.PHONE_BROWSING_POSSIBLE -> phoneBrowsingConfidence
  EvePersonContextActivity.INTERACTION_UNCLEAR -> interactionConfidence
 }
}
/** Fuses repeated local signals and deduplicates source timestamps to prevent frame over-counting. */
class EvePersonContextUnderstanding(private val windowMs: Long = 9_000L, private val maxSamples: Int = 24) {
 private val samples = ArrayDeque<EvePersonContextEvidence>()
 private var lastTimestampMs = 0L
 @Synchronized fun observe(raw: EvePersonContextEvidence): EvePersonContextSnapshot {
  val e = sanitize(raw)
  if (lastTimestampMs > e.timestampMs) return EvePersonContextSnapshot(observedAtMs = e.timestampMs)
  lastTimestampMs = e.timestampMs
  if (e.personConfidence < 0.55f) { samples.clear(); return EvePersonContextSnapshot(observedAtMs = e.timestampMs) }
  samples.addLast(e)
  while (samples.isNotEmpty() && e.timestampMs - samples.first().timestampMs > windowMs) samples.removeFirst()
  while (samples.size > maxSamples) samples.removeFirst()
  val frames = samples.filter { it.personConfidence >= 0.55f }
  val poses = frames.filter { it.poseObservationAtMs > 0L && e.timestampMs - it.poseObservationAtMs in 0L..3_200L }
   .distinctBy { it.poseObservationAtMs }.sortedBy { it.poseObservationAtMs }
  val seats = frames.filter { it.objectObservationAtMs > 0L &&
   e.timestampMs - it.objectObservationAtMs in 0L..2_200L && it.nearbySeatConfidence >= 0.42f }
   .distinctBy { it.objectObservationAtMs }
  val phones = frames.filter { it.objectObservationAtMs > 0L &&
   e.timestampMs - it.objectObservationAtMs in 0L..2_200L && it.phoneConfidence >= 0.42f }
   .distinctBy { it.objectObservationAtMs }.sortedBy { it.objectObservationAtMs }
  val hands = frames.filter { it.handObservationAtMs > 0L &&
   e.timestampMs - it.handObservationAtMs in 0L..1_400L && it.handNearPhoneConfidence > 0f }
   .distinctBy { it.handObservationAtMs }.sortedBy { it.handObservationAtMs }
  val sitMean = poses.map { it.sittingPoseConfidence }.meanOrZero()
  val sitVotes = if (poses.isEmpty()) 0f else poses.count { it.sittingPoseConfidence >= 0.52f }.toFloat() / poses.size
  val seatMean = seats.map { it.nearbySeatConfidence }.meanOrZero()
  val sitting = if (poses.size >= 3 && span(poses.map { it.poseObservationAtMs }) >= 1_400L &&
      sitVotes >= 0.60f && sitMean >= 0.56f) (sitMean * (0.90f + 0.10f * seatMean)).coerceAtMost(0.82f) else 0f
  val phoneMean = phones.map { it.phoneConfidence }.meanOrZero()
  val earMean = phones.map { it.phoneNearEarConfidence }.meanOrZero()
  val earVotes = if (phones.isEmpty()) 0f else phones.count { it.phoneNearEarConfidence >= 0.52f }.toFloat() / phones.size
  val phoneSpan = span(phones.map { it.objectObservationAtMs })
  val call = if (phones.size >= 2 && phoneSpan >= 800L && phoneMean >= 0.42f && earMean >= 0.52f && earVotes >= 0.60f)
   (0.48f + 0.28f * earMean + ((phones.size - 2) * 0.025f).coerceAtMost(0.08f)).coerceAtMost(0.82f) else 0f
  val socialMotion = frames.filter {
   it.visiblePersonCount >= 2 && it.faceProximityConfidence >= 0.42f &&
    it.motionObservationAtMs > 0L && e.timestampMs - it.motionObservationAtMs in 0L..1_500L &&
    it.motionScore >= 0.22f
  }.distinctBy { it.motionObservationAtMs }.sortedBy { it.motionObservationAtMs }
  val socialProximity = socialMotion.map { it.faceProximityConfidence }.meanOrZero()
  val socialMotionMean = socialMotion.map { it.motionScore }.meanOrZero()
  val socialConfidence = if (socialMotion.size >= 3 &&
    span(socialMotion.map { it.motionObservationAtMs }) >= 1_100L &&
    socialProximity >= 0.45f && socialMotionMean >= 0.25f)
   (0.35f + 0.35f * socialProximity + 0.20f * socialMotionMean).coerceAtMost(0.72f) else 0f

  val frontMean = phones.map { it.phoneInFrontConfidence }.meanOrZero()
  val handMean = hands.map { it.handNearPhoneConfidence }.meanOrZero()
  val browse = if (call < 0.55f && phones.size >= 2 && phoneSpan >= 1_000L && phoneMean >= 0.42f &&
      frontMean >= 0.52f && hands.size >= 2 && span(hands.map { it.handObservationAtMs }) >= 450L && handMean >= 0.45f)
   (0.43f + 0.16f * frontMean + 0.17f * handMean + ((phones.size - 2) * 0.02f).coerceAtMost(0.06f)).coerceAtMost(0.80f) else 0f
  val summaries = mutableListOf<String>()
  if (sitting >= 0.55f) summaries += if (seatMean >= 0.42f)
   "تظهر وضعية جسم تشبه الجلوس مع ظهور مقعد؛ يبقى ذلك تقديرًا بصريًا وقد يشبه القرفصاء."
  else "تظهر وضعية جسم تشبه الجلوس أو القرفصاء عبر عدة إطارات؛ ليست تأكيدًا على أن الشخص جالس."
  if (call >= 0.55f) summaries += "ظهر هاتف قرب جانب الرأس في رصدات متكررة؛ قد يكون الشخص في مكالمة، ولا أستطيع تأكيد وجود اتصال فعلي."
  else if (browse >= 0.55f) summaries += "ظهر هاتف أمام الشخص مع اقتراب اليد منه في رصدات متكررة؛ قد يستخدم الهاتف أو يتصفحه، ولا أستطيع معرفة محتوى الشاشة."
  if (socialConfidence >= 0.55f) summaries += "ألاحظ شخصين أو أكثر قريبين مع حركة متكررة؛ لا أستطيع التمييز بثقة بين المزاح والشجار من هذه الأدلة البصرية وحدها."
  return EvePersonContextSnapshot(
   sittingConfidence=sitting, phoneCallConfidence=call, phoneBrowsingConfidence=browse,
   interactionConfidence=socialConfidence, summaryArabic=summaries.joinToString("؛ "), observedAtMs=e.timestampMs
  )
 }
 @Synchronized fun reset() { samples.clear(); lastTimestampMs = 0L }
 private fun sanitize(e: EvePersonContextEvidence) = e.copy(
  personConfidence=e.personConfidence.unit(), sittingPoseConfidence=e.sittingPoseConfidence.unit(),
  phoneConfidence=e.phoneConfidence.unit(), phoneNearEarConfidence=e.phoneNearEarConfidence.unit(),
  phoneInFrontConfidence=e.phoneInFrontConfidence.unit(), handNearPhoneConfidence=e.handNearPhoneConfidence.unit(),
  nearbySeatConfidence=e.nearbySeatConfidence.unit(), faceProximityConfidence=e.faceProximityConfidence.unit(),
  motionScore=e.motionScore.unit(), visiblePersonCount=e.visiblePersonCount.coerceIn(0,20),
  motionObservationAtMs=e.motionObservationAtMs.coerceAtLeast(0L), poseObservationAtMs=e.poseObservationAtMs.coerceAtLeast(0L),
  objectObservationAtMs=e.objectObservationAtMs.coerceAtLeast(0L), handObservationAtMs=e.handObservationAtMs.coerceAtLeast(0L)
 )
 private fun Float.unit()=takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
 private fun List<Float>.meanOrZero()=if (isEmpty()) 0f else average().toFloat().coerceIn(0f,1f)
 private fun span(v:List<Long>)=if(v.size<2) 0L else (v.maxOrNull()!!-v.minOrNull()!!).coerceAtLeast(0L)
}
