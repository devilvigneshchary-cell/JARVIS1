package com.vignesh.jarvis

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.app.SearchManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object Actions {
    fun needsConfirm(a: JSONObject) = a.optString("type") in setOf("call", "sms", "whatsapp", "reply_last")

    fun describe(a: JSONObject): String = when (a.optString("type")) {
        "call" -> "Call ${a.optString("name")}"
        "sms" -> "Text ${a.optString("name")}: ${a.optString("text")}"
        "whatsapp" -> "WhatsApp ${a.optString("name")}: ${a.optString("text")}"
        "reply_last" -> "Reply to ${Inbox.lastSender()}: ${a.optString("text")}"
        else -> ""
    }

    private fun go(c: Context, i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { c.startActivity(i); true } catch (e: Exception) { false }
    }

    fun findContact(c: Context, name: String): Pair<String, String>? {
        if (name.isBlank() || c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        var best: Pair<String, String>? = null
        val cur = c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null
        )
        cur?.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                if (best == null || n.length < best!!.first.length) best = Pair(n, num)
            }
        }
        return best
    }

    fun run(c: Context, a: JSONObject): String {
        try {
            when (a.optString("type")) {
                "call" -> {
                    val ct = findContact(c, a.optString("name")) ?: return "I could not find ${a.optString("name")} in contacts, boss."
                    Store.setLastContact(c, ct.first)
                    return if (go(c, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", ct.second, null)))) "" else "Call failed, boss."
                }
                "sms" -> {
                    val ct = findContact(c, a.optString("name")) ?: return "I could not find ${a.optString("name")} in contacts, boss."
                    val sm = c.getSystemService(SmsManager::class.java)
                    sm.sendMultipartTextMessage(ct.second, null, sm.divideMessage(a.optString("text")), null, null)
                    Store.setLastContact(c, ct.first)
                    return "Sent to ${ct.first}."
                }
                "whatsapp" -> {
                    val ct = findContact(c, a.optString("name")) ?: return "I could not find ${a.optString("name")} in contacts, boss."
                    var num = ct.second.filter { it.isDigit() }
                    if (num.length == 10) num = "91$num"
                    Store.setLastContact(c, ct.first)
                    go(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(a.optString("text")))))
                    return "WhatsApp is open, boss. Tap send."
                }
                "read_messages" -> return Inbox.readAloud()
                "reply_last" -> return Inbox.reply(c, a.optString("text"))
                "open_app" -> {
                    val pm = c.packageManager
                    val q = a.optString("app").lowercase()
                    val l = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val app = pm.queryIntentActivities(l, 0).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
                        ?: return "I cannot find ${a.optString("app")}, boss."
                    val li = pm.getLaunchIntentForPackage(app.activityInfo.packageName) ?: return "Cannot open it, boss."
                    go(c, li)
                }
                "navigate" -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a.optString("place"))))
                    i.setPackage("com.google.android.apps.maps")
                    if (!go(c, i)) return "Google Maps is not available, boss."
                }
                "alarm" -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (!go(c, i)) return "Could not set the alarm, boss."
                }
                "timer" -> {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (!go(c, i)) return "Could not set the timer, boss."
                }
                "calendar" -> {
                    val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(a.optString("start"))?.time ?: 0L
                    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, a.optString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t + 3600000)
                    go(c, i)
                    return "Calendar is open, boss. Tap save."
                }
                "flashlight" -> {
                    val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], a.optBoolean("on", true))
                }
                "volume" -> {
                    val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max * a.optInt("level", 50) / 100, 0)
                }
                "brightness" -> {
                    if (!Settings.System.canWrite(c)) {
                        go(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
                        return "Boss, allow brightness control first."
                    }
                    Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, a.optInt("level", 50) * 255 / 100)
                }
                "wifi" -> go(c, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                "music" -> {
                    val q = a.optString("query")
                    val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        .putExtra(SearchManager.QUERY, q)
                    if (!go(c, i)) go(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                }
            }
        } catch (e: Exception) {
            return "That did not work, boss."
        }
        return ""
    }
}

