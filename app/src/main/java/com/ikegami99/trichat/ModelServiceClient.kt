package com.ikegami99.trichat

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.reflect.KClass

class ModelServiceClient(
    private val context: Context,
    private val serviceClass: KClass<out Service>,
    private val label: String,
    private val logs: LogStore,
    private val important: Boolean = false,
    private val autoRestoreAfterCrash: Boolean = false,
) {
    private data class PendingLoad(
        val path: String,
        val systemPrompt: String,
        val callback: (Boolean, String?) -> Unit,
    )

    private data class LoadSpec(
        val path: String,
        val systemPrompt: String,
    )

    var isBound = false; private set
    var isLoaded = false; private set
    private var remote: Messenger? = null
    private var loadCallback: ((Boolean, String?) -> Unit)? = null
    private var tokenCallback: ((String) -> Unit)? = null
    private var doneCallback: ((String?) -> Unit)? = null
    private var pendingLoad: PendingLoad? = null
    private var activeLoadSpec: LoadSpec? = null
    private var lastSuccessfulLoad: LoadSpec? = null
    private var bindingRequested = false
    private var reconnectScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ModelProtocol.MSG_LOADED -> {
                isLoaded = true
                activeLoadSpec?.let { lastSuccessfulLoad = it }
                activeLoadSpec = null
                logs.i(label, "model loaded")
                loadCallback?.invoke(true, null)
                loadCallback = null
            }
            ModelProtocol.MSG_TOKEN -> tokenCallback?.invoke(msg.data.getString(ModelProtocol.KEY_TOKEN).orEmpty())
            ModelProtocol.MSG_DONE -> finishGeneration(null)
            ModelProtocol.MSG_ERROR -> {
                val err = msg.data.getString(ModelProtocol.KEY_ERROR) ?: "unknown error"
                logs.i(label, "ERROR $err")
                if (loadCallback != null) {
                    isLoaded = false
                    activeLoadSpec = null
                    loadCallback?.invoke(false, err)
                    loadCallback = null
                } else {
                    finishGeneration(err)
                }
            }
        }
        true
    })

    private fun queueRestoreIfNeeded(wasLoaded: Boolean) {
        if (!autoRestoreAfterCrash || !wasLoaded || pendingLoad != null) return
        val spec = lastSuccessfulLoad ?: return
        pendingLoad = PendingLoad(spec.path, spec.systemPrompt) { ok, err ->
            if (ok) logs.i(label, "model restored after service restart")
            else logs.i(label, "automatic model restore failed: ${err ?: "unknown error"}")
        }
        logs.i(label, "queued automatic model restore after service restart")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            isBound = true
            bindingRequested = true
            reconnectScheduled = false
            logs.i(label, "service connected")

            val queued = pendingLoad
            if (queued != null) {
                pendingLoad = null
                logs.i(label, "sending queued model load after reconnect")
                sendLoad(queued.path, queued.systemPrompt, queued.callback)
            } else {
                onConnected?.invoke()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val wasLoaded = isLoaded
            remote = null
            isBound = false
            isLoaded = false
            logs.i(label, "service disconnected")
            queueRestoreIfNeeded(wasLoaded)
            failInFlight("$label service disconnected during request")
            scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName?) {
            val wasLoaded = isLoaded
            remote = null
            isBound = false
            isLoaded = false
            bindingRequested = false
            logs.i(label, "service binding died")
            queueRestoreIfNeeded(wasLoaded)
            failInFlight("$label service binding died during request")
            scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName?) {
            val wasLoaded = isLoaded
            remote = null
            isBound = false
            isLoaded = false
            bindingRequested = false
            logs.i(label, "service returned null binding")
            queueRestoreIfNeeded(wasLoaded)
            failInFlight("$label service returned null binding")
            scheduleReconnect()
        }
    }

    private var onConnected: (() -> Unit)? = null

    fun bind(onConnected: () -> Unit) {
        this.onConnected = onConnected
        if (isBound || bindingRequested) return
        var flags = Context.BIND_AUTO_CREATE
        if (important) flags = flags or Context.BIND_IMPORTANT
        bindingRequested = context.bindService(Intent(context, serviceClass.java), connection, flags)
        if (!bindingRequested) logs.i(label, "bindService returned false")
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        mainHandler.postDelayed({
            reconnectScheduled = false
            if (isBound) return@postDelayed
            runCatching { context.unbindService(connection) }
            bindingRequested = false
            bind(onConnected ?: {})
        }, 500L)
    }

    private fun finishGeneration(error: String?) {
        val done = doneCallback
        tokenCallback = null
        doneCallback = null
        done?.invoke(error)
    }

    private fun failInFlight(reason: String) {
        val load = loadCallback
        loadCallback = null
        activeLoadSpec = null
        if (load != null) load(false, reason)
        finishGeneration(reason)
    }

    private fun sendLoad(path: String, systemPrompt: String, callback: (Boolean, String?) -> Unit) {
        val r = remote
        if (r == null) {
            pendingLoad = PendingLoad(path, systemPrompt, callback)
            logs.i(label, "service unavailable; queued model load")
            scheduleReconnect()
            return
        }
        loadCallback = callback
        activeLoadSpec = LoadSpec(path, systemPrompt)
        val data = Bundle().apply {
            putString(ModelProtocol.KEY_PATH, path)
            putString(ModelProtocol.KEY_SYSTEM, systemPrompt)
        }
        runCatching {
            r.send(Message.obtain(null, ModelProtocol.MSG_LOAD).apply { this.data = data; replyTo = incoming })
        }.onFailure {
            remote = null
            isBound = false
            isLoaded = false
            loadCallback = null
            activeLoadSpec = null
            pendingLoad = PendingLoad(path, systemPrompt, callback)
            logs.i(label, "load send failed; queued for reconnect: ${it.message}")
            scheduleReconnect()
        }
    }

    fun load(path: String, systemPrompt: String, callback: (Boolean, String?) -> Unit) {
        sendLoad(path, systemPrompt, callback)
    }

    fun generate(prompt: String, maxTokens: Int = 256, onToken: (String) -> Unit, onDone: (String?) -> Unit) {
        val r = remote ?: return onDone("service not connected")
        tokenCallback = onToken
        doneCallback = onDone
        val data = Bundle().apply {
            putString(ModelProtocol.KEY_PROMPT, prompt)
            putInt(ModelProtocol.KEY_MAX_TOKENS, maxTokens)
        }
        runCatching {
            r.send(Message.obtain(null, ModelProtocol.MSG_GENERATE).apply { this.data = data; replyTo = incoming })
        }.onFailure {
            tokenCallback = null
            doneCallback = null
            onDone("service send failed: ${it.message}")
        }
    }

    fun unbind() {
        reconnectScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        if (bindingRequested || isBound) runCatching { context.unbindService(connection) }
        bindingRequested = false
        isBound = false
        isLoaded = false
        remote = null
        pendingLoad = null
        activeLoadSpec = null
        lastSuccessfulLoad = null
        loadCallback = null
        tokenCallback = null
        doneCallback = null
    }
}
