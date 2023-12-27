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
import com.signaller.RTCEventEmitter
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
  private var peerName = "NO_PEER"
  private var feedId   = 0L

  private var mediaStream: MediaStream? = null

  private var listener: RendererCommon.RendererEvents? = null

  override fun createViewInstance(reactContext: ThemedReactContext): View {
    context = reactContext.reactApplicationContext

    videoView = SurfaceViewRenderer(reactContext)
    videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    videoView.layoutParams = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT,
      Gravity.CENTER
    )
    listener = object: RendererCommon.RendererEvents {
      override fun onFirstFrameRendered() {}
      override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
        RTCModule.getPeer(peerName)?.streams?.get(feedId)?.resolution.let {
          it?.width  = width.toLong()
          it?.height = height.toLong()
        }
        updateView()
      }
    }
    videoView.init(eglBaseContext, listener)

    container = FrameLayout(reactContext)
    container.setBackgroundColor(Color.BLACK)
    container.addView(videoView)

    return container
  }

  override fun onDropViewInstance(view: View) {
    super.onDropViewInstance(view)
    mediaStream?.videoTracks?.firstOrNull()?.removeSink(videoView)
    mediaStream?.videoTracks?.firstOrNull()?.removeSink(videoView)
    videoView.release()
    Log.w(TAG, "View $peerName fucking released...")
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @ReactProp(name = "feedName")
  fun setFeedName(view: View, value: String?) {
    if (value == null) return

    when (value) {
      "local" -> {
        if (!RTCDeviceManager.isCameraActivated) {
          RTCDeviceManager.activateCamera(context)
        }
        peerName = "local"
        mediaStream = RTCDeviceManager.mediaStream
        if (mediaStream != null) {
          mediaStream!!.videoTracks.firstOrNull()?.addSink(videoView)
        } else {
          RTCEventEmitter.error("WTF???????????????????????????????")
        }
      }
      else -> {
        val p = splitByLastColon(value) ?: return
        peerName = p.first
        feedId   = p.second.toLong()
        val rtcPeer = RTCModule.getPeer(peerName)
          ?: return RTCEventEmitter.error("Peer: '$peerName' not found.")
        mediaStream = rtcPeer.streams[feedId]!!.mediaStream
          ?: return RTCEventEmitter.error("Stream feed: '$feedId' not found in '$peerName' peer.")
        mediaStream!!.videoTracks.firstOrNull()?.addSink(videoView)
      }
    }
  }

  @Suppress("unused", "UNUSED_PARAMETER")
  @ReactProp(name = "side")
  fun setSide(view: View, value: String?) {
    if (peerName != "local") return
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
      val firstPart  = input.substring(0, lastColonIndex)
      val secondPart = input.substring(lastColonIndex + 1)
      Pair(firstPart, secondPart)
    } else {
      null
    }
  }

  private fun updateView() {
    val jsModule = context
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)

    if (peerName == "local") {
      jsModule.emit("update_view", peerName)
    } else {
      Log.w(TAG, "updateView $peerName ------------------------------")
      jsModule.emit("update_view", "$peerName:$feedId")
      RTCModule.syncSubscriberFeeds()
    }
  }
}
