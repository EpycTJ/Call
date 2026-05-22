package com.webrtc.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodecModifier {
    private static final String AUDIO_CODEC_PARAM_OPUS = "opus";
    private static final String OPUS_MAXPLAYBACKRATE = "maxplaybackrate=48000";
    private static final String OPUS_STEREO = "stereo=1";
    private static final String OPUS_USEINBANDFEC = "useinbandfec=1"; // Forward Error Correction for lower latency/drops

    public static String preferOpus(String sdpDescription) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String opusRtpMap = null;
        String opusPayloadType = null;

        // Find m=audio line and opus rtpmap
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("m=audio ")) {
                mLineIndex = i;
            } else if (lines[i].startsWith("a=rtpmap:") && lines[i].contains(AUDIO_CODEC_PARAM_OPUS)) {
                opusRtpMap = lines[i];
                Pattern pattern = Pattern.compile("^a=rtpmap:(\\d+) " + AUDIO_CODEC_PARAM_OPUS + "(/\\d+)+[\r]?$");
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.matches()) {
                    opusPayloadType = matcher.group(1);
                }
            }
        }

        if (mLineIndex == -1 || opusPayloadType == null) {
            return sdpDescription; // Audio or Opus not found
        }

        // Reorder m=audio line to prefer opus
        String[] mLineParts = lines[mLineIndex].split(" ");
        if (mLineParts.length > 3) {
            StringBuilder newMLine = new StringBuilder(mLineParts[0] + " " + mLineParts[1] + " " + mLineParts[2]);
            newMLine.append(" ").append(opusPayloadType);
            for (int i = 3; i < mLineParts.length; i++) {
                if (!mLineParts[i].equals(opusPayloadType)) {
                    newMLine.append(" ").append(mLineParts[i]);
                }
            }
            lines[mLineIndex] = newMLine.toString();
        }

        // Add advanced Opus parameters for low latency and high quality
        StringBuilder sdpBuilder = new StringBuilder();
        for (String line : lines) {
            sdpBuilder.append(line).append("\r\n");
            if (line.equals(opusRtpMap)) {
                // Add format parameters immediately after rtpmap
                sdpBuilder.append("a=fmtp:").append(opusPayloadType).append(" ")
                          .append(OPUS_MAXPLAYBACKRATE).append("; ")
                          .append(OPUS_STEREO).append("; ")
                          .append(OPUS_USEINBANDFEC).append("\r\n");
            }
        }

        return sdpBuilder.toString();
    }
}