object Brain {
    private fun err(msg: String) =
        JSONObject().put("speech", msg).put("lang", "en").put("actions", JSONArray())

    fun ask(c: Context, userText: String): JSONObject {
        val key = Store.apiKey(c)
        if (key.isEmpty()) return err("Boss, add your Gemini API key in the app first.")
        val now = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val sys = """
You are JARVIS, the voice assistant on the phone of Vignesh, a Rapido bike taxi captain in Hyderabad.
He is often riding, so keep speech very short (max 15 words), spoken style, no emojis, no markdown. Call him boss.
He speaks English, Telugu, Hindi or a mix. Reply in the language he used.
Current time: $now. Last contact used: ${Store.lastContact(c)}.
Reply ONLY with JSON: {"speech":"...","lang":"en|te|hi","actions":[...]}
Action types (use only when he asks, several allowed, run in order):
{"type":"call","name":"Ravi"}
{"type":"sms","name":"Ravi","text":"I will be late"}
{"type":"whatsapp","name":"Ravi","text":"..."}
{"type":"read_messages"}
{"type":"reply_last","text":"..."} (reply to the latest received message)
{"type":"open_app","app":"youtube"}
{"type":"navigate","place":"Bowenpally"}
{"type":"alarm","hour":6,"minute":0,"label":"Wake up"} (24 hour; also use for reminders at a time)
{"type":"timer","seconds":300,"label":"Tea"}
{"type":"calendar","title":"Meeting","start":"2026-11-23 17:00"}
{"type":"flashlight","on":true}
{"type":"volume","level":50} (0-100)
{"type":"brightness","level":70} (0-100)
{"type":"wifi"}
{"type":"music","query":"song name"}
Rules: write contact names in English letters as heard. For him, her, call back use the last contact.
Texts must contain what he said, cleaned up, never invented. If unclear, ask a short question in speech with empty actions.
For normal questions just answer in speech with empty actions.
""".trimIndent()

        val contents = JSONArray()
        val h = Store.history(c)
        var started = false
        for (i in 0 until h.length()) {
            val t = h.getJSONObject(i)
            val role = t.getString("role")
            if (!started && role != "user") continue
            started = true
            contents.put(JSONObject().put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", t.getString("text")))))
        }
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${Store.model(c)}:generateContent?key=$key")
            val con = url.openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.setRequestProperty("Content-Type", "application/json")
            con.doOutput = true
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream).bufferedReader().readText()
            if (code !in 200..299) return err("Boss, AI error $code. Check key or internet.")
            val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            JSONObject(out.trim().removePrefix("```json").removeSuffix("```").trim())
        } catch (e: Exception) {
            err("Boss, I cannot reach the AI right now.")
        }
    }
}

object Inbox {
    class Msg(val sender: String, val text: String, val reply: Notification.Action?, var read: Boolean = false)

    private val msgs = ArrayList<Msg>()
    var onNew: ((String) -> Unit)? = null

    fun add(m: Msg) {
        msgs.add(m)
        if (msgs.size > 30) msgs.removeAt(0)
    }

    fun lastSender() = msgs.lastOrNull()?.sender ?: ""

    fun readAloud(): String {
        val unread = msgs.filter { !it.read }.takeLast(5)
        if (unread.isEmpty()) return "No new messages, boss."
        unread.forEach { it.read = true }
        return unread.joinToString(". ") { "${it.sender} says: ${it.text}" }
    }

    fun reply(c: Context, text: String): String {
        val m = msgs.lastOrNull { it.reply != null } ?: return "No message to reply to, boss."
        val act = m.reply!!
        val ri = act.remoteInputs ?: return "Cannot reply to that one, boss."
        val intent = Intent()
        val b = Bundle()
        for (r in ri) b.putCharSequence(r.resultKey, text)
        RemoteInput.addResultsToIntent(ri, intent, b)
        act.actionIntent.send(c, 0, intent)
        Store.setLastContact(c, m.sender)
        return "Replied to ${m.sender}."
    }
}

