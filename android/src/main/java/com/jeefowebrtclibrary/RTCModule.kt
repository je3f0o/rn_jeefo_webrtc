package com.jeefowebrtclibrary

import android.util.Log
import com.facebook.react.bridge.*
import org.webrtc.*

interface SdpObserverAdapter : SdpObserver {
  override fun onCreateSuccess(sessionDescription: SessionDescription) {}
  override fun onCreateFailure(error: String) {}
  override fun onSetSuccess() {}
  override fun onSetFailure(error: String) {}
}

@Suppress("unused")
class RTCModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  companion object {
    const val MODULE_NAME = "RCTRTCModule"
    private var peers: MutableMap<String, RTCPeer> = mutableMapOf()

    fun getPeer(name: String): RTCPeer? = peers[name]
  }

  override fun getName() = MODULE_NAME

  @ReactMethod
  fun createOffer(peerName: String, promise: Promise) {
    val rtcPeer = peers[peerName] ?: return promise.reject(Exception("Peer: '$peerName' is not exists"))
    val peer = rtcPeer.peer

    // Define another SdpObserver for setting local description
    val localSdpObserver = object: SdpObserverAdapter {
      override fun onSetSuccess() {
        // The local description has been set successfully
        val localDescription = peer.localDescription
        val jsonResult = Arguments.createMap().apply {
          putString("type", localDescription.type.canonicalForm())
          putString("sdp", localDescription.description)
        }
        Log.d(TAG, "Offer created successfully: $jsonResult")
        promise.resolve(jsonResult)
      }

      override fun onSetFailure(error: String) {
        Log.e(TAG, "Error setting local description: $error")
      }
    }

    val offerSdpObserver = object: SdpObserverAdapter {
      override fun onCreateSuccess(sessionDescription: SessionDescription) {
        // Set the local description with the created offer
        peer.setLocalDescription(localSdpObserver, sessionDescription)
      }

      override fun onCreateFailure(error: String) {
        Log.e(TAG, "Error creating offer: $error")
      }
    }

    if (!RTCDeviceManager.isCameraActivated) {
      RTCDeviceManager.activateCamera(reactApplicationContext)
    }
    for (t in RTCDeviceManager.mediaStream.audioTracks) peer.addTrack(t)
    for (t in RTCDeviceManager.mediaStream.videoTracks) peer.addTrack(t)

    peer.createOffer(offerSdpObserver, RTCService.mediaConstraint)
  }

  @ReactMethod
  fun createPeer(peerName: String, promise: Promise) {
    if (peers.containsKey(peerName)) {
      promise.reject(Exception("Peer: '$peerName' is already exists"))
    } else {
      val peer = RTCPeer(peerName)
      if (RTCService.createPeerConnection(peer)) {
        peers[peerName] = peer
      }
      promise.resolve(true)
    }
  }

  @ReactMethod
  fun setRemoteSDP(peerName: String, sdp: ReadableMap, promise: Promise) {
    val rtcPeer = peers[peerName] ?: return promise.reject(Exception("Peer: '$peerName' is not exists"))

    val type        = sdp.getString("type")
    val description = sdp.getString("sdp")

    if (type.isNullOrEmpty() || description.isNullOrEmpty()) {
      return promise.reject(Exception("Invalid SDP format"))
    }

    val remoteSDP = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), description)
    val sdpObserver = object : SdpObserverAdapter {
      override fun onSetSuccess() {
        promise.resolve("Remote SDP set successfully")
      }

      override fun onSetFailure(error: String) {
        promise.reject(Exception("Error setting remote SDP: $error"))
      }
    }

    rtcPeer.peer.setRemoteDescription(sdpObserver, remoteSDP)
  }

  @ReactMethod
  fun answer(peerName: String, sdp: ReadableMap, promise: Promise) {
    val peerWrapper = peers[peerName] ?: return promise.reject(Exception("Peer: '$peerName' is not exists"))
    val peer = peerWrapper.peer

    val type        = sdp.getString("type")
    val description = sdp.getString("sdp")

    if (type.isNullOrEmpty() || description.isNullOrEmpty()) {
      return promise.reject(Exception("Invalid SDP format"))
    }

    val remoteSDPObserver = object: SdpObserverAdapter {
      override fun onSetSuccess() {
        peer.createAnswer(createAnswerSDPObserver(peer, promise), RTCService.mediaConstraint)
      }

      override fun onSetFailure(error: String) {
        promise.reject(Exception("Error setting remote description: $error"))
      }
    }
    val remoteSDP = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), description)
    peer.setRemoteDescription(remoteSDPObserver, remoteSDP)
  }

  @ReactMethod
  fun getFeeds(peerName: String, promise: Promise) {
    val peer = peers[peerName] ?: return
    val feedsArray: WritableArray = Arguments.createArray()
    for (feed in peer.streams.keys) {
      feedsArray.pushString(feed)
    }
    promise.resolve(feedsArray)
  }

  @ReactMethod
  fun sync(peerName: String, streams: ReadableArray, promise: Promise) {
    val peer = peers[peerName] ?: return promise.reject(Exception("Peer: '$peerName' is not exists"))

    val feedIds = mutableListOf<String>()
    for (i in 0 until streams.size()) {
      val streamObject: ReadableMap = streams.getMap(i)

      val feedId    = streamObject.getDouble("feed_id").toULong().toString()
      val publisher = peer.streams.getOrPut(feedId) { RTCPublisher(feedId) }
      if (!feedIds.contains(feedId)) feedIds.add(feedId)

      val mid = streamObject.getInt("mindex")
      if (!publisher.mids.contains(mid)) {
        publisher.mids.add(mid)
      }

      val peerConnectionFactory = RTCService.peerConnectionFactory
      publisher.name        = streamObject.getString("feed_display") ?: "feed_id:$feedId"
      publisher.mediaStream = peerConnectionFactory.createLocalMediaStream(publisher.name)
    }

    // Removing streams that doesn't exist anymore
    val iterator = peer.streams.entries.iterator()
    val publishersToRemove = mutableListOf<String>()
    while (iterator.hasNext()) {
      val (feedId, publisher) = iterator.next()
      if (!feedIds.contains(publisher.id)) {
        publishersToRemove.add(feedId)
      }
    }
    for (feedId in publishersToRemove) peer.streams.remove(feedId)

    promise.resolve(true)
  }

  private fun createAnswerSDPObserver(peer: PeerConnection, promise: Promise): SdpObserverAdapter {
    val localSDPObserver = object: SdpObserverAdapter {
      override fun onSetFailure(error: String) {
        promise.reject(Exception("Error setting local description: $error"))
      }

      override fun onSetSuccess() {
        val localDescription = peer.localDescription
        val jsonResult = Arguments.createMap().apply {
          putString("type", localDescription.type.canonicalForm())
          putString("sdp", localDescription.description)
        }
        Log.d(TAG, "Answer created successfully: $jsonResult")
        promise.resolve(jsonResult)
      }
    }

    return object: SdpObserverAdapter {
      override fun onCreateSuccess(sessionDescription: SessionDescription) {
        peer.setLocalDescription(localSDPObserver, sessionDescription)
      }

      override fun onCreateFailure(error: String) {
        promise.reject(Exception("Error creating answer: $error"))
      }
    }
  }
}
