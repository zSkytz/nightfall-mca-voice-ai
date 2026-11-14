import com.nightfall.voice.audio.MicBuffer;
public class MicBufferSelfTest {
  public static void main(String[] args) throws Exception {
    com.nightfall.voice.config.VoiceConfig cfg = new com.nightfall.voice.config.VoiceConfig();
    cfg.voiceThresholdRms=100;
    cfg.minSpeechMs=10;
    cfg.speechSilenceMs=20;
    cfg.cooldownMs=1;
    MicBuffer buffer=new MicBuffer(cfg);
    short[] loud=new short[960];
    java.util.Arrays.fill(loud,(short)1600);
    buffer.accept(loud);
    Thread.sleep(30);
    byte[] wav=buffer.poll();
    if (wav==null || wav.length<=44) throw new AssertionError("No WAV after silence");
    System.out.println("PASS: segmentation / WAV on silence (synthetic PCM)");
  }
}
