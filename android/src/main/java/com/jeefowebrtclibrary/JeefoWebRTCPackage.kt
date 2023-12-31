package com.jeefowebrtclibrary

import android.util.Log
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.signaller.RTCEventEmitter
import org.webrtc.EglBase

const val TAG = "WebRTC-TAG"
val eglBaseContext: EglBase.Context = EglBase.create().eglBaseContext

@Suppress("unused")
class JeefoWebRTCPackage: ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
    RTCEventEmitter.initialize(reactContext)
    RTCService.initializePeerConnectionFactory(reactContext)
    return listOf(RTCModule(reactContext))
  }

  // If you have custom view managers, implement this method
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
    return listOf(RTCViewManager())
  }
}