class JarvisService : Service(), TextToSpeech.OnInitListener {
    companion object {
        var running = false
        var log: ((String) -> Unit)? = null
        private val YES = setOf("yes", "yeah", "yep", "ok", "okay", "send", "sure", "avunu", "haan", "ha", "ji",
            "అవును", "హా", "సరే", "हाँ", "हां", "ठीक", "हा")
        private val NO = setOf("no", "nope", "cancel", "stop", "vaddu", "nahi", "వద్దు", "కాదు", "నో", "नहीं", "मत", "नो")
        private val WAKE = listOf("jarvis", "jarvish", "జార్విస్", "జార్విస్", "जार्विस", "जारविस")
    }

    private lateinit var tts: TextToSpeech
    private var rec: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pair<String, String>>()
    private var speaking = false
    private var busy = false
    private var awakeUntil = 0L
    private var pending: List<JSONObject>? = null
    private var pendingSpeech = ""
    private var pendingLang = "en"

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        tts = TextToSpeech(this, this)
        Inbox.onNew = { say(it, "en") }
        running = true
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onInit(status: Int) {
        tts.setPitch(0.8f)
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { h.post { speaking = false; speakNext() } }
            @Deprecated("deprecated") override fun onError(id: String?) { h.post { speaking = false; speakNext() } }
        })
        say("Jarvis online, boss.", "en")
    }

    private fun say(text: String, lang: String) {
        h.post {
            log("Jarvis: $text")
            queue.addLast(Pair(text, lang))
            if (!speaking) speakNext()
        }
    }

    private fun speakNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) { listen(); return }
        try { rec?.cancel() } catch (e: Exception) {}
        speaking = true
        val loc = when (next.second) { "te" -> Locale("te", "IN"); "hi" -> Locale("hi", "IN"); else -> Locale("en", "IN") }
        if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.language = loc else tts.language = Locale("en", "IN")
        tts.speak(next.first, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun listen() {
        if (speaking || busy) return
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this)
            rec!!.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.lang(this))
        try { rec!!.startListening(i) } catch (e: Exception) {}
    }

    private val listener = object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) listen() else handle(t)
        }
        override fun onError(e: Int) {
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { rec?.destroy(); rec = null }
            h.postDelayed({ if (!speaking && !busy) listen() }, 400)
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun handle(text: String) {
        val low = text.lowercase()
        val hit = WAKE.firstOrNull { low.contains(it) }
        val now = System.currentTimeMillis()
        var cmd = text
        if (hit != null) {
            cmd = text.substring(low.indexOf(hit) + hit.length).trim(' ', ',', '.')
        } else if (now > awakeUntil && pending == null) {
            listen(); return
        }
        awakeUntil = now + 15000
        if (cmd.isEmpty()) { say("Yes boss.", "en"); return }
        process(cmd)
    }

    private fun process(cmd: String) {
        log("You: $cmd")
        val p = pending
        if (p != null) {
            val toks = cmd.lowercase().split(Regex("[\\s,.!?]+"))
            if (toks.any { it in NO }) { pending = null; say("Cancelled, boss.", "en"); return }
            if (toks.any { it in YES }) {
                pending = null
                if (pendingSpeech.isNotEmpty()) say(pendingSpeech, pendingLang)
                execute(p, pendingLang)
                return
            }
            pending = null
        }
        busy = true
        Thread {
            val r = Brain.ask(this, cmd)
            h.post { busy = false; onBrain(cmd, r) }
        }.start()
    }

    private fun onBrain(cmd: String, r: JSONObject) {
        val speech = r.optString("speech")
        val lang = r.optString("lang", "en")
        val arr = r.optJSONArray("actions") ?: JSONArray()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        Store.addTurn(this, "user", cmd)
        Store.addTurn(this, "model", speech + if (list.isNotEmpty()) " [actions: $arr]" else "")
        awakeUntil = System.currentTimeMillis() + 15000
        if (list.any { Actions.needsConfirm(it) }) {
            pending = list
            pendingSpeech = speech
            pendingLang = lang
            val what = list.filter { Actions.needsConfirm(it) }.joinToString(". ") { Actions.describe(it) }
            say("$what. Yes or no?", lang)
        } else {
            if (speech.isNotEmpty()) say(speech, lang)
            execute(list, lang)
            if (speech.isEmpty() && list.isEmpty()) listen()
        }
    }

    private fun execute(list: List<JSONObject>, lang: String) {
        for (a in list) {
            val out = Actions.run(this, a)
            if (out.isNotEmpty()) say(out, lang)
        }
        if (queue.isEmpty() && !speaking) listen()
    }

    override fun onDestroy() {
        running = false
        Inbox.onNew = null
        try { rec?.destroy() } catch (e: Exception) {}
        tts.shutdown()
        super.onDestroy()
    }

    private fun log(s: String) { log?.invoke(s) }
}

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private val langs = listOf("en-IN", "te-IN", "hi-IN")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })

        fun label(t: String) = TextView(this).apply { text = t; textSize = 18f }.also { col.addView(it) }
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply { text = t; setOnClickListener { f(this) } }.also { col.addView(it) }

        label("JARVIS")
        val key = EditText(this).apply { hint = "Gemini API key"; setText(Store.apiKey(this@MainActivity)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(key)
        val model = EditText(this).apply { hint = "Model"; setText(Store.model(this@MainActivity)) }
        col.addView(model)
        btn("Save key") { Store.save(this, key.text.toString(), model.text.toString()); toast("Saved") }
        btn("1. Allow permissions") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS, "android.permission.POST_NOTIFICATIONS"), 1)
        }
        btn("2. Notification access (read messages)") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btn("3. Display over other apps (needed to open apps)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("4. Brightness control") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        btn("5. Battery: set Jarvis to unrestricted") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        btn("Listening language: " + Store.lang(this)) {
            val next = langs[(langs.indexOf(Store.lang(this)) + 1) % langs.size]
            Store.setLang(this, next); it.text = "Listening language: $next"
        }
        btn("START JARVIS") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) toast("Allow permissions first")
            else startForegroundService(Intent(this, JarvisService::class.java))
        }
        btn("STOP JARVIS") { stopService(Intent(this, JarvisService::class.java)) }
        logView = TextView(this).apply { textSize = 15f }
        col.addView(logView)
        JarvisService.log = { s -> runOnUiThread { logView.text = (logView.text.toString() + "\n" + s).takeLast(2000) } }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

