package com.example.russianstep

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private data class RussianWord(
    val id: String,
    val russian: String,
    val pronunciation: String,
    val meaning: String,
    val exampleRussian: String,
    val exampleArabic: String,
    val category: String,
    val custom: Boolean = false
)

private data class WordProgress(
    val reviews: Int,
    val streak: Int,
    val intervalDays: Float,
    val ease: Float,
    val dueAt: Long,
    val correct: Int,
    val wrong: Int
)

private enum class StudyTab { LEARN, QUIZ, DICTIONARY }
private enum class Grade { AGAIN, HARD, GOOD, EASY }

/**
 * Russian Step by Step is an offline-first, Arabic-language vocabulary tutor.
 * No INTERNET permission is declared; progress and custom words stay in SharedPreferences.
 */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("russian_step_local", MODE_PRIVATE) }
    private val backgroundColor = Color.rgb(246, 247, 243)
    private val inkColor = Color.rgb(31, 48, 59)
    private val tealColor = Color.rgb(14, 126, 121)
    private val mutedColor = Color.rgb(103, 119, 126)
    private val paleTeal = Color.rgb(225, 243, 239)
    private val white = Color.WHITE
    private val dailyGoal = 5

    private lateinit var statsContainer: LinearLayout
    private lateinit var bodyContainer: LinearLayout
    private lateinit var tabsContainer: LinearLayout
    private var activeTab = StudyTab.LEARN
    private var revealedWordId: String? = null
    private var quizWordId: String? = null
    private var quizOptionsIds: List<String> = emptyList()
    private var quizSelectedId: String? = null
    private var quizWasCorrect: Boolean? = null
    private var offlineRussianVoice = false
    private var tts: TextToSpeech? = null

    private val baseWords = listOf(
        RussianWord("w001","Привет","بريڤيت","مرحبًا (غير رسمي)","Привет, Анна!","مرحبًا يا آنا!","تحية"),
        RussianWord("w002","Здравствуйте","زدرَستڤويتي","مرحبًا (رسمي)","Здравствуйте, доктор.","مرحبًا أيها الطبيب.","تحية"),
        RussianWord("w003","Спасибо","سپاسيبا","شكرًا","Спасибо большое.","شكرًا جزيلًا.","تحية"),
        RussianWord("w004","Пожалуйста","پاجالوستا","من فضلك / العفو","Воду, пожалуйста.","ماءً من فضلك.","تحية"),
        RussianWord("w005","Да","دا","نعم","Да, я понимаю.","نعم، أنا أفهم.","أساسيات"),
        RussianWord("w006","Нет","نيت","لا","Нет, спасибо.","لا، شكرًا.","أساسيات"),
        RussianWord("w007","Извините","إزڤينيتِه","عذرًا / آسف","Извините, где метро?","عذرًا، أين المترو؟","تحية"),
        RussianWord("w008","Я","يا","أنا","Я дома.","أنا في المنزل.","ضمائر"),
        RussianWord("w009","Ты","تي","أنتَ / أنتِ (غير رسمي)","Ты мой друг.","أنت صديقي.","ضمائر"),
        RussianWord("w010","Вы","ڤي","أنت حضرتك / أنتم","Вы говорите по-русски?","هل تتحدث الروسية؟","ضمائر"),
        RussianWord("w011","Он","أون","هو","Он здесь.","هو هنا.","ضمائر"),
        RussianWord("w012","Она","أنا","هي","Она дома.","هي في المنزل.","ضمائر"),
        RussianWord("w013","Мы","مي","نحن","Мы учимся.","نحن نتعلم.","ضمائر"),
        RussianWord("w014","Они","أني","هم","Они здесь.","هم هنا.","ضمائر"),
        RussianWord("w015","Как дела?","كاك ديلا","كيف حالك؟","Привет! Как дела?","مرحبًا! كيف حالك؟","عبارات"),
        RussianWord("w016","Хорошо","خراشو","جيد / بخير","У меня всё хорошо.","أنا بخير.","عبارات"),
        RussianWord("w017","Плохо","پلوخا","سيئ","Сегодня мне плохо.","أشعر أنني لست بخير اليوم.","عبارات"),
        RussianWord("w018","Меня зовут...","مينيا زاڤوت","اسمي...","Меня зовут Омар.","اسمي عمر.","عبارات"),
        RussianWord("w019","Я не понимаю","يا ني پانيمايو","أنا لا أفهم","Простите, я не понимаю.","عذرًا، أنا لا أفهم.","عبارات"),
        RussianWord("w020","Повторите, пожалуйста","پاڤتاريتي پاجالوستا","أعد من فضلك","Повторите, пожалуйста, медленно.","أعد من فضلك ببطء.","عبارات"),
        RussianWord("w021","Где туалет?","غدي تواليت","أين الحمام؟","Скажите, где туалет?","أخبرني، أين الحمام؟","السفر"),
        RussianWord("w022","Сколько стоит?","سكولكا ستويت","كم السعر؟","Сколько это стоит?","كم ثمن هذا؟","السفر"),
        RussianWord("w023","Вода","ڤادا","ماء","Мне нужна вода.","أحتاج إلى ماء.","طعام"),
        RussianWord("w024","Еда","ييدا","طعام","Это вкусная еда.","هذا طعام لذيذ.","طعام"),
        RussianWord("w025","Хлеб","خليب","خبز","Я покупаю хлеб.","أنا أشتري الخبز.","طعام"),
        RussianWord("w026","Молоко","مالاكو","حليب","Я пью молоко.","أنا أشرب الحليب.","طعام"),
        RussianWord("w027","Чай","تشاي","شاي","Я хочу чай.","أريد شايًا.","طعام"),
        RussianWord("w028","Кофе","كوفي","قهوة","Один кофе, пожалуйста.","قهوة واحدة من فضلك.","طعام"),
        RussianWord("w029","Дом","دوم","منزل","Это мой дом.","هذا منزلي.","أماكن"),
        RussianWord("w030","Работа","رابوتا","عمل","Я иду на работу.","أنا ذاهب إلى العمل.","أماكن"),
        RussianWord("w031","Школа","شكولا","مدرسة","Школа рядом.","المدرسة قريبة.","أماكن"),
        RussianWord("w032","Книга","كنيغا","كتاب","Это интересная книга.","هذا كتاب ممتع.","أشياء"),
        RussianWord("w033","Телефон","تيليفون","هاتف","Где мой телефон?","أين هاتفي؟","أشياء"),
        RussianWord("w034","Друг","دروغ","صديق","Он мой друг.","هو صديقي.","أشخاص"),
        RussianWord("w035","Семья","سيميا","عائلة","Моя семья большая.","عائلتي كبيرة.","أشخاص"),
        RussianWord("w036","Мама","ماما","أمي","Моя мама дома.","أمي في المنزل.","أشخاص"),
        RussianWord("w037","Папа","پاپا","أبي","Мой папа работает.","أبي يعمل.","أشخاص"),
        RussianWord("w038","Сегодня","سيڤودنيا","اليوم","Сегодня хорошая погода.","الطقس جميل اليوم.","الوقت"),
        RussianWord("w039","Завтра","زافترا","غدًا","Увидимся завтра.","أراك غدًا.","الوقت"),
        RussianWord("w040","Вчера","ڤتشيرا","أمس","Вчера я был дома.","كنت في المنزل أمس.","الوقت"),
        RussianWord("w041","Утро","أوترا","صباح","Доброе утро!","صباح الخير!","الوقت"),
        RussianWord("w042","День","دين","نهار / يوم","Хорошего дня!","أتمنى لك يومًا سعيدًا!","الوقت"),
        RussianWord("w043","Вечер","ڤيتشير","مساء","Добрый вечер!","مساء الخير!","الوقت"),
        RussianWord("w044","Ночь","نوتش","ليل","Спокойной ночи.","تصبح على خير.","الوقت"),
        RussianWord("w045","Хочу","خاتشو","أريد","Я хочу есть.","أريد أن آكل.","أفعال"),
        RussianWord("w046","Мне нужно","مني نوجنا","أحتاج إلى","Мне нужно помочь.","أحتاج إلى المساعدة.","عبارات"),
        RussianWord("w047","Помогите","پاماغيتي","ساعدوني / ساعدني","Помогите мне, пожалуйста.","ساعدني من فضلك.","السفر"),
        RussianWord("w048","Осторожно","أستاروجنا","انتبه / بحذر","Осторожно, горячо!","انتبه، إنه ساخن!","عبارات"),
        RussianWord("w049","Большой","بالشوي","كبير","Это большой дом.","هذا منزل كبير.","صفات"),
        RussianWord("w050","Маленький","مالينكي","صغير","У меня маленькая сумка.","لدي حقيبة صغيرة.","صفات"),
        RussianWord("w051","Быстро","بيسترا","بسرعة","Он говорит быстро.","هو يتحدث بسرعة.","صفات"),
        RussianWord("w052","Медленно","ميدلينا","ببطء","Говорите медленно.","تحدث ببطء.","صفات"),
        RussianWord("w053","Слушать","سلوشات","يستمع","Я люблю слушать музыку.","أحب الاستماع إلى الموسيقى.","أفعال"),
        RussianWord("w054","Говорить","گاڤاريت","يتكلم","Я немного говорю по-русски.","أتحدث الروسية قليلًا.","أفعال"),
        RussianWord("w055","Учить","أوتشيت","يتعلم / يدرّس","Я учу русский язык.","أنا أتعلم اللغة الروسية.","أفعال"),
        RussianWord("w056","Запомнить","زاپومنيت","يتذكر / يحفظ","Мне трудно запомнить слово.","يصعب عليّ حفظ الكلمة.","أفعال"),
        RussianWord("w057","Повторять","پاڤتارْيات","يكرر","Нужно повторять каждый день.","يجب أن نكرر كل يوم.","أفعال"),
        RussianWord("w058","Немного","نيمناگا","قليلًا","Я понимаю немного.","أفهم قليلًا.","صفات"),
        RussianWord("w059","Почему?","پاتشيمو","لماذا؟","Почему ты здесь?","لماذا أنت هنا؟","أسئلة"),
        RussianWord("w060","Потому что","پاتامو شتا","لأن","Я дома, потому что устал.","أنا في المنزل لأنني متعب.","عبارات"),
        RussianWord("w061","Где?","غدي","أين؟","Где магазин?","أين المتجر؟","أسئلة"),
        RussianWord("w062","Когда?","كاغدا","متى؟","Когда автобус?","متى الحافلة؟","أسئلة"),
        RussianWord("w063","Кто?","كتو","مَن؟","Кто это?","من هذا؟","أسئلة"),
        RussianWord("w064","Что?","شتو","ماذا؟ / ما؟","Что это?","ما هذا؟","أسئلة"),
        RussianWord("w065","Люблю","ليوبليو","أحب","Я люблю музыку.","أحب الموسيقى.","أفعال"),
        RussianWord("w066","Музыка","موزيكا","موسيقى","Я слушаю музыку.","أنا أستمع إلى الموسيقى.","أشياء"),
        RussianWord("w067","Магазин","ماگازين","متجر","Магазин открыт.","المتجر مفتوح.","أماكن"),
        RussianWord("w068","Автобус","افتوبوس","حافلة","Где автобус?","أين الحافلة؟","السفر"),
        RussianWord("w069","Время","ڤريميا","وقت","У меня нет времени.","ليس لدي وقت.","الوقت"),
        RussianWord("w070","Я учусь","يا أوتشوس","أنا أتعلم / أدرس","Я учусь каждый день.","أنا أتعلم كل يوم.","عبارات")
    )

    private val words: List<RussianWord>
        get() = baseWords + loadCustomWords()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(16), dp(10), dp(16), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        header.addView(textView("روسي خطوة خطوة", 27f, inkColor, true))
        header.addView(textView("تعلّم بهدوء، وتذكّر بالتكرار الذكي", 14f, mutedColor))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        statsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(paleTeal, 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(statsContainer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val scroll = ScrollView(this).apply { fillViewport = true; clipToPadding = false }
        bodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), dp(2), dp(0), dp(12))
        }
        scroll.addView(bodyContainer, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        tabsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(0), dp(8), dp(0), dp(4))
        }
        root.addView(tabsContainer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        render()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) configureOfflineRussianVoice()
        }
    }

    private fun configureOfflineRussianVoice() {
        val engine = tts ?: return
        try {
            val voices = engine.voices ?: emptySet<Voice>()
            val localVoice = voices
                .filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
                .maxByOrNull { it.quality }
            if (localVoice != null) {
                engine.language = Locale("ru", "RU")
                engine.voice = localVoice
                engine.setSpeechRate(0.78f)
                engine.setPitch(1.0f)
                offlineRussianVoice = true
            } else {
                offlineRussianVoice = false
            }
            runOnUiThread { render() }
        } catch (_: Exception) {
            offlineRussianVoice = false
            runOnUiThread { render() }
        }
    }

    private fun speak(word: RussianWord) {
        if (!offlineRussianVoice) {
            toast("لم أجد صوتًا روسيًا محليًا مثبتًا. يمكنك استخدام النطق التقريبي المكتوب؛ التطبيق نفسه لا يحتاج الإنترنت.")
            return
        }
        try {
            tts?.speak(word.russian, TextToSpeech.QUEUE_FLUSH, null, "russian-" + word.id)
        } catch (_: Exception) {
            toast("تعذّر تشغيل الصوت المحلي.")
        }
    }

    private fun render() {
        renderStats()
        bodyContainer.removeAllViews()
        when (activeTab) {
            StudyTab.LEARN -> renderLearn()
            StudyTab.QUIZ -> renderQuiz()
            StudyTab.DICTIONARY -> renderDictionary()
        }
        renderTabs()
    }

    private fun renderStats() {
        statsContainer.removeAllViews()
        val learned = words.count { progress(it.id).streak > 0 }
        val due = dueWords().size
        val newToday = dailyNewCount()
        statsContainer.addView(textView("خطة اليوم: " + newToday + " / " + dailyGoal + " كلمات جديدة", 15f, inkColor, true))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = dailyGoal
        bar.progress = newToday.coerceAtMost(dailyGoal)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(tealColor)
        statsContainer.addView(bar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(7); bottomMargin = dp(6) })
        statsContainer.addView(textView("بدأت تذكر " + learned + " كلمة  •  للمراجعة الآن: " + due, 13f, mutedColor))
    }

    private fun renderTabs() {
        tabsContainer.removeAllViews()
        addTabButton("تعلّم", StudyTab.LEARN)
        addTabButton("اختبر نفسك", StudyTab.QUIZ)
        addTabButton("قاموسي", StudyTab.DICTIONARY)
    }

    private fun addTabButton(label: String, tab: StudyTab) {
        val selected = activeTab == tab
        val button = Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else inkColor)
            background = rounded(if (selected) tealColor else Color.rgb(231, 235, 232), 14)
            minHeight = dp(46)
            stateListAnimator = null
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener {
                if (activeTab != tab) {
                    activeTab = tab
                    revealedWordId = null
                    quizWordId = null
                    quizOptionsIds = emptyList()
                    quizSelectedId = null
                    quizWasCorrect = null
                    render()
                }
            }
        }
        tabsContainer.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
    }

    private fun renderLearn() {
        bodyContainer.addView(textView("جلسة قصيرة", 21f, inkColor, true))
        bodyContainer.addView(textView("كلمة واحدة في كل مرة. لا توجد عقوبة على النسيان؛ إعادة الكلمة جزء من التعلّم.", 14f, mutedColor))
        gap(bodyContainer, 10)

        val word = nextLearnWord()
        if (word == null) {
            val card = card()
            card.addView(textView("أحسنت! أنهيت المتاح لك الآن 🌿", 21f, inkColor, true))
            gap(card, 6)
            card.addView(textView("يمكنك العودة لاحقًا عندما يحين موعد المراجعة، أو فتح القاموس لإضافة كلماتك.", 15f, mutedColor))
            val toQuiz = actionButton("اختبر نفسك الآن", tealColor) {
                activeTab = StudyTab.QUIZ
                quizWordId = null
                render()
            }
            card.addView(toQuiz, matchWrap())
            bodyContainer.addView(card)
            return
        }

        val p = progress(word.id)
        val isNew = p.reviews == 0
        val card = card()
        card.addView(textView(if (isNew) "كلمة جديدة • " + word.category else "مراجعة • " + word.category, 13f, tealColor, true))
        gap(card, 8)
        card.addView(textView(word.russian, 34f, inkColor, true))
        gap(card, 3)
        card.addView(textView("النطق التقريبي: " + word.pronunciation, 18f, tealColor, true))
        gap(card, 8)
        card.addView(textView("اقرأ الكلمة بصوتك، ثم حاول تذكّر معناها قبل الكشف.", 14f, mutedColor))
        gap(card, 8)
        card.addView(actionButton(if (offlineRussianVoice) "🔊 استمع للنطق الروسي" else "🔊 الصوت الروسي المحلي غير متاح", Color.rgb(50, 88, 105)) {
            speak(word)
        }, matchWrap())
        gap(card, 6)
        card.addView(actionButton(if (revealedWordId == word.id) "إخفاء المعنى" else "أظهر المعنى والمثال", paleTeal) {
            revealedWordId = if (revealedWordId == word.id) null else word.id
            render()
        }.apply { setTextColor(inkColor) }, matchWrap())

        if (revealedWordId == word.id) {
            gap(card, 8)
            val meaningCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.rgb(247, 250, 248), 14)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            meaningCard.addView(textView(word.meaning, 23f, inkColor, true))
            gap(meaningCard, 8)
            meaningCard.addView(textView(word.exampleRussian, 19f, tealColor, true))
            meaningCard.addView(textView(word.exampleArabic, 16f, mutedColor))
            card.addView(meaningCard, matchWrap())
            gap(card, 10)
            card.addView(textView("كيف تذكّرتها؟ اختر الإجابة الأقرب:", 14f, inkColor, true))
            gap(card, 4)
            addGradeButtons(card, word)
        } else {
            gap(card, 8)
            card.addView(textView("اضغط «أظهر المعنى» بعد أن تحاول التذكّر.", 13f, mutedColor))
        }
        bodyContainer.addView(card)

        gap(bodyContainer, 10)
        bodyContainer.addView(actionButton("أضف كلمة تريد حفظها", Color.rgb(231, 235, 232)) {
            showAddWordDialog()
        }.apply { setTextColor(inkColor) }, matchWrap())
    }

    private fun renderQuiz() {
        bodyContainer.addView(textView("اختبار استرجاع بسيط", 21f, inkColor, true))
        bodyContainer.addView(textView("اقرأ المعنى العربي واختر الكلمة الروسية. التلميح الصوتي اختياري.", 14f, mutedColor))
        gap(bodyContainer, 10)

        if (quizWordId == null || words.none { it.id == quizWordId }) {
            prepareQuiz()
        }
        val word = words.firstOrNull { it.id == quizWordId } ?: words.first()
        val card = card()
        card.addView(textView("ما الكلمة الروسية التي تعني؟", 15f, mutedColor, true))
        gap(card, 6)
        card.addView(textView(word.meaning, 27f, inkColor, true))
        gap(card, 6)
        card.addView(textView("تذكّرها بنفسك قبل اختيار الإجابة.", 14f, mutedColor))
        gap(card, 12)

        if (quizSelectedId == null) {
            quizOptionsIds.mapNotNull { id -> words.firstOrNull { it.id == id } }.forEach { option ->
                card.addView(actionButton(option.russian + "   •   " + option.pronunciation, Color.rgb(235, 240, 238)) {
                    quizSelectedId = option.id
                    quizWasCorrect = option.id == word.id
                    render()
                }.apply { setTextColor(inkColor) }, matchWrap())
                gap(card, 6)
            }
            card.addView(actionButton("🔊 اسمع الكلمة الصحيحة", tealColor) { speak(word) }, matchWrap())
        } else {
            val correct = quizWasCorrect == true
            card.addView(textView(
                if (correct) "صحيح! أحسنت 🌟" else "لا بأس، نتعلّم بالتكرار.",
                22f, if (correct) tealColor else Color.rgb(153, 83, 52), true
            ))
            gap(card, 6)
            card.addView(textView(word.russian + "  •  " + word.pronunciation, 22f, inkColor, true))
            gap(card, 4)
            card.addView(textView(word.exampleRussian, 18f, tealColor, true))
            card.addView(textView(word.exampleArabic, 15f, mutedColor))
            gap(card, 10)
            card.addView(textView("ما مدى سهولة تذكّرها؟", 14f, inkColor, true))
            gap(card, 5)
            addGradeButtons(card, word)
            gap(card, 8)
            card.addView(actionButton("تجاوز إلى سؤال آخر", Color.rgb(231, 235, 232)) {
                prepareQuiz()
                render()
            }.apply { setTextColor(inkColor) }, matchWrap())
        }
        bodyContainer.addView(card)
    }

    private fun prepareQuiz() {
        val candidates = dueWords().ifEmpty {
            words.filter { progress(it.id).reviews > 0 }.ifEmpty { words }
        }
        val previous = quizWordId
        val chosen = (candidates.filter { it.id != previous }.ifEmpty { candidates }).random()
        quizWordId = chosen.id
        val distractors = words.filter { it.id != chosen.id && it.meaning != chosen.meaning }
            .shuffled().take(3).map { it.id }
        quizOptionsIds = (distractors + chosen.id).shuffled()
        quizSelectedId = null
        quizWasCorrect = null
    }

    private fun renderDictionary(initialQuery: String = "") {
        bodyContainer.addView(textView("قاموسي الشخصي", 21f, inkColor, true))
        bodyContainer.addView(textView("ابحث بالعربية أو بالروسية، وأضف الكلمات التي تحتاجها. كل شيء محفوظ على الهاتف.", 14f, mutedColor))
        gap(bodyContainer, 8)
        bodyContainer.addView(actionButton("＋ أضف كلمة جديدة", tealColor) { showAddWordDialog() }, matchWrap())
        gap(bodyContainer, 8)
        val search = EditText(this).apply {
            hint = "ابحث عن كلمة أو معنى..."
            textSize = 16f
            setSingleLine(true)
            setTextColor(inkColor)
            setHintTextColor(mutedColor)
            background = rounded(Color.WHITE, 14)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setText(initialQuery)
        }
        bodyContainer.addView(search, matchWrap())
        gap(bodyContainer, 8)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyContainer.addView(results, matchWrap())
        fun fillResults(query: String) {
            results.removeAllViews()
            val q = query.trim().lowercase(Locale.ROOT)
            val found = words.filter {
                q.isBlank() || it.russian.lowercase(Locale.ROOT).contains(q) ||
                    it.meaning.lowercase(Locale.ROOT).contains(q) ||
                    it.pronunciation.lowercase(Locale.ROOT).contains(q) ||
                    it.category.lowercase(Locale.ROOT).contains(q)
            }.take(100)
            if (found.isEmpty()) {
                results.addView(textView("لم أجد كلمة مطابقة. يمكنك إضافتها من الزر أعلاه.", 15f, mutedColor))
            }
            found.forEach { word ->
                val item = card()
                item.addView(textView(word.russian, 23f, inkColor, true))
                item.addView(textView(word.pronunciation, 16f, tealColor, true))
                item.addView(textView(word.meaning, 18f, inkColor))
                item.addView(textView(word.exampleRussian, 15f, tealColor))
                item.addView(textView(word.exampleArabic, 14f, mutedColor))
                gap(item, 5)
                item.addView(actionButton("🔊 اسمع", Color.rgb(231, 235, 232)) { speak(word) }.apply { setTextColor(inkColor) }, matchWrap())
                val p = progress(word.id)
                val status = when {
                    p.reviews == 0 -> "لم تبدأ حفظها بعد"
                    p.streak == 0 -> "تحتاج إلى إعادة قريبة"
                    p.dueAt <= System.currentTimeMillis() -> "حان وقت المراجعة"
                    else -> "موعد مراجعتها لاحقًا"
                }
                item.addView(textView(status, 12f, mutedColor))
                results.addView(item, matchWrap().apply { bottomMargin = dp(8) })
            }
        }
        fillResults(initialQuery)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                fillResults(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun addGradeButtons(parent: LinearLayout, word: RussianWord) {
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        row1.addView(actionButton("لم أتذكر", Color.rgb(250, 230, 224)) {
            grade(word, Grade.AGAIN)
        }.apply { setTextColor(Color.rgb(128, 52, 43)) }, weighted())
        row1.addView(actionButton("صعب", Color.rgb(250, 240, 214)) {
            grade(word, Grade.HARD)
        }.apply { setTextColor(Color.rgb(123, 83, 31)) }, weighted())
        parent.addView(row1, matchWrap())
        gap(parent, 5)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        row2.addView(actionButton("تذكّرتها", paleTeal) {
            grade(word, Grade.GOOD)
        }.apply { setTextColor(inkColor) }, weighted())
        row2.addView(actionButton("سهلة", tealColor) {
            grade(word, Grade.EASY)
        }, weighted())
        parent.addView(row2, matchWrap())
    }

    private fun grade(word: RussianWord, grade: Grade) {
        val old = progress(word.id)
        if (old.reviews == 0) {
            ensureStudyDay()
            prefs.edit().putInt("daily_new_count", dailyNewCount() + 1).apply()
        }
        var ease = old.ease
        var streak = old.streak
        val interval: Float
        when (grade) {
            Grade.AGAIN -> {
                streak = 0
                ease = (ease - 0.20f).coerceAtLeast(1.30f)
                interval = 10f / (24f * 60f)
            }
            Grade.HARD -> {
                streak = (streak + 1).coerceAtLeast(1)
                ease = (ease - 0.12f).coerceAtLeast(1.30f)
                interval = if (old.streak == 0) 1f else (old.intervalDays * 1.20f).coerceAtLeast(1f)
            }
            Grade.GOOD -> {
                streak += 1
                interval = when (old.streak) {
                    0 -> 1f
                    1 -> 3f
                    else -> (old.intervalDays * ease).coerceAtLeast(1f)
                }
            }
            Grade.EASY -> {
                streak += 1
                ease = (ease + 0.15f).coerceAtMost(3.0f)
                interval = if (old.streak == 0) 3f else (old.intervalDays * (ease + 0.35f)).coerceAtLeast(2f)
            }
        }
        val now = System.currentTimeMillis()
        val correct = old.correct + if (grade == Grade.AGAIN) 0 else 1
        val wrong = old.wrong + if (grade == Grade.AGAIN) 1 else 0
        prefs.edit()
            .putInt(key(word.id, "reviews"), old.reviews + 1)
            .putInt(key(word.id, "streak"), streak)
            .putFloat(key(word.id, "interval"), interval)
            .putFloat(key(word.id, "ease"), ease)
            .putLong(key(word.id, "due"), now + (interval * 24f * 60f * 60f * 1000f).toLong())
            .putInt(key(word.id, "correct"), correct)
            .putInt(key(word.id, "wrong"), wrong)
            .apply()

        revealedWordId = null
        if (activeTab == StudyTab.QUIZ) {
            quizWordId = null
            quizOptionsIds = emptyList()
            quizSelectedId = null
            quizWasCorrect = null
        }
        render()
    }

    private fun nextLearnWord(): RussianWord? {
        ensureStudyDay()
        val newCount = dailyNewCount()
        if (newCount < dailyGoal) {
            words.firstOrNull { progress(it.id).reviews == 0 }?.let { return it }
        }
        return dueWords().firstOrNull()
    }

    private fun dueWords(): List<RussianWord> {
        val now = System.currentTimeMillis()
        return words.filter {
            val p = progress(it.id)
            p.reviews > 0 && p.dueAt <= now
        }.sortedBy { progress(it.id).dueAt }
    }

    private fun progress(id: String): WordProgress = WordProgress(
        reviews = prefs.getInt(key(id, "reviews"), 0),
        streak = prefs.getInt(key(id, "streak"), 0),
        intervalDays = prefs.getFloat(key(id, "interval"), 0f),
        ease = prefs.getFloat(key(id, "ease"), 2.35f),
        dueAt = prefs.getLong(key(id, "due"), 0L),
        correct = prefs.getInt(key(id, "correct"), 0),
        wrong = prefs.getInt(key(id, "wrong"), 0)
    )

    private fun key(id: String, field: String) = "p_" + id + "_" + field

    private fun ensureStudyDay() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString("study_day", "") != today) {
            prefs.edit().putString("study_day", today).putInt("daily_new_count", 0).apply()
        }
    }

    private fun dailyNewCount(): Int {
        ensureStudyDay()
        return prefs.getInt("daily_new_count", 0)
    }

    private fun loadCustomWords(): List<RussianWord> {
        val array = try { JSONArray(prefs.getString("custom_words", "[]")) } catch (_: Exception) { JSONArray() }
        val result = ArrayList<RussianWord>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val ru = o.optString("russian", "").trim()
            val meaning = o.optString("meaning", "").trim()
            if (id.isBlank() || ru.isBlank() || meaning.isBlank()) continue
            result.add(RussianWord(
                id, ru, o.optString("pronunciation", "اقرأها ببطء"), meaning,
                o.optString("exampleRussian", ru), o.optString("exampleArabic", meaning),
                o.optString("category", "كلماتي"), true
            ))
        }
        return result
    }

    private fun persistCustomWord(word: RussianWord) {
        val array = try { JSONArray(prefs.getString("custom_words", "[]")) } catch (_: Exception) { JSONArray() }
        val obj = JSONObject()
            .put("id", word.id)
            .put("russian", word.russian)
            .put("pronunciation", word.pronunciation)
            .put("meaning", word.meaning)
            .put("exampleRussian", word.exampleRussian)
            .put("exampleArabic", word.exampleArabic)
            .put("category", word.category)
        array.put(obj)
        prefs.edit().putString("custom_words", array.toString()).apply()
    }

    private fun showAddWordDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun input(hintText: String): EditText = EditText(this).apply {
            hint = hintText
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val russian = input("الكلمة بالروسية — например: книга")
        val pronunciation = input("النطق التقريبي بالعربية (اختياري)")
        val meaning = input("المعنى بالعربية")
        form.addView(russian, matchWrap())
        form.addView(pronunciation, matchWrap())
        form.addView(meaning, matchWrap())
        AlertDialog.Builder(this)
            .setTitle("أضف كلمة تحفظها بطريقتك")
            .setMessage("اكتب كلمة واحدة ومعناها. ستبقى على هذا الهاتف.")
            .setView(form)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ محليًا") { _, _ ->
                val ru = russian.text.toString().trim()
                val ar = meaning.text.toString().trim()
                if (ru.isBlank() || ar.isBlank()) {
                    toast("أدخل الكلمة الروسية ومعناها.")
                    return@setPositiveButton
                }
                val id = "custom_" + System.currentTimeMillis().toString()
                val pron = pronunciation.text.toString().trim().ifBlank { "اقرأها ببطء" }
                val word = RussianWord(id, ru, pron, ar, ru, ar, "كلماتي", true)
                persistCustomWord(word)
                activeTab = StudyTab.LEARN
                revealedWordId = null
                render()
                toast("حُفظت الكلمة في قاموسك المحلي.")
            }
            .show()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(white, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun textView(label: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 15f
            setTextColor(if (color == tealColor) Color.WHITE else inkColor)
            background = rounded(color, 14)
            minHeight = dp(46)
            minimumHeight = dp(46)
            stateListAnimator = null
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun gap(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weighted() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(3); marginEnd = dp(3)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        super.onDestroy()
    }
}
