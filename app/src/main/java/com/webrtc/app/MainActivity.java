package com.webrtc.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 100;

    // NOTE: In a real app, this should be an actual WebSocket server address
    private static final String SIGNALING_URL = "ws://10.0.2.2:8080";

    private WebRTCClient webRTCClient;
    private SignalingClient signalingClient;
    private EglBase rootEglBase;

    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;
    private Button btnCall;
    private Button btnMic;
    private Button btnCamera;

    private boolean isMuted = false;
    private boolean isCalling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        localView = findViewById(R.id.local_view);
        remoteView = findViewById(R.id.remote_view);
        btnCall = findViewById(R.id.btn_call);
        btnMic = findViewById(R.id.btn_mic);
        btnCamera = findViewById(R.id.btn_camera);

        rootEglBase = EglBase.create();

        btnCall.setOnClickListener(v -> toggleCall());
        btnMic.setOnClickListener(v -> toggleMic());
        btnCamera.setOnClickListener(v -> toggleCamera());

        requestPermissions();
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };

        boolean needsRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }

        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE);
        } else {
            init();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                init();
            } else {
                Log.e(TAG, "Permissions not granted");
                // In a real app, inform the user they need permissions
            }
        }
    }

    private void init() {
        webRTCClient = new WebRTCClient(getApplicationContext(), rootEglBase.getEglBaseContext());
        webRTCClient.initViews(localView, remoteView);

        signalingClient = new SignalingClient(SIGNALING_URL, new SignalingClient.Callback() {
            @Override
            public void onOfferReceived(JSONObject data) {
                runOnUiThread(() -> {
                    try {
                        String sdp = data.getString("sdp");
                        webRTCClient.setRemoteDescription(new SessionDescription(SessionDescription.Type.OFFER, sdp));
                        webRTCClient.answer();
                        updateCallButton(true);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void onAnswerReceived(JSONObject data) {
                runOnUiThread(() -> {
                    try {
                        String sdp = data.getString("sdp");
                        webRTCClient.setRemoteDescription(new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void onIceCandidateReceived(JSONObject data) {
                runOnUiThread(() -> {
                    try {
                        IceCandidate candidate = new IceCandidate(
                                data.getString("sdpMid"),
                                data.getInt("sdpMLineIndex"),
                                data.getString("candidate")
                        );
                        webRTCClient.addIceCandidate(candidate);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                });
            }
        });

        webRTCClient.setObserver(new WebRTCClient.WebRTCObserver() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                signalingClient.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
            }

            @Override
            public void onSessionDescription(SessionDescription sdp) {
                if (sdp.type == SessionDescription.Type.OFFER) {
                    signalingClient.sendOffer(sdp.description);
                } else if (sdp.type == SessionDescription.Type.ANSWER) {
                    signalingClient.sendAnswer(sdp.description);
                }
            }
        });

        webRTCClient.startLocalMedia();
        signalingClient.connect();
    }

    private void toggleCall() {
        if (!isCalling) {
            webRTCClient.call();
            updateCallButton(true);
        } else {
            webRTCClient.hangup();
            updateCallButton(false);
        }
    }

    private void updateCallButton(boolean calling) {
        isCalling = calling;
        btnCall.setText(isCalling ? getString(R.string.hangup) : getString(R.string.call));
    }

    private void toggleMic() {
        isMuted = !isMuted;
        btnMic.setText(isMuted ? getString(R.string.mic_off) : getString(R.string.mic_on));
        if (webRTCClient != null) {
            webRTCClient.toggleAudio();
        }
    }

    private void toggleCamera() {
        if (webRTCClient != null) {
            webRTCClient.toggleCamera();
        }
    }

    @Override
    protected void onDestroy() {
        if (webRTCClient != null) {
            webRTCClient.destroy();
        }
        if (signalingClient != null) {
            signalingClient.destroy();
        }
        if (rootEglBase != null) {
            rootEglBase.release();
        }
        super.onDestroy();
    }
}
