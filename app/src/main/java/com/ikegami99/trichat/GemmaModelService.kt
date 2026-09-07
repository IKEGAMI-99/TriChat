package com.ikegami99.trichat

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Gemma 4 E2B runtime backed by Google's LiteRT-LM.
 *
 * We intentionally do not use llama.cpp for Gemma here. On Android the official
 * Gemma 4 E2B GGUF repeatedly killed the isolated service during native model
 * loading, while LiteRT-LM is the Android-first runtime and model format Google
 * ships for Gemma 4.
 */
class GemmaModelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                ModelProtocol.MSG_LOAD -> handleLoad(msg)
                ModelProtocol.MSG_GENERATE -> handleGenerate(msg)
                ModelProtocol.MSG_UNLOAD -> scope.launch { closeRuntime() }
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun validateLiteRtLm(path: String) {
        val file = File(path)
        require(file.exists()) { "Gemmaモデルが見つかりません" }
        require(file.isFile && file.canRead()) { "Gemmaモデルを読み取れません" }
        require(file.length() > 500_000_000L) { "Gemmaモデルが小さすぎます" }
        val magic = ByteArray(8)
        file.inputStream().use { input ->
            require(input.read(magic) == 8) { "LiteRT-LMヘッダーを読み取れません" }
        }
        val expected = "LITERTLM".toByteArray(Charsets.US_ASCII)
        require(magic.contentEquals(expected)) {
            "Gemmaは .litertlm 形式を選んでください。旧GGUFはv0.1.4以降では使用しません"
        }
    }

    private fun handleLoad(msg: Message) {
        val reply = msg.replyTo ?: return
        val path = msg.data.getString(ModelProtocol.KEY_PATH).orEmpty()
        val system = msg.data.getString(ModelProtocol.KEY_SYSTEM).orEmpty()
        scope.launch {
            try {
                validateLiteRtLm(path)
                closeRuntime()

                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                val nextEngine = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = Backend.CPU(threadCount = 4),
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = 4096,
                        cacheDir = File(cacheDir, "gemma4-litertlm").apply { mkdirs() }.absolutePath,
                    )
                )
                nextEngine.initialize()

                val nextConversation = nextEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7),
                        channels = emptyList(),
                        maxOutputToken = 256,
                        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    )
                )

                engine = nextEngine
                conversation = nextConversation
                send(reply, ModelProtocol.MSG_LOADED)
            } catch (t: Throwable) {
                closeRuntime()
                send(reply, ModelProtocol.MSG_ERROR, Bundle().apply {
                    putString(ModelProtocol.KEY_ERROR, "LiteRT-LM Gemma load failed: ${t.stackTraceToString()}")
                })
            }
        }
    }

    private fun handleGenerate(msg: Message) {
        val reply = msg.replyTo ?: return
        val prompt = msg.data.getString(ModelProtocol.KEY_PROMPT).orEmpty()
        val maxTokens = msg.data.getInt(ModelProtocol.KEY_MAX_TOKENS, 256)
        scope.launch {
            try {
                val conv = conversation ?: error("Gemma LiteRT-LM model is not loaded")
                conv.sendMessageAsync(
                    text = prompt,
                    maxOutputToken = maxTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                ).collect { chunk ->
                    val text = chunk.toString()
                    if (text.isNotEmpty()) {
                        send(reply, ModelProtocol.MSG_TOKEN, Bundle().apply {
                            putString(ModelProtocol.KEY_TOKEN, text)
                        })
                    }
                }
                send(reply, ModelProtocol.MSG_DONE)
            } catch (t: Throwable) {
                send(reply, ModelProtocol.MSG_ERROR, Bundle().apply {
                    putString(ModelProtocol.KEY_ERROR, "LiteRT-LM Gemma generate failed: ${t.stackTraceToString()}")
                })
            }
        }
    }

    private fun closeRuntime() {
        conversation?.let { runCatching { it.close() } }
        conversation = null
        engine?.let { runCatching { it.close() } }
        engine = null
    }

    private fun send(target: Messenger, what: Int, data: Bundle = Bundle()) {
        runCatching { target.send(Message.obtain(null, what).apply { this.data = data }) }
    }

    override fun onDestroy() {
        closeRuntime()
        scope.cancel()
        super.onDestroy()
    }
}
