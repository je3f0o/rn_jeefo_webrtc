package com.jeefowebrtclibrary

import android.util.Log
import com.facebook.react.bridge.*
import com.signaller.RTCEventEmitter
import com.signaller.RTCSignaller
import com.signaller.VideoRoomPlugin
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*

interface SdpObserverAdapter : SdpObserver {
  override fun onCreateSuccess(sessionDescription: SessionDescription) {}
  override fun onCreateFailure(error: String) {}
  override fun onSetSuccess() {}
  override fun onSetFailure(error: String) {}
}

sealed class Result<out T> {
  data class Ok<out T>(val value: T):  Result<T>()
  data class Err(val message: String): Result<Nothing>()
}

@Suppress("unused")
class RTCModule(reactContext: ReactApplicationContext):
  ReactContextBaseJavaModule(reactContext)
{
  companion object {
    const val MODULE_NAME = "RCTRTCModule"
    private var peers: MutableMap<String, RTCPeer> = mutableMapOf()

    fun getPeer(name: String): RTCPeer? = peers[name]

    fun syncSubscriberFeeds() {
      val publishers = peers[VideoRoomPlugin.subscriberPeerName]?.toJSON() ?: JSONArray()

      RTCEventEmitter.event("video_room.sync", JSONObject().apply {
        put("publishers", publishers)
      })
    }

    fun createPeer(peerName: String): Result<Boolean> {
      return if (peers.containsKey(peerName)) {
        Result.Err("Peer: '$peerName' already exists")
      } else {
        val peer = RTCPeer(peerName)
        if (RTCService.createPeerConnection(peer)) {
          peers[peerName] = peer
        }
        Result.Ok(true)
      }
    }

    fun createOffer(
      peerName: String,
      reactContext: ReactApplicationContext,
      callback: (Result<JSONObject>) -> Unit
    ) {
      val rtcPeer = peers[peerName] ?: return callback(Result.Err("Peer: '$peerName' is not exists"))
      val peer = rtcPeer.peer

      // Define another SdpObserver for setting local description
      val localSdpObserver = object: SdpObserverAdapter {
        override fun onSetSuccess() {
          // The local description has been set successfully
          val localDescription = peer.localDescription
          val jsonResult = JSONObject().apply {
            put("type", localDescription.type.canonicalForm())
            put("sdp", localDescription.description)
          }
          Log.d(TAG, "Offer created successfully: $jsonResult")
          callback(Result.Ok(jsonResult))
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
        RTCDeviceManager.activateCamera(reactContext)
      }
      for (t in RTCDeviceManager.mediaStream!!.audioTracks) peer.addTrack(t)
      for (t in RTCDeviceManager.mediaStream!!.videoTracks) peer.addTrack(t)

      peer.createOffer(offerSdpObserver, RTCService.mediaConstraint)
    }

    fun setRemoteSDP(peerName: String, sdp: JSONObject) {
      val rtcPeer = peers[peerName]
      if (rtcPeer == null) {
        Log.e(TAG, "Peer: '$peerName' is not exists")
        return
      }

      val type        = sdp.getString("type")
      val description = sdp.getString("sdp")

      if (type.isEmpty() || description.isEmpty()) {
        Log.e(TAG, "Invalid SDP format")
        return
      }

      val remoteSDP = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), description)
      val sdpObserver = object : SdpObserverAdapter {
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String) {
          Log.e(TAG, "Error setting remote SDP: $error")
        }
      }

      rtcPeer.peer.setRemoteDescription(sdpObserver, remoteSDP)
    }

    fun hasPeer(peerName: String): Boolean {
      return peers.containsKey(peerName)
    }

    fun sync(peerName: String, streams: List<JSONObject>) {
      val peer = peers[peerName] ?: throw IllegalStateException("Peer: '$peerName' is not exists")

      val feedIds = mutableListOf<Long>()
      for (i in streams.indices) {
        val streamObject = streams[i]

        val feedId    = streamObject.getLong("feed_id")
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
      val publishersToRemove = mutableListOf<Long>()
      while (iterator.hasNext()) {
        val (feedId, publisher) = iterator.next()
        if (!feedIds.contains(publisher.id)) {
          publishersToRemove.add(feedId)
        }
      }
      for (feedId in publishersToRemove) {
        peer.streams.remove(feedId)
      }
    }

    fun answer(peerName: String, sdp: JSONObject, callback: (Result<JSONObject>) -> Unit) {
      val peerWrapper = peers[peerName] ?: return callback(Result.Err("Peer: '$peerName' is not exists"))
      val peer = peerWrapper.peer

      val type        = sdp.getString("type")
      val description = sdp.getString("sdp")

      if (type.isNullOrEmpty() || description.isNullOrEmpty()) {
        return callback(Result.Err("Invalid SDP format"))
      }

      val remoteSDPObserver = object: SdpObserverAdapter {
        override fun onSetSuccess() {
          peer.createAnswer(createAnswerSDPObserver(peer, callback), RTCService.mediaConstraint)
        }

        override fun onSetFailure(error: String) {
          callback(Result.Err("Error setting remote description: $error"))
        }
      }
      val remoteSDP = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), description)
      peer.setRemoteDescription(remoteSDPObserver, remoteSDP)
    }

    private fun createAnswerSDPObserver(
      peer: PeerConnection,
      callback: (Result<JSONObject>) -> Unit
    ): SdpObserverAdapter {
      val localSDPObserver = object: SdpObserverAdapter {
        override fun onSetFailure(error: String) {
          callback(Result.Err("Error setting local description: $error"))
        }

        override fun onSetSuccess() {
          val localDescription = peer.localDescription
          val jsonResult = JSONObject().apply {
            put("type", localDescription.type.canonicalForm())
            put("sdp", localDescription.description)
          }
          callback(Result.Ok(jsonResult))
        }
      }

      return object: SdpObserverAdapter {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {
          peer.setLocalDescription(localSDPObserver, sessionDescription)
        }

        override fun onCreateFailure(error: String) {
          callback(Result.Err("Error creating answer: $error"))
        }
      }
    }
  }

  private val signaller = RTCSignaller(reactContext)

  override fun getName() = MODULE_NAME

  @ReactMethod
  fun init(url: String) {
    RTCService.signaller = signaller
    signaller.init(url)
  }

  @ReactMethod
  fun attach(pluginName: String) {
    signaller.attach(pluginName)
  }

  @ReactMethod
  fun pluginMethod(pluginName: String, methodName: String, args: ReadableMap) {
    when (pluginName) {
      "video_room" -> {
        val plugin = signaller.findPluginByName(pluginName)
          ?: return RTCEventEmitter.error("'$pluginName' is not attched.")

        (plugin as VideoRoomPlugin).handleMethod(methodName, args)
      }
      else -> {
        RTCEventEmitter.error("'$pluginName' is not found.")
      }
    }
  }

  @ReactMethod
  fun destroy() {
    RTCDeviceManager.release()
    for (p in peers.values) {
      for (s in p.streams.values) {
        s.mediaStream?.audioTracks?.forEach { track -> track.dispose() }
        s.mediaStream?.videoTracks?.forEach { track -> track.dispose() }
      }
      p.peer.close()
    }
    peers.clear()
    signaller.destroy()

    Log.w(TAG, "DESTROYED")
  }
}
