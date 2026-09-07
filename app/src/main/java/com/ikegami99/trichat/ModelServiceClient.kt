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
    private val logs: LogStore
) {
    private data class PendingLoad(
        val path: String,
        val systemPrompt: String,
        val callback: (Boolean, String?) -> Unit,
    )

    var isBound = false; private set
    var isLoaded = false; private set
    private var remote: Messenger? = null
    private var loadCallback: ((Boolean, String?) -> Unit)? = null
    private var tokenCallback: ((String) -> Unit)? = null
    private var doneCallback: ((String?) -> Unit)? = null
    private var pendingLoad: PendingLoad? = null
    private var bindingRequested = false
    private var reconnectScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ModelProtocol.MSG_LOADED -> {
                isLoaded = true
                logs.i(label, "model loaded")
                loadCallback?.invoke(true, null)
                loadCallback = null
            }
            ModelProtocol.MSG_TOKEN -> tokenCallback?.invoke(msg.data.getString(ModelProtocol.KEY_TOKEN).orEmpty())
            ModelProtocol.MSG_DONE -> {
                doneCallback?.invoke(null)
                tokenCallback = null
                doneCallback = null
            }
            ModelProtocol.MSG_ERROR -> {
                val err = msg.data.getString(ModelProtocol.KEY_ERROR) ?: "unknown error"
                logs.i(label, "ERROR $err")
                if (loadCallback != null) {
                    isLoaded = false
                    loadCallback?.invoke(false, err)
                    loadCallback = null
                } else {
                    doneCallback?.invoke(err)
                    tokenCallback = null
                    doneCallback = null
                }
            }
        }
        true
    })

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
            remote = null
            isBound = false
            isLoaded = false
            logs.i(label, "service disconnected")
            scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            isBound = false
            isLoaded = false
            bindingRequested = false
            logs.i(label, "service binding died")
            scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            isBound = false
            isLoaded = false
            bindingRequested = false
            logs.i(label, "service returned null binding")
            scheduleReconnect()
        }
    }

    private var onConnected: (() -> Unit)? = null

    fun bind(onConnected: () -> Unit) {
        this.onConnected = onConnected
        if (isBound || bindingRequested) return
        bindingRequested = context.bindService(Intent(context, serviceClass.java), connection, Context.BIND_AUTO_CREATE)
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

    private fun sendLoad(path: String, systemPrompt: String, callback: (Boolean, String?) -> Unit) {
        val r = remote
        if (r == null) {
            pendingLoad = PendingLoad(path, systemPrompt, callback)
            logs.i(label, "service unavailable; queued model load")
            scheduleReconnect()
            return
        }
        loadCallback = callback
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
    }
}
