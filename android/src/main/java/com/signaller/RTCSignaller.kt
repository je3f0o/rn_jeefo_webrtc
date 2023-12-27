package com.signaller

import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.jeefowebrtclibrary.TAG
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

fun generateTransactionId(length: Int = 12): String {
  val charSet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  return (1..length).map { charSet.random() }.joinToString("")
}

class RTCSignaller(var reactContext: ReactApplicationContext) {
  var sessionId: Long? = null
    private set
  var transactionId = ""
    private set

  private var ws: WebSocket? = null
  private var plugins        : MutableMap<Long, BasePlugin>   = HashMap()
  private var pluginRequests : MutableMap<String, BasePlugin> = HashMap()
  private var intervalId     : Timer?                         = null

  private var url           = ""
  private var interval      = 25000L

  fun init(url: String) {
    this.url = url
    val request = Request.Builder()
      .url(url)
      .header("Sec-WebSocket-Protocol", "janus-protocol")
      .build()

    // Create WebSocket instance and connect
    val listener = object: WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        Log.d(TAG, "WebSocket connected.")
        transactionId = generateTransactionId()

        send(JSONObject().apply {
          put("janus", "create")
          put("transaction", transactionId)
        })
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = JSONObject(text)
        if (msg.getString("janus") == "ack") return
        Log.d(TAG, "WebSocket IN: $text")

        when (msg.getString("janus")) {
          "success" -> handleSuccessMessage(msg)
          "event" -> handleEventMessage(msg)
          "media", "hangup", "webrtcup" -> return
          "timeout" -> {
            val savedUrl = url
            destroy()
            init(savedUrl)
          }
          "detached" -> handleDetachedMessage(msg)
          else -> Log.w(TAG, "Unhandled message: $msg")
        }
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "WebSocket closed. Code: $code, Reason: $reason")
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        val reason = t.message ?: "Unknown reason"
        val code = response?.code ?: -1
        Log.e(TAG, "WebSocket failed. Reason: $reason, Code: $code")
      }
    }

    ws = OkHttpClient().newWebSocket(request, listener)
  }

  fun send(data: JSONObject) {
    if (data.getString("janus") != "keepalive") {
      Log.d(TAG, "WebSocket OUT: $data")
    }
    ws?.send(data.toString())
  }

  fun attach(pluginName: String) {
    val plugin = findPluginByName(pluginName)
    if (plugin != null) {
      return RTCEventEmitter.error("'$pluginName' already attached.")
    }

    var pluginTransactionId = generateTransactionId()
    // Ensure transaction id is not duplicated
    while (pluginRequests[pluginTransactionId] != null) {
      pluginTransactionId = generateTransactionId()
    }

    when (pluginName) {
      "video_room" -> {
        pluginRequests[pluginTransactionId] = VideoRoomPlugin(this, pluginTransactionId)
      }
      else -> RTCEventEmitter.error("Plugin '$pluginName' is not implemented")
    }
  }

  fun setPluginRequest(pluginTransactionId: String, plugin: BasePlugin) {
    pluginRequests[pluginTransactionId] = plugin
  }

  fun findPluginByName(pluginName: String): BasePlugin? {
    for (plugin in plugins.values) {
      if (plugin.name == pluginName) return plugin
    }
    return null
  }

  private fun handleSuccessMessage(msg: JSONObject) {
    if (!msg.has("data")) return
    val data = msg.getJSONObject("data")
    if (!data.has("id")) return

    val transaction = msg.getString("transaction")
    if (sessionId == null) {
      sessionId = data.getLong("id")
      startKeepAlive()
      RTCEventEmitter.event("connected", JSONObject())
    } else if (pluginRequests.contains(transaction)) {
      val plugin = pluginRequests[transaction] as VideoRoomPlugin
      val pluginHandlerId = data.getLong("id")
      plugins[pluginHandlerId] = plugin
      pluginRequests.remove(transaction)
      plugin.handleSuccess(pluginHandlerId)
    }
  }

  private fun handleEventMessage(msg: JSONObject) {
    if (!msg.has("plugindata")) return

    val plugin = plugins[msg.getLong("sender")] as VideoRoomPlugin
    plugin.handleMessage(
      msg.getJSONObject("plugindata").getJSONObject("data"),
      msg.getLong("sender"),
      msg.optJSONObject("jsep")
    )
  }

  private fun handleDetachedMessage(msg: JSONObject) {
    val id = msg.getLong("sender")
    if (plugins.containsKey(id)) {
      plugins.remove(id)
    }
  }

  private fun startKeepAlive() {
    intervalId = Timer()
    intervalId?.scheduleAtFixedRate(object: TimerTask() {
      override fun run() {
        send(JSONObject().apply {
          put("janus", "keepalive")
          put("session_id", sessionId)
          put("transaction", transactionId)
        })
      }
    }, interval, interval)
  }

  fun destroy() {
    for (p in plugins.values) p.destroy()
    plugins.clear()
    pluginRequests.clear()
    intervalId?.cancel()
    ws?.cancel()

    ws            = null
    url           = ""
    sessionId     = null
    intervalId    = null
    transactionId = ""
  }
}
