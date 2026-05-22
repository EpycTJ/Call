package com.webrtc.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    private var rootEglBase: EglBase? = null

    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var btnCall: Button
    private lateinit var btnMic: Button
    private lateinit var btnCamera: Button

    private var isMuted = false
    private var isCalling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localView = findViewById(R.id.local_view)
        remoteView = findViewById(R.id.remote_view)
        btnCall = findViewById(R.id.btn_call)
        btnMic = findViewById(R.id.btn_mic)
        btnCamera = findViewById(R.id.btn_camera)

        rootEglBase = EglBase.create()

        btnCall.setOnClickListener { toggleCall() }
        btnMic.setOnClickListener { toggleMic() }
        btnCamera.setOnClickListener { toggleCamera() }

        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        var needsRequest = false
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true
                break
            }
        }

        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE)
        } else {
            init()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                init()
            } else {
                Log.e(TAG, "Permissions not granted")
                // In a real app, inform the user they need permissions
            }
        }
    }

    private fun init() {
        webRTCClient = rootEglBase?.let { WebRTCClient(applicationContext, it.eglBaseContext) }
        webRTCClient?.initViews(localView, remoteView)

        signalingClient = SignalingClient(SIGNALING_URL, object : SignalingClient.Callback {
            override fun onOfferReceived(data: JSONObject) {
                runOnUiThread {
                    try {
                        val sdp = data.getString("sdp")
                        webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                        webRTCClient?.answer()
                        updateCallButton(true)
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onAnswerReceived(data: JSONObject) {
                runOnUiThread {
                    try {
                        val sdp = data.getString("sdp")
                        webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onIceCandidateReceived(data: JSONObject) {
                runOnUiThread {
                    try {
                        val candidate = IceCandidate(
                            data.getString("sdpMid"),
                            data.getInt("sdpMLineIndex"),
                            data.getString("candidate")
                        )
                        webRTCClient?.addIceCandidate(candidate)
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                }
            }
        })

        webRTCClient?.setObserver(object : WebRTCClient.WebRTCObserver {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient?.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }

            override fun onSessionDescription(sdp: SessionDescription) {
                if (sdp.type == SessionDescription.Type.OFFER) {
                    signalingClient?.sendOffer(sdp.description)
                } else if (sdp.type == SessionDescription.Type.ANSWER) {
                    signalingClient?.sendAnswer(sdp.description)
                }
            }
        })

        webRTCClient?.startLocalMedia()
        signalingClient?.connect()
    }

    private fun toggleCall() {
        if (!isCalling) {
            webRTCClient?.call()
            updateCallButton(true)
        } else {
            webRTCClient?.hangup()
            updateCallButton(false)
        }
    }

    private fun updateCallButton(calling: Boolean) {
        isCalling = calling
        btnCall.text = if (isCalling) getString(R.string.hangup) else getString(R.string.call)
    }

    private fun toggleMic() {
        isMuted = !isMuted
        btnMic.text = if (isMuted) getString(R.string.mic_off) else getString(R.string.mic_on)
        webRTCClient?.toggleAudio()
    }

    private fun toggleCamera() {
        webRTCClient?.toggleCamera()
    }

    override fun onDestroy() {
        webRTCClient?.destroy()
        signalingClient?.destroy()
        rootEglBase?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 100
        // NOTE: In a real app, this should be an actual WebSocket server address
        private const val SIGNALING_URL = "ws://10.0.2.2:8080"
    }
}
