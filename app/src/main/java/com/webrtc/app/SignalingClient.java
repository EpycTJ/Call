package com.webrtc.app;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private WebSocketClient webSocket;
    private final String url;
    private final Callback callback;

    public interface Callback {
        void onOfferReceived(JSONObject data);
        void onAnswerReceived(JSONObject data);
        void onIceCandidateReceived(JSONObject data);
    }

    public SignalingClient(String url, Callback callback) {
        this.url = url;
        this.callback = callback;
    }

    public void connect() {
        try {
            URI uri = new URI(url);
            webSocket = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket connection opened");
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JSONObject jsonMessage = new JSONObject(message);
                        String type = jsonMessage.getString("type");
                        switch (type) {
                            case "offer":
                                callback.onOfferReceived(jsonMessage);
                                break;
                            case "answer":
                                callback.onAnswerReceived(jsonMessage);
                                break;
                            case "candidate":
                                callback.onIceCandidateReceived(jsonMessage);
                                break;
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing message", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "WebSocket connection closed: " + reason);
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error", ex);
                }
            };
            webSocket.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid WebSocket URL", e);
        }
    }

    public void sendOffer(String sdp) {
        sendMessage(createJsonMessage("offer", sdp));
    }

    public void sendAnswer(String sdp) {
        sendMessage(createJsonMessage("answer", sdp));
    }

    public void sendIceCandidate(String sdpMid, int sdpMLineIndex, String sdp) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "candidate");
            message.put("sdpMid", sdpMid);
            message.put("sdpMLineIndex", sdpMLineIndex);
            message.put("candidate", sdp);
            sendMessage(message);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating ice candidate message", e);
        }
    }

    private JSONObject createJsonMessage(String type, String sdp) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", type);
            message.put("sdp", sdp);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating message", e);
        }
        return message;
    }

    private void sendMessage(JSONObject message) {
        if (webSocket != null && webSocket.isOpen()) {
            webSocket.send(message.toString());
        }
    }

    public void destroy() {
        if (webSocket != null) {
            webSocket.close();
        }
    }
}
