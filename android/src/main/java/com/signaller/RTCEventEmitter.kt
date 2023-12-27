package com.signaller

import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject

object RTCEventEmitter {
  private var reactContext: ReactContext? = null

  fun initialize(reactContext: ReactContext) {
    this.reactContext = reactContext
  }

  fun event(eventType: String, data: JSONObject) {
    data.put("type", eventType)
    reactContext
      ?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      ?.emit("event", data.toString())
  }

  fun error(message: String) {
    event("error", JSONObject().apply {
      put("message", message)
    })
  }
}
