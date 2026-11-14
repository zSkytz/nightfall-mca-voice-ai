package com.nightfall.voice.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM utilities: SVC mono 48kHz <-> OpenAI raw PCM 24kHz. No external codec needed. */
public final class AudioWav {
    private AudioWav() { }
    public static byte[] wav48k(short[] audio) {
        int dataSize = audio.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36 + dataSize).put(new byte[]{'W','A','V','E'});
        b.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1);
        b.putInt(48000).putInt(96000).putShort((short)2).putShort((short)16);
        b.put(new byte[]{'d','a','t','a'}).putInt(dataSize);
        for (short v : audio) b.putShort(v);
        return b.array();
    }
    /** OpenAI TTS raw PCM is 24,000 samples/s, signed 16-bit little-endian. */
    public static short[] pcm24to48(byte[] raw) {
        int count = raw.length / 2;
        short[] result = new short[count * 2];
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            short current = b.getShort();
            result[i*2] = current;
            short next = i + 1 < count ? b.getShort(b.position()) : current;
            result[i*2 + 1] = (short)((current + (int)next) / 2);
        }
        return result;
    }
    public static double rms(short[] audio) {
        if (audio.length == 0) return 0;
        double sum=0;
        for (short v : audio) sum += (double)v*v;
        return Math.sqrt(sum / audio.length);
    }
}
