#!/usr/bin/env python3
"""Train EVE's compact Arabic intent student from reviewed EVE-specific supervision."""
import base64, gzip, json, math, random, struct, pathlib

DATA = {
"greeting":["مرحبا","اهلا وسهلا","السلام عليكم","صباح الخير","مساء الخير","اهلين يا ايف","هلا ايف","هاي يا ايف","نورت يا ايف","كيفك يا ايف","اهلا بعودتك","يا ايف انا هنا"],
"wellbeing":["كيف حالك","كيفك اليوم","هل انت بخير","كيف كان يومك","ما اخبارك","كيف مزاجك الان","هل انت مرتاحة","هل تشعرين بالملل","كيف تشعرين اليوم","هل انت سعيدة","طمنيني عنك","شلونك اليوم"],
"identity":["من انت","ما اسمك","عرفيني بنفسك","ماذا تكونين","هل انت ايف","ما قصتك","من تكون ايف","هل انت روبوت","ما هويتك","هل انت انسان","اخبريني عن نفسك","من تكونين"],
"capabilities":["ماذا تستطيعين ان تفعلي","ما الذي يمكنك فعله","ما قدراتك","ماذا تعرفين","كيف يمكنك مساعدتي","ما مهاراتك","هل تستطيعين الرؤية","هل تفهمين الصور","هل يمكنك التعلم","ما الاشياء التي تجيدينها","كيف تعملين","ما امكانياتك"],
"offline":["هل تعملين بدون انترنت","هل تحتاجين الى الانترنت","هل انت متصلة بالانترنت","هل تعملين اوفلاين","هل ترسلين بياناتي","هل تحفظين صوري على الانترنت","هل بياناتي تبقى في الهاتف","هل تستخدمين السحابة","هل تحتاجين واي فاي","هل يمكنك العمل دون شبكة","هل كل شيء محلي","هل انت مستقلة عن الانترنت"],
"vision":["ماذا ترين","ماذا تشاهدين الان","وش تشوفين","ما هذا","ماذا يوجد امامك","صفي لي ما ترينه","ما الذي امام الكاميرا","هل ترين شيئا","ماذا يوجد في الصورة","ما الموجود هنا","انظري الى هذا","ما الذي تلاحظينه"],
"camera":["هل الكاميرا تعمل","اي كاميرا تعمل الان","هل تستخدمين الكاميرا الامامية","بدلي الكاميرا","شغلي الكاميرا","لماذا لا ترين","الكاميرا لا تعمل","هل الكاميرا مفتوحة","اين الكاميرا","كيف اغير الكاميرا","هل تستطيعين رؤيتي","الكاميرا الامامية ام الخلفية"],
"mood":["ما شعورك الان","ما حالتك العاطفية","بماذا تشعرين","هل انت غاضبة","هل انت فضولية","ما مزاجك","هل انت منزعجة","هل تشعرين بالراحة","كيف تشعر ايف","هل انت نائمة","هل انت متحمسة","ما حالتك الان"],
"curiosity":["لماذا تسألين كثيرا","لماذا تشعرين بالفضول","ما الذي يثير فضولك","لماذا تريدين معرفة ذلك","كيف تختارين اسئلتك","هل لديك فضول","لماذا تراقبين الاشياء","ماذا تريدين ان تتعلمي","لماذا تهتمين بهذا الشيء","ما الذي لفت انتباهك","لماذا تطرحين الاسئلة","هل تسألين بشكل عشوائي"],
"memory":["ماذا تتذكرين","هل تتذكرين ما حدث","ما الذي حفظته","هل لديك ذاكرة","ماذا تعلمت مني","هل تنسين الاشياء","ما اخر شيء تعلمته","هل تحفظين تجاربك","هل تتذكرين حديثنا","كيف تعمل ذاكرتك","هل تتذكرين الاشياء التي رأيتها","ما الذي تعرفينه عن تجاربنا"],
"people":["من هذا الشخص","هل تعرفين هذا الشخص","هل تتذكرين امي","من صديقي","هل تعرفين افراد عائلتي","ما اسم الشخص الذي حفظته","هل تعرفينني","هل تتعرفين على وجهي","من الشخص امامك","هل هذا شخص تعرفينه","هل حفظت وجه امي","هل تستطيعين تمييز صديقي"],
"affection":["احبك","انا احبك يا ايف","انت صديقتي","اشتقت اليك","انت رفيقتي","انا سعيد بوجودك","احب وجودك معي","انت مهمة بالنسبة لي","انت قريبة مني","يا ايف احبك","انت رفيقتي المفضلة","انا متعلق بك"],
"praise":["انت رائعة","احسنت يا ايف","عمل ممتاز","انت ذكية","هذا رائع","ممتازة","فخورة بك","كان اداؤك جيدا","انت مذهلة","اجابة جميلة","اعجبني ما فعلت","شغل ممتاز"],
"thanks":["شكرا","شكرا لك","ممتن لك","تسلمين","يعطيك العافية","اشكرك يا ايف","شكرا على المساعدة","بارك الله فيك","هذا لطف منك","مشكورة","شكرا جزيلا","الله يعطيك العافية"],
"farewell":["مع السلامة","الى اللقاء","تصبحين على خير","سأذهب الان","وداعا","اشوفك لاحقا","الى وقت اخر","سأغلق التطبيق","باي يا ايف","نلتقي لاحقا","سأعود لاحقا","ليلة سعيدة"],
"play":["هيا نلعب","لنلعب الشطرنج","اريد ان العب معك","ابدئي لعبة","هل نلعب اكس او","تحديني في لعبة","لنلعب جولة","اريد لعبة جديدة","هل تستطيعين اللعب","ابدئي الشطرنج","هيا نتحدى بعضنا","نلعب الان"],
"help":["ساعديني","احتاج مساعدتك","ماذا افعل","اريد مساعدتك","هل يمكنك مساعدتي","ساعديني في هذا","انا لا افهم","اشرح لي","احتاج الى شرح","ماذا تنصحين","اعطيني فكرة","ممكن تساعديني"]
}
LABELS=list(DATA)
FEATURES=1024

