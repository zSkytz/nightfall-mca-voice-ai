package com.nightfall.voice.audio;

import com.nightfall.voice.config.VoiceConfig;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One object per consenting focused user. Packet data is decoded on voicechat thread. */
public final class MicBuffer {
    private final VoiceConfig config;
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private long started;
    private long lastLoud;
    private long lastFinalize;
    private boolean talking;

    public MicBuffer(VoiceConfig config) { this.config=config; }

    public synchronized void accept(short[] pcm) {
        long now = System.currentTimeMillis();
        boolean loud = AudioWav.rms(pcm) >= config.voiceThresholdRms;
        if (!talking && !loud) return;
        if (!talking) {
            if (now - lastFinalize < config.cooldownMs) return;
            talking=true;
            started=now;
            lastLoud=now;
            raw.reset();
        }
        if (loud) lastLoud=now;
        if (raw.size() + pcm.length*2 <= 48000 * 2 * 30) {
            ByteBuffer b = ByteBuffer.allocate(pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : pcm) b.putShort(s);
            raw.writeBytes(b.array());
        }
    }
    /** Returns a complete WAV utterance after a quiet period / maximum duration, else null. */
    public synchronized byte[] poll() {
        if (!talking) return null;
        long now=System.currentTimeMillis();
        if (now-lastLoud < config.speechSilenceMs && now-started < config.maxSpeechMs) return null;
        talking=false;
        lastFinalize=now;
        byte[] data=raw.toByteArray();
        raw.reset();
        if (now-started < config.minSpeechMs) return null;
        short[] samples=new short[data.length/2];
        ByteBuffer in=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i=0; i<samples.length; i++) samples[i]=in.getShort();
        return AudioWav.wav48k(samples);
    }
    public synchronized void clear() { talking=false; raw.reset(); }
}
