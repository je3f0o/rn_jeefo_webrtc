package com.jeefowebrtclibrary

import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.signaller.RTCSignaller
import com.signaller.VideoRoomPlugin
import org.json.JSONObject
import org.webrtc.*

object RTCService {
  private var isInitialized = false
  lateinit var peerConnectionFactory: PeerConnectionFactory
  var signaller: RTCSignaller? = null

  val mediaConstraint = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("iceRestart", "true"))

    optional.add(MediaConstraints.KeyValuePair("RtpDataChannels", "true"))
    optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    optional.add(MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"))
    optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "true"))
    optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetectionThreshold", "true"))
  }

  fun initializePeerConnectionFactory(reactContext: ReactApplicationContext) {
//    if (isInitialized) return

    val initializationOptions = PeerConnectionFactory
      .InitializationOptions
      .builder(reactContext.applicationContext)
//        .setEnableInternalTracer(true)
//        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
      .createInitializationOptions()
    PeerConnectionFactory.initialize(initializationOptions)
    isInitialized = true

    val encoder = DefaultVideoEncoderFactory(eglBaseContext, true, true)
    val decoder = DefaultVideoDecoderFactory(eglBaseContext)
    peerConnectionFactory = PeerConnectionFactory
      .builder()
      .setVideoDecoderFactory(decoder)
      .setVideoEncoderFactory(encoder)
      .createPeerConnectionFactory()

    for (codec in encoder.supportedCodecs) {
      Log.d(TAG, "Supported encoder codec: ${codec.name}, params: ${codec.params}")
    }
    for (codec in decoder.supportedCodecs) {
      Log.d(TAG, "Supported decoder codec: ${codec.name}, params: ${codec.params}")
    }
    Log.w(TAG, "Initialized PeerConnectionFactory")
  }

  fun createPeerConnection(rtcPeer: RTCPeer): Boolean {
    val iceServers = listOf(
      PeerConnection.IceServer.builder("stun:freestun.net:3479").createIceServer(),
      PeerConnection.IceServer.builder("stun:freestun.net:5350").createIceServer(),
      PeerConnection.IceServer.builder("turn:freestun.net:3479")
        .setUsername("free")
        .setPassword("free")
        .createIceServer(),
      PeerConnection.IceServer.builder("turns:freestun.net:5350")
        .setUsername("free")
        .setPassword("free")
        .createIceServer()
    )
//    val iceServers = emptyList<PeerConnection.IceServer>()
    val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
    rtcConfig.sdpSemantics      = PeerConnection.SdpSemantics.UNIFIED_PLAN
    rtcConfig.rtcpMuxPolicy     = PeerConnection.RtcpMuxPolicy.REQUIRE
    rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL

    rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
    rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    rtcConfig.keyType = PeerConnection.KeyType.ECDSA
    rtcConfig.enableDtlsSrtp = true
    rtcConfig.enableRtpDataChannel = true

    val observer = makePeerConnectionObserver(rtcPeer)
    rtcPeer.peer = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!

    return true
  }

  private fun makePeerConnectionObserver(rtcPeer: RTCPeer): PeerConnection.Observer {
    return object: PeerConnection.Observer {
      override fun onSignalingChange(e: PeerConnection.SignalingState?) {
        Log.d(TAG, "onSignalingChange: $e")
      }

      override fun onIceConnectionChange(e: PeerConnection.IceConnectionState?) {
        Log.d(TAG, "onIceConnectionChange: $e")
        when (e) {
          PeerConnection.IceConnectionState.CONNECTED -> {}
          PeerConnection.IceConnectionState.DISCONNECTED -> {}
          else -> {}
        }
      }

      override fun onIceConnectionReceivingChange(e: Boolean) {
        Log.d(TAG, "onIceConnectionReceivingChange: $e")
      }

      override fun onIceGatheringChange(e: PeerConnection.IceGatheringState?) {
        Log.d(TAG, "onIceGatheringChange: $e")
      }

      override fun onIceCandidate(candidate: IceCandidate?) {
        Log.d(TAG, "onIceCandidate: $candidate")
        sendIceCandidate(candidate)
      }

      override fun onIceCandidatesRemoved(e: Array<out IceCandidate>?) {
        Log.d(TAG, "onIceCandidatesRemoved: $e")
      }

      override fun onAddStream(stream: MediaStream?) {
        Log.d(TAG, "onAddStream: $stream")
      }

      override fun onRemoveStream(e: MediaStream?) {
        Log.d(TAG, "onRemoveStream: $e")
      }

      override fun onDataChannel(e: DataChannel?) {
        Log.d(TAG, "onDataChannel: $e")
      }

      override fun onRenegotiationNeeded() {
        Log.d(TAG, "onRenegotiationNeeded:")
      }

      override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
        Log.d(TAG, "onAddTrack: $receiver, $streams")
        val recvTrack   = receiver?.track() ?: return
        val inputStream = streams?.first() ?: return
        val mediaStream = getStreamByReceiverID(receiver.id(), rtcPeer) ?: return

        when (recvTrack.kind()) {
          "audio" -> for (t in inputStream.audioTracks) {
            if (t.id() == recvTrack.id()) mediaStream.addTrack(t)
          }
          "video" -> for (t in inputStream.videoTracks) {
            if (t.id() == recvTrack.id()) mediaStream.addTrack(t)
          }
        }
      }
    }
  }

  private fun getStreamByReceiverID(id: String, rtcPeer: RTCPeer): MediaStream? {
    var mid = -1
    for ((i, t) in rtcPeer.peer.transceivers.withIndex()) {
      if (id == t.receiver.id()) {
        mid = i
        break
      }
    }

    for (s in rtcPeer.streams.values) if (s.mids.contains(mid)) return s.mediaStream
    return null
  }

  private fun sendIceCandidate(candidate: IceCandidate?) {
    val plugin = signaller?.findPluginByName("video_room") as VideoRoomPlugin
    val data = JSONObject().apply {
      put("janus", "trickle")
      put("session_id", signaller?.sessionId)
      put("transaction", signaller?.transactionId)
      put("handle_id", plugin.publisherId)
    }

    if (candidate == null) {
      data.put("candidate", null)
    } else {
      data.put("candidate", JSONObject().apply {
        put("sdpMid", candidate.sdpMid)
        put("sdpMLineIndex", candidate.sdpMLineIndex)
        put("candidate", candidate.sdp)
      })
    }
//    signaller?.send(data)
  }
}