def normalize(s):
    for a,b in (("أ","ا"),("إ","ا"),("آ","ا"),("ى","ي"),("ة","ه"),("ؤ","و"),("ئ","ي")):
        s=s.replace(a,b)
    return " ".join(s.lower().strip().split())

def features(s):
    s=normalize(s); out={}
    def add(k,v):
        h=2166136261
        for ch in k: h=((h ^ ord(ch))*16777619)&0xffffffff
        i=h%FEATURES; out[i]=out.get(i,0.0)+v
    p="^"+s.replace(" ","_")+"$"
    for n in (2,3,4):
        for i in range(max(0,len(p)-n+1)): add("c"+p[i:i+n],1.0)
    t=s.split()
    for w in t: add("w"+w,1.5)
    for a,b in zip(t,t[1:]): add("b"+a+"_"+b,1.2)
    z=math.sqrt(sum(v*v for v in out.values())) or 1.0
    return {i:v/z for i,v in out.items()}

def render(packed):
    chunks=[packed[i:i+16000] for i in range(0,len(packed),16000)]
    packed_expr=" +\n        ".join(json.dumps(c) for c in chunks)
    labels=", ".join(json.dumps(x,ensure_ascii=False) for x in LABELS)
    stop={"ما","ماذا","كيف","لماذا","هل","من","هذا","هذه","في","على","الى","عن","مع","يا","ان","انت","انا","هو","هي","لا","لم","لن","قد","متى","اين","كم","الان","اليوم","بدون","دون","لي","لك","ثم","او","و","ف"}
    vocab=sorted({w for phrases in DATA.values() for phrase in phrases for w in normalize(phrase).split() if len(w)>1 and w not in stop})
    known=", ".join(json.dumps(x,ensure_ascii=False) for x in vocab)
    return f"""package com.eve.robot

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.sqrt

/** EVE's trained compact Arabic intent student, trained from reviewed EVE-specific supervision. */
internal object EveDistilledArabicStudent {{
    private const val FEATURE_COUNT = {FEATURES}
    private val labels = listOf({labels})
    private val knownTokens = setOf({known})
    private val packedWeights = {packed_expr}
    private val weights: FloatArray by lazy {{
        val compressed = Base64.getDecoder().decode(packedWeights)
        val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use {{ it.readBytes() }}
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(raw.size / 4) {{ buffer.float }}
    }}
    data class Result(val intent: String?, val confidence: Float, val margin: Float)
    fun classify(raw: String): Result {{
        val text = EveArabicTextUnderstanding.normalize(raw)
        if (text.isBlank()) return Result(null, 0f, 0f)
        val evidence = EveArabicTextUnderstanding.featureTokens(raw).any {{ it in knownTokens }}
        if (!evidence) return Result(null, 0f, 0f)
        val features = HashMap<Int, Float>()
        fun add(key: String, value: Float) {{
            var h = 0x811c9dc5.toInt()
            for (ch in key) {{ h = h xor ch.code; h *= 0x01000193 }}
            val index = (h.toLong() and 0xffffffffL).rem(FEATURE_COUNT.toLong()).toInt()
            features[index] = (features[index] ?: 0f) + value
        }}
        val padded = "^" + text.replace(" ", "_") + "$"
        for (n in 2..4) for (i in 0..(padded.length-n)) add("c"+padded.substring(i,i+n),1f)
        val tokens = text.split(Regex("\\\\s+")).filter {{ it.isNotBlank() }}
        tokens.forEach {{ add("w"+it,1.5f) }}
        for (i in 0 until tokens.size-1) add("b"+tokens[i]+"_"+tokens[i+1],1.2f)
        val norm = sqrt(features.values.sumOf {{ it*it }}).takeIf {{ it>0f }} ?: 1f
        val stride = FEATURE_COUNT+1
        val model = weights
        val logits = FloatArray(labels.size)
        for (c in labels.indices) {{
            var score = model[c*stride+FEATURE_COUNT]
            for ((i,v) in features) score += model[c*stride+i]*(v/norm)
            logits[c]=score
        }}
        val maxLogit=logits.maxOrNull() ?: return Result(null,0f,0f)
        val exps=FloatArray(logits.size) {{ exp((logits[it]-maxLogit).coerceIn(-30f,30f)) }}
        val total=exps.sum().coerceAtLeast(1e-9f)
        val probs=FloatArray(exps.size) {{ exps[it]/total }}
        val order=probs.indices.sortedByDescending {{ probs[it] }}
        val best=order[0]
        val second=probs[order.getOrElse(1) {{ best }}]
        val confidence=probs[best]
        val margin=confidence-second
        val accepted=confidence>=0.43f && margin>=0.10f
        return Result(labels[best].takeIf {{ accepted }},confidence,margin)
    }}
}}
"""

