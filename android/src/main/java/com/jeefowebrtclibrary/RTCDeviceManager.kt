package com.jeefowebrtclibrary

import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import org.webrtc.*

object RTCDeviceManager {
  var isCameraActivated = false
  var mediaStream: MediaStream? = null
  private var localVideoTrack: VideoTrack? = null
  private var localAudioTrack: AudioTrack? = null
  private var cameraVideoCapturer: CameraVideoCapturer? = null
  private var audioSource: AudioSource? = null

  fun activateCamera(context: ReactApplicationContext) {
    if (isCameraActivated) return

    val peerConnectionFactory = getPeerConnectionFactory()
    cameraVideoCapturer = createCameraVideoCapturer(context)

    // Video setup
    val localVideoSource = peerConnectionFactory.createVideoSource(false)
    val textureHelper = SurfaceTextureHelper.create("CameraVideoCapturerThread", eglBaseContext)
    localVideoTrack = peerConnectionFactory.createVideoTrack("local_video_track", localVideoSource)
    cameraVideoCapturer?.initialize(textureHelper, context, localVideoSource.capturerObserver)
    cameraVideoCapturer?.startCapture(640, 480, 30)

    // Audio setup
    audioSource = peerConnectionFactory.createAudioSource(RTCService.mediaConstraint)
    localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource)

    // Media stream setup
    mediaStream = RTCService.peerConnectionFactory.createLocalMediaStream("local_camera")
    mediaStream?.addTrack(localVideoTrack)
    mediaStream?.addTrack(localAudioTrack)

    isCameraActivated = true
    Log.d(TAG, "Camera activated!")
  }

  fun switchCamera() {
    cameraVideoCapturer?.switchCamera(null)
  }

  fun release() {
    localVideoTrack?.dispose()
    localAudioTrack?.dispose()
    if (audioSource != null) {
      audioSource?.dispose()
      audioSource = null
    }
    if (cameraVideoCapturer != null) {
      cameraVideoCapturer?.dispose()
      cameraVideoCapturer = null
    }
    localVideoTrack = null
    localAudioTrack = null
    mediaStream     = null
    isCameraActivated = false
  }

  private fun getPeerConnectionFactory(): PeerConnectionFactory {
    val encoder = DefaultVideoEncoderFactory(eglBaseContext, true, true)
    val decoder = DefaultVideoDecoderFactory(eglBaseContext)
    val options = PeerConnectionFactory.Options().apply {
      disableEncryption = true
      disableNetworkMonitor = true
    }
    return PeerConnectionFactory
      .builder()
      .setOptions(options)
      .setVideoDecoderFactory(decoder)
      .setVideoEncoderFactory(encoder)
      .createPeerConnectionFactory()
  }

  private fun createCameraVideoCapturer(context: ReactApplicationContext): CameraVideoCapturer {
    val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
      Camera2Enumerator(context)
    } else {
      Camera1Enumerator(false)
    }

    // Choose the front-facing camera
    val deviceNames = enumerator.deviceNames
    var selectedDevice: String? = null
    for (deviceName in deviceNames) {
      if (enumerator.isFrontFacing(deviceName)) {
        selectedDevice = deviceName
        break
      }
    }

    return enumerator.createCapturer(selectedDevice, null)
      ?: throw RuntimeException("Failed to open camera")
  }
}
