package com.webrtc.app

import java.util.regex.Pattern

object CodecModifier {
    private const val AUDIO_CODEC_PARAM_OPUS = "opus"
    private const val OPUS_MAXPLAYBACKRATE = "maxplaybackrate=48000"
    private const val OPUS_STEREO = "stereo=1"
    private const val OPUS_USEINBANDFEC = "useinbandfec=1" // Forward Error Correction for lower latency/drops

    fun preferOpus(sdpDescription: String): String {
        val lines = sdpDescription.split("\r\n".toRegex()).toTypedArray()
        var mLineIndex = -1
        var opusRtpMap: String? = null
        var opusPayloadType: String? = null

        // Find m=audio line and opus rtpmap
        for (i in lines.indices) {
            if (lines[i].startsWith("m=audio ")) {
                mLineIndex = i
            } else if (lines[i].startsWith("a=rtpmap:") && lines[i].contains(AUDIO_CODEC_PARAM_OPUS)) {
                opusRtpMap = lines[i]
                val pattern = Pattern.compile("^a=rtpmap:(\\d+) " + AUDIO_CODEC_PARAM_OPUS + "(/\\d+)+[\r]?$")
                val matcher = pattern.matcher(lines[i])
                if (matcher.matches()) {
                    opusPayloadType = matcher.group(1)
                }
            }
        }

        if (mLineIndex == -1 || opusPayloadType == null) {
            return sdpDescription // Audio or Opus not found
        }

        // Reorder m=audio line to prefer opus
        val mLineParts = lines[mLineIndex].split(" ".toRegex()).toTypedArray()
        if (mLineParts.size > 3) {
            val newMLine = StringBuilder(mLineParts[0] + " " + mLineParts[1] + " " + mLineParts[2])
            newMLine.append(" ").append(opusPayloadType)
            for (i in 3 until mLineParts.size) {
                if (mLineParts[i] != opusPayloadType) {
                    newMLine.append(" ").append(mLineParts[i])
                }
            }
            lines[mLineIndex] = newMLine.toString()
        }

        // Add advanced Opus parameters for low latency and high quality
        val sdpBuilder = StringBuilder()
        for (line in lines) {
            sdpBuilder.append(line).append("\r\n")
            if (line == opusRtpMap) {
                // Add format parameters immediately after rtpmap
                sdpBuilder.append("a=fmtp:").append(opusPayloadType).append(" ")
                    .append(OPUS_MAXPLAYBACKRATE).append("; ")
                    .append(OPUS_STEREO).append("; ")
                    .append(OPUS_USEINBANDFEC).append("\r\n")
            }
        }

        return sdpBuilder.toString()
    }
}
