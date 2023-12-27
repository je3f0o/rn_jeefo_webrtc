package com.signaller

import android.util.Log
import com.facebook.react.bridge.ReadableMap
import com.jeefowebrtclibrary.RTCModule
import com.jeefowebrtclibrary.Result
import com.jeefowebrtclibrary.TAG
import org.json.JSONArray
import org.json.JSONObject

class VideoRoomPlugin(signaller: RTCSignaller, transactionId: String):
  BasePlugin(signaller, "video_room", transactionId)
{
  companion object {
    const val publisherPeerName  = "publisher"
    const val subscriberPeerName = "subscriber"
  }

  private var roomId       = 0L
  private var privateId    = 0L
  var publisherId  = 0L
    private set
  private var subscriberId = 0L
//    private set

  init {
    signaller.send(JSONObject().apply {
      put("janus", "attach")
      put("plugin", "janus.plugin.videoroom")
      put("session_id", signaller.sessionId)
      put("transaction", transactionId)
    })
  }

  fun handleSuccess(id: Long) {
    if (publisherId == 0L) {
      publisherId = id
      requestSubscriberPlugin("${transactionId}.sub")
    } else if (subscriberId == 0L) {
      subscriberId = id
      RTCEventEmitter.event("attached", JSONObject().apply {
        put("name", name)
      })
    }
  }

  fun handleMessage(msg: JSONObject, sender: Long, jsep: JSONObject?) {
    if (msg.has("error")) return RTCEventEmitter.error(msg.getString("error"))

    if (sender == publisherId) {
      onPublisherMessage(msg, jsep)
    } else {
      onSubscriberMessage(msg, jsep)
    }
  }

  fun handleMethod(methodName: String, args: ReadableMap) {
    when (methodName) {
      "join" -> {
        if (args.hasKey("room") && args.hasKey("username")) {
          val roomId   = args.getInt("room").toLong()
          val username = args.getString("username")!!
          join(roomId, username)
        } else {
          RTCEventEmitter.error("Invalid arguments passed to call join room")
        }
      }
    }
  }

  override fun destroy() {

  }

  private fun join(roomId: Long, username: String) {
    this.roomId = roomId
    val data = JSONObject().apply {
      put("request", "join")
      put("room", roomId)
      put("ptype", "publisher")
      put("display", username)
    }
    send(data, publisherId, null)
  }

  private fun joinSubscriber(privateId: Long, streams: JSONArray) {
    val data = JSONObject().apply {
      put("request", "join")
      put("room", roomId)
      put("ptype", "subscriber")
      put("use_msid", false)
      put("private_id", privateId)
      put("streams", streams)
    }
    send(data, subscriberId, null)
  }

  private fun send(body: JSONObject, pluginHandlerId: Long, jsep: JSONObject?) {
    val data = JSONObject().apply {
      put("janus", "message")
      put("session_id", signaller.sessionId)
      put("transaction", transactionId)
      put("handle_id", pluginHandlerId)
      put("body", body)
    }
    if (jsep != null) data.put("jsep", jsep)
    signaller.send(data)
  }

  private fun onPublisherMessage(msg: JSONObject, jsep: JSONObject?) {
    when (val eventType = msg.getString("videoroom")) {
      "joined" -> handlePublisherJoinedEvent(msg)
      "event" -> {
        when {
          msg.optString("configured") == "ok" -> {
            if (jsep != null) {
              RTCModule.setRemoteSDP(publisherPeerName, jsep)
            }
          }
          msg.has("publishers") -> {
            val streams = prepareStreams(msg.getJSONArray("publishers"))
            if (RTCModule.hasPeer(subscriberPeerName)) {
              val body = JSONObject().apply {
                put("request", "update")
                put("subscribe", streams)
              }
              send(body, subscriberId, null)
            } else {
              joinSubscriber(privateId, streams)
            }
          }
          (!msg.has("leaving") && !msg.has("unpublished")) -> {
            Log.w(TAG, "WHAT IS THIS???? $msg")
          }
        }
      }
      else -> Log.w(TAG, "Unhandled publisher message in video_room: $eventType $msg")
    }
  }

  private fun onSubscriberMessage(msg: JSONObject, jsep: JSONObject?) {
    when (val eventType = msg.getString("videoroom")) {
      "attached" -> {
        RTCEventEmitter.event("$name.subscribed", JSONObject())
        if (jsep != null) updateSubscriber(msg, jsep)
      }
      "updated" -> {
        if (jsep != null) updateSubscriber(msg, jsep)
      }
      "event" -> {
        if (msg.optString("started") == "ok") {
          Log.w(TAG, "STARTED ------------------------------")
          RTCModule.syncSubscriberFeeds()
        } else {
          Log.w(TAG, "WHAT IS THIS EVENT ????? $msg")
        }
      }
      else -> {
        val s = "Unhandled subscriber message in video_room:"
        Log.w(TAG, "$s $eventType $msg")
      }
    }
  }

  private fun updateSubscriber(msg: JSONObject, jsep: JSONObject) {
    val inputStreams = msg.getJSONArray("streams")
    val streams = mutableListOf<JSONObject>()

    for (i in 0 until inputStreams.length()) {
      val stream = inputStreams.getJSONObject(i)
      if (stream.getBoolean("active")) {
        streams.add(stream)
      }
    }

    if (!RTCModule.hasPeer(subscriberPeerName)) {
      RTCModule.createPeer(subscriberPeerName)
    }
    RTCModule.sync(subscriberPeerName, streams)
    RTCModule.answer(subscriberPeerName, jsep) { result ->
      when (result) {
        is Result.Err -> RTCEventEmitter.error(result.message)
        is Result.Ok -> {
          val data = JSONObject().apply {
            put("request", "start")
            put("room", msg.getString("room"))
          }
          val localSdp = result.value
          send(data, subscriberId, localSdp)
        }
      }
    }
  }

  private fun requestSubscriberPlugin(pluginTransactionId: String) {
    signaller.setPluginRequest(pluginTransactionId, this)

    signaller.send(JSONObject().apply {
      put("janus", "attach")
      put("plugin", "janus.plugin.videoroom")
      put("session_id", signaller.sessionId)
      put("transaction", pluginTransactionId)
    })
  }

  private fun handlePublisherJoinedEvent(msg: JSONObject) {
    privateId = msg.getLong("private_id")
    RTCEventEmitter.event("$name.joined", JSONObject())

    val peerName = "publisher"
    when (val r = RTCModule.createPeer(peerName)) {
      is Result.Err -> RTCEventEmitter.error(r.message)
      is Result.Ok -> {
        RTCModule.createOffer(peerName, signaller.reactContext) { result ->
          when (result) {
            is Result.Err -> RTCEventEmitter.error(result.message)
            is Result.Ok -> {
              val data = JSONObject().apply {
                put("request", "configure")
                put("audio", true)
                put("video", true)
              }
              send(data, publisherId, result.value)

              val streams = prepareStreams(msg.getJSONArray("publishers"))
              if (streams.length() > 0) {
                joinSubscriber(privateId, streams)
              }
            }
          }
        }
      }
    }
  }
}

fun prepareStreams(publishersArray: JSONArray): JSONArray {
  val streams = JSONArray()

  for (i in 0 until publishersArray.length()) {
    val publisher    = publishersArray.getJSONObject(i)
    val publisherId  = publisher.getLong("id")
    val streamsArray = publisher.getJSONArray("streams")

    for (j in 0 until streamsArray.length()) {
      val stream = streamsArray.getJSONObject(j)
      val mid    = stream.getString("mid")
      streams.put(JSONObject().apply {
        put("feed", publisherId)
        put("mid", mid)
      })
    }
  }

  return streams
}
