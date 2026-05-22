package com.webrtc.app

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

class SignalingClient(private val url: String, private val callback: Callback) {
    private var webSocket: WebSocketClient? = null

    interface Callback {
        fun onOfferReceived(data: JSONObject)
        fun onAnswerReceived(data: JSONObject)
        fun onIceCandidateReceived(data: JSONObject)
    }

    fun connect() {
        try {
            val uri = URI(url)
            webSocket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake) {
                    Log.d(TAG, "WebSocket connection opened")
                }

                override fun onMessage(message: String) {
                    try {
                        val jsonMessage = JSONObject(message)
                        when (jsonMessage.getString("type")) {
                            "offer" -> callback.onOfferReceived(jsonMessage)
                            "answer" -> callback.onAnswerReceived(jsonMessage)
                            "candidate" -> callback.onIceCandidateReceived(jsonMessage)
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing message", e)
                    }
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    Log.d(TAG, "WebSocket connection closed: $reason")
                }

                override fun onError(ex: Exception) {
                    Log.e(TAG, "WebSocket error", ex)
                }
            }
            webSocket?.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid WebSocket URL", e)
        }
    }

    fun sendOffer(sdp: String) {
        sendMessage(createJsonMessage("offer", sdp))
    }

    fun sendAnswer(sdp: String) {
        sendMessage(createJsonMessage("answer", sdp))
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        try {
            val message = JSONObject()
            message.put("type", "candidate")
            message.put("sdpMid", sdpMid)
            message.put("sdpMLineIndex", sdpMLineIndex)
            message.put("candidate", sdp)
            sendMessage(message)
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating ice candidate message", e)
        }
    }

    private fun createJsonMessage(type: String, sdp: String): JSONObject {
        val message = JSONObject()
        try {
            message.put("type", type)
            message.put("sdp", sdp)
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating message", e)
        }
        return message
    }

    private fun sendMessage(message: JSONObject) {
        if (webSocket?.isOpen == true) {
            webSocket?.send(message.toString())
        }
    }

    fun destroy() {
        webSocket?.close()
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
