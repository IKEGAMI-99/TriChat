package com.ikegami99.trichat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    companion object {
        const val QWEN_URL = "https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf?download=true"
        const val GEMMA_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
        private const val QWEN_SYSTEM = "あなたはTriChatのQwenです。技術・論理・実現可能性を担当します。内部の思考過程は回答に含めず、結論と根拠だけを短く自然な日本語で答えてください。"
        private const val GEMMA_SYSTEM = "あなたはTriChatのGemmaです。批評・反論・別視点・改善案を担当します。思考過程やCoTは出力せず、結論と根拠だけを短く自然な日本語で答えてください。"
    }

    private lateinit var logs: LogStore
    private lateinit var qwen: ModelServiceClient
    private lateinit var gemma: ModelServiceClient
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var parallel: SwitchCompat
    private var loadingModels = false
    private var autoLoadAttempted = false
    private var busy = false
    private var lastGemma = ""

    private val qwenPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> importModel(uri, true) } }
    private val gemmaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> importModel(uri, false) } }
    private val logExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(logs.exportText()) } }
                .onSuccess { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "ログを書き出しました", Toast.LENGTH_SHORT).show() } }
                .onFailure { logs.i("LOG", it.stackTraceToString()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        logs = LogStore(this)
        buildUi()
        qwen = ModelServiceClient(this, QwenModelService::class, "QWEN", logs)
        gemma = ModelServiceClient(
            this,
            GemmaModelService::class,
            "GEMMA",
            logs,
            important = true,
            autoRestoreAfterCrash = true,
        )
        qwen.bind { refreshStatus(); maybeAutoLoad() }
        gemma.bind { refreshStatus(); maybeAutoLoad() }
        logs.i("APP", "started ${BuildConfig.VERSION_NAME}")
        val legacyGemma = File(File(filesDir, "models"), "gemma.gguf")
        if (legacyGemma.exists()) logs.i("GEMMA", "legacy GGUF ignored; import gemma-4-E2B-it.litertlm")
        UpdateManager(this, logs).check(silent = true)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundColor(Color.rgb(13, 17, 23))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            view.setPadding(
                dp(14) + bars.left,
                dp(14) + bars.top,
                dp(14) + bars.right,
                dp(10) + bottomInset,
            )
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) scrollBottom()
            insets
        }

        val title = TextView(this).apply {
            text = "TriChat  •  LOCAL AI MEETING"
            setTextColor(Color.WHITE); textSize = 20f
        }
        root.addView(title)
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "Qwen: 接続中 / Gemma: 接続中" }
        root.addView(status)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tools.addView(Button(this).apply { text = "モデル"; setOnClickListener { showModelDialog() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(Button(this).apply { text = "ログ"; setOnClickListener { logs.i("LOG", "export requested"); logExporter.launch("TriChat-${BuildConfig.VERSION_NAME}.log.txt") } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(Button(this).apply { text = "更新"; setOnClickListener { UpdateManager(this@MainActivity, logs).check(false) } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        parallel = SwitchCompat(this).apply { text = "並列"; setTextColor(Color.WHITE); setPadding(dp(8), 0, 0, 0) }
        tools.addView(parallel)
        root.addView(tools)

        chatScroll = ScrollView(this).apply { isFillViewport = true }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        chatScroll.addView(chat)
        root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addBubble("SYSTEM", "Qwen3.5 2B (llama.cpp) + Gemma 4 E2B (LiteRT-LM)。両方ロードすると3人会議を開始できます。", Color.rgb(35, 39, 47))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        input = EditText(this).apply {
            hint = "議題を入力…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); minLines = 1; maxLines = 5
            setBackgroundColor(Color.rgb(22, 27, 34)); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        send = Button(this).apply { text = "送信"; setOnClickListener { sendMessage() } }
        bottom.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(send)
        root.addView(bottom)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showModelDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
        box.addView(TextView(this).apply { text = "Qwen3.5 2B  GGUF Q4_K_M  約1.3GB" })
        box.addView(button("Qwenモデルを選択") { qwenPicker.launch(arrayOf("*/*")) })
        box.addView(button("Qwenモデルのダウンロードリンク") { openUrl(QWEN_URL) })
        box.addView(TextView(this).apply { text = "Gemma 4 E2B  LiteRT-LM  約2.59GB" })
        box.addView(button("Gemma .litertlm を選択") { gemmaPicker.launch(arrayOf("*/*")) })
        box.addView(button("Gemma LiteRT-LMのダウンロードリンク") { openUrl(GEMMA_URL) })
        box.addView(TextView(this).apply { text = "QwenはGGUF、Gemmaは .litertlm を使用します。Qwenの内部思考は画面にもGemmaにも渡しません。" })
        AlertDialog.Builder(this).setTitle("モデル設定").setView(box).setPositiveButton("閉じる", null).show()
    }

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun modelFile(isQwen: Boolean) = File(
        File(filesDir, "models").apply { mkdirs() },
        if (isQwen) "qwen.gguf" else "gemma-4-E2B-it.litertlm"
    )

    private fun importModel(uri: Uri, isQwen: Boolean) {
        val label = if (isQwen) "Qwen" else "Gemma"
        Toast.makeText(this, "${label}モデルを取り込み中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val out = modelFile(isQwen)
                    contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) } }
                    require(out.length() > 100_000_000L) {
                        if (isQwen) "ファイルが小さすぎます。GGUF本体を選んでください" else "ファイルが小さすぎます。.litertlm本体を選んでください"
                    }
                    out
                }
                logs.i(label, "model imported ${file.length()} bytes path=${file.name}")
                loadOne(isQwen)
            } catch (t: Throwable) {
                logs.i(label, t.stackTraceToString())
                Toast.makeText(this@MainActivity, "${label}の取り込みに失敗: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun maybeAutoLoad() {
        if (autoLoadAttempted || loadingModels || !::qwen.isInitialized || !::gemma.isInitialized || !qwen.isBound || !gemma.isBound) return
        autoLoadAttempted = true
        loadingModels = true
        val qf = modelFile(true)
        val gf = modelFile(false)

        fun loadGemmaThenFinish() {
            if (gf.exists()) {
                gemma.load(gf.absolutePath, GEMMA_SYSTEM) { _, err ->
                    loadingModels = false
                    if (err != null) addBubble("SYSTEM", "Gemma load error: ${err.lineSequence().firstOrNull()}", Color.DKGRAY)
                    refreshStatus()
                }
            } else {
                loadingModels = false
                refreshStatus()
            }
        }

        if (qf.exists()) {
            qwen.load(qf.absolutePath, QWEN_SYSTEM) { _, err ->
                if (err != null) addBubble("SYSTEM", "Qwen load error: ${err.lineSequence().firstOrNull()}", Color.DKGRAY)
                refreshStatus()
                loadGemmaThenFinish()
            }
        } else {
            loadGemmaThenFinish()
        }
        refreshStatus()
    }

    private fun loadOne(isQwen: Boolean) {
        val c = if (isQwen) qwen else gemma
        val f = modelFile(isQwen)
        val sys = if (isQwen) QWEN_SYSTEM else GEMMA_SYSTEM
        c.load(f.absolutePath, sys) { ok, err ->
            refreshStatus()
            Toast.makeText(this, if (ok) "${if (isQwen) "Qwen" else "Gemma"}をロードしました" else "ロード失敗: ${err?.lineSequence()?.firstOrNull()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        if (!::qwen.isInitialized || !::gemma.isInitialized) return
        fun s(c: ModelServiceClient) = if (!c.isBound) "OFF" else if (c.isLoaded) "READY" else "WAIT"
        status.text = "Qwen: ${s(qwen)}   /   Gemma: ${s(gemma)}   /   ${if (parallel.isChecked) "PARALLEL" else "MEETING"}"
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!qwen.isLoaded || !gemma.isLoaded) {
            Toast.makeText(this, "QwenとGemmaの両方をロードしてください", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        busy = true
        send.isEnabled = false
        addBubble("YOU", text, Color.rgb(42, 34, 66))
        logs.i("USER", text.take(500))
        if (parallel.isChecked) runParallel(text) else runMeeting(text)
    }

    private fun stripQwenThinking(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder()
        var cursor = 0
        while (cursor < raw.length) {
            val start = raw.indexOf("<think>", cursor, ignoreCase = true)
            if (start < 0) {
                val tail = raw.substring(cursor)
                val marker = "<think>"
                var partial = 0
                val maxPartial = minOf(marker.length - 1, tail.length)
                for (n in maxPartial downTo 1) {
                    if (marker.startsWith(tail.takeLast(n), ignoreCase = true)) {
                        partial = n
                        break
                    }
                }
                if (partial > 0) out.append(tail.dropLast(partial)) else out.append(tail)
                break
            }

            out.append(raw.substring(cursor, start))
            val end = raw.indexOf("</think>", start + "<think>".length, ignoreCase = true)
            if (end < 0) break
            cursor = end + "</think>".length
        }
        return out.toString().trimStart()
    }

    private fun runMeeting(user: String) {
        val qView = addBubble("QWEN", "", Color.rgb(15, 41, 66))
        val qRaw = StringBuilder()
        var qVisible = ""
        val context = if (lastGemma.isBlank()) "" else "前回のGemmaの発言:\n$lastGemma\n\n"

        qwen.generate(
            "${context}ユーザー: $user\n会議参加者として簡潔に答えてください。",
            256,
            { token ->
                qRaw.append(token)
                qVisible = stripQwenThinking(qRaw.toString())
                qView.text = "QWEN\n$qVisible"
                scrollBottom()
            }
        ) { err ->
            qVisible = stripQwenThinking(qRaw.toString())
            qView.text = "QWEN\n$qVisible"
            if (err != null) {
                logs.i("QWEN", "generation ended: $err")
                if (qVisible.isEmpty()) qView.append("\n[ERROR] $err")
                finishTurn()
                return@generate
            }

            logs.i("QWEN", qVisible.take(1000))
            val qForGemma = qVisible.ifBlank { "（Qwenの公開回答なし）" }
            val gView = addBubble("GEMMA", "", Color.rgb(25, 51, 31))
            val gBuf = StringBuilder()
            gemma.generate(
                "ユーザー: $user\nQwen: $qForGemma\nQwenの意見を踏まえ、同意・反論・改善案のどれかを含めて簡潔に答えてください。",
                256,
                { t -> gBuf.append(t); gView.text = "GEMMA\n$gBuf"; scrollBottom() }
            ) { gErr ->
                if (gErr != null) {
                    logs.i("GEMMA", "generation ended: $gErr")
                    if (gBuf.isEmpty()) gView.append("\n[ERROR] $gErr")
                }
                lastGemma = gBuf.toString()
                logs.i("GEMMA", lastGemma.take(1000))
                finishTurn()
            }
        }
    }

    private fun runParallel(user: String) {
        var done = 0
        fun mark() { done++; if (done >= 2) finishTurn() }

        val qView = addBubble("QWEN", "", Color.rgb(15, 41, 66))
        val qRaw = StringBuilder()
        var qVisible = ""
        val gView = addBubble("GEMMA", "", Color.rgb(25, 51, 31))
        val gBuf = StringBuilder()

        qwen.generate(user, 256, { token ->
            qRaw.append(token)
            qVisible = stripQwenThinking(qRaw.toString())
            qView.text = "QWEN\n$qVisible"
            scrollBottom()
        }) { err ->
            qVisible = stripQwenThinking(qRaw.toString())
            qView.text = "QWEN\n$qVisible"
            if (err != null) {
                logs.i("QWEN", "generation ended: $err")
                if (qVisible.isEmpty()) qView.append("\n[ERROR] $err")
            }
            logs.i("QWEN", qVisible.take(1000))
            mark()
        }

        gemma.generate(user, 256, { t ->
            gBuf.append(t)
            gView.text = "GEMMA\n$gBuf"
            scrollBottom()
        }) { err ->
            if (err != null) {
                logs.i("GEMMA", "generation ended: $err")
                if (gBuf.isEmpty()) gView.append("\n[ERROR] $err")
            }
            lastGemma = gBuf.toString()
            logs.i("GEMMA", lastGemma.take(1000))
            mark()
        }
    }

    private fun finishTurn() {
        busy = false
        send.isEnabled = true
        scrollBottom()
    }

    private fun addBubble(author: String, body: String, color: Int): TextView {
        val v = TextView(this).apply {
            text = "$author\n$body"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(color)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        }
        chat.addView(v, lp)
        scrollBottom()
        return v
    }

    private fun scrollBottom() = chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::qwen.isInitialized) qwen.unbind()
        if (::gemma.isInitialized) gemma.unbind()
        super.onDestroy()
    }
}
