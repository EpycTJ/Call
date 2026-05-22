package com.webrtc.app;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

public class WebRTCClient {
    private static final String TAG = "WebRTCClient";
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";

    private final Context context;
    private final EglBase.Context eglBaseContext;
    private final PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private final List<PeerConnection.IceServer> iceServers;

    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private CameraVideoCapturer videoCapturer;

    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;

    private boolean isMuted = false;

    public interface WebRTCObserver {
        void onIceCandidate(IceCandidate candidate);
        void onSessionDescription(SessionDescription sdp);
    }

    private WebRTCObserver observer;

    public WebRTCClient(Context context, EglBase.Context eglBaseContext) {
        this.context = context;
        this.eglBaseContext = eglBaseContext;

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                eglBaseContext, true, true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(eglBaseContext);

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();

        iceServers = new ArrayList<>();
        // Add STUN server for NAT traversal
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
    }

    public void setObserver(WebRTCObserver observer) {
        this.observer = observer;
    }

    public void initViews(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        this.localView = local;
        this.remoteView = remote;
        this.localView.init(eglBaseContext, null);
        this.localView.setMirror(true);
        this.remoteView.init(eglBaseContext, null);
        this.remoteView.setMirror(false);
    }

    public void startLocalMedia() {
        videoCapturer = createCameraCapturer(new Camera2Enumerator(context));
        if (videoCapturer == null) {
            videoCapturer = createCameraCapturer(new Camera1Enumerator(true));
        }

        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        videoCapturer.startCapture(1280, 720, 30); // HD format for quality

        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.addSink(localView);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(createAudioConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
    }

    private CameraVideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First try front-facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        // Fallback to back-facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private MediaConstraints createAudioConstraints() {
        MediaConstraints audioConstraints = new MediaConstraints();
        // Constraints to enable high-quality audio
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        return audioConstraints;
    }

    public void call() {
        createPeerConnection();
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                String modifiedSdp = CodecModifier.preferOpus(sessionDescription.description);
                SessionDescription sdp = new SessionDescription(sessionDescription.type, modifiedSdp);
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sdp);
                if (observer != null) observer.onSessionDescription(sdp);
            }
        }, sdpMediaConstraints);
    }

    public void answer() {
        createPeerConnection();
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                String modifiedSdp = CodecModifier.preferOpus(sessionDescription.description);
                SessionDescription sdp = new SessionDescription(sessionDescription.type, modifiedSdp);
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sdp);
                if (observer != null) observer.onSessionDescription(sdp);
            }
        }, sdpMediaConstraints);
    }

    private void createPeerConnection() {
        if (peerConnection != null) return;

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                if (observer != null) observer.onIceCandidate(iceCandidate);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(MediaStream mediaStream) {
                if (mediaStream.videoTracks.size() > 0) {
                    VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                    remoteVideoTrack.addSink(remoteView);
                }
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(org.webrtc.RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                // Unified plan track handling
                org.webrtc.MediaStreamTrack track = rtpReceiver.track();
                if (track instanceof VideoTrack) {
                    ((VideoTrack) track).addSink(remoteView);
                }
            }
        });

        // Add local stream
        if (localVideoTrack != null && localAudioTrack != null) {
            MediaStream stream = peerConnectionFactory.createLocalMediaStream("ARDAMS");
            stream.addTrack(localVideoTrack);
            stream.addTrack(localAudioTrack);
            peerConnection.addStream(stream);
        }
    }

    public void setRemoteDescription(SessionDescription sdp) {
        if (peerConnection == null) createPeerConnection();
        peerConnection.setRemoteDescription(new SimpleSdpObserver(), sdp);
    }

    public void addIceCandidate(IceCandidate candidate) {
        if (peerConnection != null) {
            peerConnection.addIceCandidate(candidate);
        }
    }

    public void toggleAudio() {
        if (localAudioTrack != null) {
            isMuted = !isMuted;
            localAudioTrack.setEnabled(!isMuted);
        }
    }

    public void toggleCamera() {
        if (videoCapturer != null) {
            try {
                videoCapturer.switchCamera(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch camera", e);
            }
        }
    }

    public void hangup() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
    }

    public void destroy() {
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            videoCapturer.dispose();
        }
        if (localView != null) localView.release();
        if (remoteView != null) remoteView.release();
        if (peerConnection != null) peerConnection.close();
        if (peerConnectionFactory != null) peerConnectionFactory.dispose();
    }
}
