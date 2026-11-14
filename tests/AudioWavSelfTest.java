import com.nightfall.voice.audio.AudioWav;
import java.util.Arrays;
public class AudioWavSelfTest {
    public static void main(String[] args) {
        short[] voice = {10, -10, Short.MAX_VALUE, Short.MIN_VALUE};
        byte[] wav = AudioWav.wav48k(voice);
        if (wav.length != 52 || wav[0] != 'R' || wav[1] != 'I') throw new AssertionError("WAV cabecalho invalido");
        byte[] raw = { 0, 0, 0, 64, 0, (byte)128, (byte)255, 127};
        short[] played = AudioWav.pcm24to48(raw);
        if (played.length != 8 || played[0] != 0 || played[2] != 16384) throw new AssertionError("Resample invalido: " + Arrays.toString(played));
        if (AudioWav.rms(new short[]{0,0}) != 0) throw new AssertionError("RMS invalido");
        System.out.println("PASS: WAV 48 kHz, resample PCM 24->48 kHz, RMS");
    }
}