def main():
    rows=[]
    for label,phrases in DATA.items():
        for p in phrases:
            rows.extend([(p,label),("يا ايف "+p,label)])
            if p.startswith("هل "): rows.append((p[3:],label))
    random.seed(42); random.shuffle(rows)
    W=[[0.0]*(FEATURES+1) for _ in LABELS]
    for epoch in range(100):
        random.shuffle(rows); lr=0.16/(1+epoch*0.012)
        for p,label in rows:
            x=features(p); y=LABELS.index(label)
            z=[W[c][FEATURES]+sum(W[c][i]*v for i,v in x.items()) for c in range(len(LABELS))]
            m=max(z); e=[math.exp(max(-30,min(30,v-m))) for v in z]; sm=sum(e); probs=[v/sm for v in e]
            for c in range(len(LABELS)):
                err=probs[c]-(1.0 if c==y else 0.0)
                for i,v in x.items(): W[c][i]-=lr*(err*v+0.00015*W[c][i])
                W[c][FEATURES]-=lr*err
    raw=struct.pack("<"+"f"*(len(LABELS)*(FEATURES+1)),*(v for row in W for v in row))
    packed=base64.b64encode(gzip.compress(raw,compresslevel=9,mtime=0)).decode()
    target=pathlib.Path("eve-project/app/src/main/java/com/eve/robot/EveDistilledArabicStudent.kt")
    target.write_text(render(packed),encoding="utf-8")
    print(f"trained {len(LABELS)} intents on {len(rows)} curated labeled examples; {len(packed)} base64 chars")

if __name__=="__main__":
    main()
