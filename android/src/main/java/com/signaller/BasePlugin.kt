package com.signaller

import org.json.JSONObject

open class BasePlugin(val signaller: RTCSignaller, name: String, transactionId: String) {
  var transactionId: String = transactionId
    private set

  var name: String = name
    private set

  open var pluginHandlerId = ""

  open fun destroy() {
    signaller.send(JSONObject().apply {
      put("janus", "detach")
      put("handle_id", pluginHandlerId)
      put("session_id", signaller.sessionId)
      put("transaction", transactionId)
    })
  }
}
