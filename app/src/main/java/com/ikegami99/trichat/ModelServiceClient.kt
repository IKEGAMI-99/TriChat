package com.ikegami99.trichat

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
    private val serviceClass: KClass<out BaseModelService>,
    private val label: String,
    private val logs: LogStore
) {
    var isBound = false; private set
    var isLoaded = false; private set
    private var remote: Messenger? = null
    private var loadCallback: ((Boolean, String?) -> Unit)? = null
    private var tokenCallback: ((String) -> Unit)? = null
    private var doneCallback: ((String?) -> Unit)? = null

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
            logs.i(label, "service connected")
            onConnected?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            isBound = false
            isLoaded = false
            logs.i(label, "service disconnected")
        }
    }

    private var onConnected: (() -> Unit)? = null

    fun bind(onConnected: () -> Unit) {
        this.onConnected = onConnected
        context.bindService(Intent(context, serviceClass.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun load(path: String, systemPrompt: String, callback: (Boolean, String?) -> Unit) {
        val r = remote ?: return callback(false, "service not connected")
        loadCallback = callback
        val data = Bundle().apply {
            putString(ModelProtocol.KEY_PATH, path)
            putString(ModelProtocol.KEY_SYSTEM, systemPrompt)
        }
        r.send(Message.obtain(null, ModelProtocol.MSG_LOAD).apply { this.data = data; replyTo = incoming })
    }

    fun generate(prompt: String, maxTokens: Int = 256, onToken: (String) -> Unit, onDone: (String?) -> Unit) {
        val r = remote ?: return onDone("service not connected")
        tokenCallback = onToken
        doneCallback = onDone
        val data = Bundle().apply {
            putString(ModelProtocol.KEY_PROMPT, prompt)
            putInt(ModelProtocol.KEY_MAX_TOKENS, maxTokens)
        }
        r.send(Message.obtain(null, ModelProtocol.MSG_GENERATE).apply { this.data = data; replyTo = incoming })
    }

    fun unbind() {
        if (isBound) runCatching { context.unbindService(connection) }
        isBound = false
    }
}
