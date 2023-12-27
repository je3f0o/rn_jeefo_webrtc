package com.jeefowebrtclibrary

import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import kotlin.properties.Delegates

class RTCPeerResolition {
  var width  = 0L
  var height = 0L

  fun toJSON() = JSONObject().apply {
    put("width" , width)
    put("height", height)
  }
}

class RTCPublisher(val id: Long) {
  @Suppress("MemberVisibilityCanBePrivate")
  var name       = "Anonymous"
  var resolution = RTCPeerResolition()
  var mids: MutableList<Int> = mutableListOf()
  var mediaStream: MediaStream? = null

  fun toJSON(peerName: String) = JSONObject().apply {
    put("feed_id", "$peerName:$id")
    put("display_name", name)
    put("resolution", resolution.toJSON())
  }
}

class RTCPeer(name: String) {
  private var name: String
  var peer: PeerConnection by Delegates.notNull()
  val streams: MutableMap<Long, RTCPublisher> = mutableMapOf()

  init {
    this.name = name
  }

  fun toJSON(): JSONArray {
    val result = JSONArray()
    streams.values.forEach { p ->
      result.put(p.toJSON(name))
    }
    return result
  }
}
