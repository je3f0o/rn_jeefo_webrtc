package com.jeefowebrtclibrary

import org.webrtc.*
import kotlin.properties.Delegates

class RTCPublisher(id: String) {
  @Suppress("MemberVisibilityCanBePrivate")
  val id: String
  var name       = "Anonymous"
//  var resolution = "0x0"
  var mids: MutableList<Int> = mutableListOf()
  lateinit var mediaStream: MediaStream

  init {
    this.id = id
  }
}

class RTCPeer(name: String) {
  var name: String
  var peer: PeerConnection by Delegates.notNull()
  val streams: MutableMap<String, RTCPublisher> = mutableMapOf()

  init {
    this.name = name
  }
}
