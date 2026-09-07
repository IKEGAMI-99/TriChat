package com.ikegami99.trichat

object ModelProtocol {
    const val MSG_LOAD = 1
    const val MSG_GENERATE = 2
    const val MSG_TOKEN = 3
    const val MSG_DONE = 4
    const val MSG_LOADED = 5
    const val MSG_ERROR = 6
    const val MSG_UNLOAD = 7

    const val KEY_PATH = "path"
    const val KEY_SYSTEM = "system"
    const val KEY_PROMPT = "prompt"
    const val KEY_TOKEN = "token"
    const val KEY_ERROR = "error"
    const val KEY_MAX_TOKENS = "max_tokens"
}
