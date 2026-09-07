package com.ikegami99.trichat

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

abstract class BaseModelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: InferenceEngine

    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                ModelProtocol.MSG_LOAD -> handleLoad(msg)
                ModelProtocol.MSG_GENERATE -> handleGenerate(msg)
                ModelProtocol.MSG_UNLOAD -> scope.launch { runCatching { engine.cleanUp() } }
            }
            true
        })
    }

    override fun onCreate() {
        super.onCreate()
        engine = AiChat.getInferenceEngine(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleLoad(msg: Message) {
        val reply = msg.replyTo ?: return
        val path = msg.data.getString(ModelProtocol.KEY_PATH).orEmpty()
        val system = msg.data.getString(ModelProtocol.KEY_SYSTEM).orEmpty()
        scope.launch {
            try {
                engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error }
                if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
                engine.loadModel(path)
                engine.setSystemPrompt(system)
                send(reply, ModelProtocol.MSG_LOADED)
            } catch (t: Throwable) {
                send(reply, ModelProtocol.MSG_ERROR, Bundle().apply { putString(ModelProtocol.KEY_ERROR, t.stackTraceToString()) })
            }
        }
    }

    private fun handleGenerate(msg: Message) {
        val reply = msg.replyTo ?: return
        val prompt = msg.data.getString(ModelProtocol.KEY_PROMPT).orEmpty()
        val maxTokens = msg.data.getInt(ModelProtocol.KEY_MAX_TOKENS, 256)
        scope.launch {
            try {
                engine.sendUserPrompt(prompt, maxTokens).collect { token ->
                    send(reply, ModelProtocol.MSG_TOKEN, Bundle().apply { putString(ModelProtocol.KEY_TOKEN, token) })
                }
                send(reply, ModelProtocol.MSG_DONE)
            } catch (t: Throwable) {
                send(reply, ModelProtocol.MSG_ERROR, Bundle().apply { putString(ModelProtocol.KEY_ERROR, t.stackTraceToString()) })
            }
        }
    }

    private fun send(target: Messenger, what: Int, data: Bundle = Bundle()) {
        runCatching { target.send(Message.obtain(null, what).apply { this.data = data }) }
    }

    override fun onDestroy() {
        runCatching { engine.destroy() }
        scope.cancel()
        super.onDestroy()
    }
}
