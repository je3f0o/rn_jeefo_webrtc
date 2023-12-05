package com.jeefowebrtclibrary

import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import org.webrtc.MediaStream
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class RTCViewManager : SimpleViewManager<View>() {
  companion object {
    const val REACT_CLASS = "RCTRTCView"
  }
  private lateinit var videoView: SurfaceViewRenderer
  override fun getName() = REACT_CLASS

  private lateinit var context: ReactApplicationContext
  private lateinit var container: ViewGroup
  private var peerFeedName = "NO_FEED"
  private var streamName = "NO_NAME"

  override fun createViewInstance(reactContext: ThemedReactContext): View {
    context = reactContext.reactApplicationContext

    videoView = SurfaceViewRenderer(reactContext)
    videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    videoView.layoutParams = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT,
      Gravity.CENTER
    )
    videoView.init(eglBaseContext, object: RendererCommon.RendererEvents {
      override fun onFirstFrameRendered() {}
      override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
        Log.i(TAG, "$streamName videoWidth: $width, videoHeight: $height")
        updateView()
      }
    })

    container = FrameLayout(reactContext)
    container.setBackgroundColor(Color.BLACK)
    container.addView(videoView)

    return container
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @ReactProp(name = "peerName")
  fun setFeedName(view: View, value: String?) {
    var peerName = value ?: return
    var feedName = "NO_FEED"
    val stream: MediaStream
    peerFeedName = value

    when (peerName) {
      "local" -> {
        if (!RTCDeviceManager.isCameraActivated) {
          RTCDeviceManager.activateCamera(context)
        }
        stream = RTCDeviceManager.mediaStream
      }
      "remote" -> {
        val rtcPeer = RTCModule.getPeer(peerName) ?: return
        stream = rtcPeer.streams[peerName]!!.mediaStream
      }
      else -> {
        val p = splitByLastColon(peerName) ?: return
        peerName = p.first
        feedName = p.second
        val rtcPeer = RTCModule.getPeer(peerName) ?: return
        stream = rtcPeer.streams[feedName]!!.mediaStream
      }
    }
    streamName = stream.id
    stream.videoTracks.firstOrNull()?.addSink(videoView)
    Log.d(TAG, "SINKED Peer: $peerName, Feed: $feedName, A tracks: ${stream.audioTracks.size}, V tracks: ${stream.videoTracks.size} --------------------------------")
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @ReactProp(name = "side")
  fun setSide(view: View, value: String?) {
    if (peerFeedName != "local") return
    val side = value ?: return
    when (side.lowercase()) {
      "back" -> {
        RTCDeviceManager.switchCamera()
      }
      "front" -> {
        RTCDeviceManager.switchCamera()
      }
    }
    updateView()
  }

  private fun splitByLastColon(input: String): Pair<String, String>? {
    val lastColonIndex = input.lastIndexOf(':')
    return if (lastColonIndex != -1 && lastColonIndex != input.length - 1) {
      val firstPart = input.substring(0, lastColonIndex)
      val secondPart = input.substring(lastColonIndex + 1)
      Pair(firstPart, secondPart)
    } else {
      null
    }
  }

  private fun updateView() {
    context
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit("update_view", peerFeedName)
  }
}
