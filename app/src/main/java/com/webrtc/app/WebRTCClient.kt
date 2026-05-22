package com.webrtc.app

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRTCClient(private val context: Context, private val eglBaseContext: EglBase.Context) {

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val iceServers: MutableList<PeerConnection.IceServer>
    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private var observer: WebRTCObserver? = null
    private var isMuted = false

    interface WebRTCObserver {
        fun onIceCandidate(candidate: IceCandidate)
        fun onSessionDescription(sdp: SessionDescription)
    }

    init {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()

        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        iceServers = ArrayList()
        // Add STUN server for NAT traversal
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    }

    fun setObserver(observer: WebRTCObserver) {
        this.observer = observer
    }

    fun initViews(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        this.localView = local
        this.remoteView = remote
        this.localView?.init(eglBaseContext, null)
        this.localView?.setMirror(true)
        this.remoteView?.init(eglBaseContext, null)
        this.remoteView?.setMirror(false)
    }

    fun startLocalMedia() {
        videoCapturer = createCameraCapturer(Camera2Enumerator(context))
        if (videoCapturer == null) {
            videoCapturer = createCameraCapturer(Camera1Enumerator(true))
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer?.isScreencast == true)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30) // HD format for quality

        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localView?.let { localVideoTrack?.addSink(it) }

        val audioSource = peerConnectionFactory.createAudioSource(createAudioConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): CameraVideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First try front-facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        // Fallback to back-facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }

    private fun createAudioConstraints(): MediaConstraints {
        val audioConstraints = MediaConstraints()
        // Constraints to enable high-quality audio
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        return audioConstraints
    }

    fun call() {
        createPeerConnection()
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let {
                    val modifiedSdp = CodecModifier.preferOpus(it.description)
                    val sdp = SessionDescription(it.type, modifiedSdp)
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                    observer?.onSessionDescription(sdp)
                }
            }
        }, sdpMediaConstraints)
    }

    fun answer() {
        createPeerConnection()
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let {
                    val modifiedSdp = CodecModifier.preferOpus(it.description)
                    val sdp = SessionDescription(it.type, modifiedSdp)
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                    observer?.onSessionDescription(sdp)
                }
            }
        }, sdpMediaConstraints)
    }

    private fun createPeerConnection() {
        if (peerConnection != null) return

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {}

            override fun onIceConnectionReceivingChange(b: Boolean) {}

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {}

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                observer?.onIceCandidate(iceCandidate)
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}

            override fun onAddStream(mediaStream: MediaStream) {
                if (mediaStream.videoTracks.size > 0) {
                    val remoteVideoTrack = mediaStream.videoTracks[0]
                    remoteView?.let { remoteVideoTrack.addSink(it) }
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                // Unified plan track handling
                val track = rtpReceiver.track()
                if (track is VideoTrack) {
                    remoteView?.let { track.addSink(it) }
                }
            }
        })

        // Add local stream
        if (localVideoTrack != null && localAudioTrack != null) {
            val stream = peerConnectionFactory.createLocalMediaStream("ARDAMS")
            stream.addTrack(localVideoTrack)
            stream.addTrack(localAudioTrack)
            peerConnection?.addStream(stream)
        }
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        if (peerConnection == null) createPeerConnection()
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun toggleAudio() {
        localAudioTrack?.let {
            isMuted = !isMuted
            it.setEnabled(!isMuted)
        }
    }

    fun toggleCamera() {
        try {
            videoCapturer?.switchCamera(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera", e)
        }
    }

    fun hangup() {
        peerConnection?.close()
        peerConnection = null
    }

    fun destroy() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        videoCapturer?.dispose()
        localView?.release()
        remoteView?.release()
        peerConnection?.close()
        peerConnectionFactory.dispose()
    }

    companion object {
        private const val TAG = "WebRTCClient"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
    }
}