class NotifListener : NotificationListenerService() {
    private val chatApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging",
        "com.android.mms", "org.telegram.messenger", "com.samsung.android.messaging"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (n.category == Notification.CATEGORY_MISSED_CALL) {
            Inbox.onNew?.invoke("Missed call from $title")
            return
        }
        if (sbn.packageName !in chatApps) return
        if (text.isEmpty() || title == "WhatsApp" || Regex("\\d+ new messages").containsMatchIn(text)) return

        val reply = n.actions?.firstOrNull { it.remoteInputs != null && it.remoteInputs.isNotEmpty() }
        Inbox.add(Inbox.Msg(title, text, reply))
        Inbox.onNew?.invoke("Message from $title: ${text.take(120)}")
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun apiKey(c: Context) = p(c).getString("key", "") ?: ""
    fun model(c: Context) = p(c).getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
    fun lang(c: Context) = p(c).getString("lang", "en-IN") ?: "en-IN"
    fun save(c: Context, key: String, model: String) {
        p(c).edit().putString("key", key.trim())
            .putString("model", model.trim().ifEmpty { "gemini-2.5-flash" }).apply()
    }
    fun setLang(c: Context, l: String) { p(c).edit().putString("lang", l).apply() }
    fun lastContact(c: Context) = p(c).getString("lastContact", "") ?: ""
    fun setLastContact(c: Context, v: String) { p(c).edit().putString("lastContact", v).apply() }
    fun history(c: Context): JSONArray =
        try { JSONArray(p(c).getString("history", "[]")) } catch (e: Exception) { JSONArray() }
    fun addTurn(c: Context, role: String, text: String) {
        val h = history(c)
        h.put(JSONObject().put("role", role).put("text", text))
        val out = JSONArray()
        for (i in maxOf(0, h.length() - 16) until h.length()) out.put(h.get(i))
        p(c).edit().putString("history", out.toString()).apply()
    }
}
