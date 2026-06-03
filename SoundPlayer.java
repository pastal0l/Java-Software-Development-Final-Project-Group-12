import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public class SoundPlayer {
    private static final float SAMPLE_RATE = 44100f;
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final byte[] DING_BUFFER = createDingBuffer();
    private static SourceDataLine dingLine;

    static {
        try {
            dingLine = AudioSystem.getSourceDataLine(AUDIO_FORMAT);
            dingLine.open(AUDIO_FORMAT);
            dingLine.start();
        } catch (LineUnavailableException e) {
            dingLine = null;
        }
    }

    public static void playDing() {
        if (dingLine != null) {
            new Thread(() -> {
                synchronized (dingLine) {
                    dingLine.write(DING_BUFFER, 0, DING_BUFFER.length);
                    dingLine.drain();
                }
            }, "SoundPlayer-Ding").start();
            return;
        }
        new Thread(SoundPlayer::playDingTone, "SoundPlayer-Ding").start();
    }

    private static void playDingTone() {
        try {
            try (SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT)) {
                line.open(AUDIO_FORMAT);
                line.start();

                line.write(DING_BUFFER, 0, DING_BUFFER.length);
                line.drain();
            }
        } catch (LineUnavailableException e) {
            // Sound playback failed; ignore so the game still works.
        }
    }

    private static final double VOLUME = 2;

    private static byte[] createDingBuffer() {
        int durationMs = 280;
        int sampleCount = (int) (SAMPLE_RATE * durationMs / 1000);
        byte[] buffer = new byte[sampleCount * 2];

        double baseFreq = 880.0;
        double harmonic2 = 1.95;
        double harmonic3 = 3.75;

        for (int i = 0; i < sampleCount; i++) {
            double progress = (double) i / sampleCount;
            double decay = Math.exp(-3.8 * progress);
            double bellEnvelope = decay * Math.sin(Math.PI * progress);
            double angle1 = 2.0 * Math.PI * baseFreq * i / SAMPLE_RATE;
            double angle2 = 2.0 * Math.PI * baseFreq * harmonic2 * i / SAMPLE_RATE;
            double angle3 = 2.0 * Math.PI * baseFreq * harmonic3 * i / SAMPLE_RATE;
            double sampleValue = Math.sin(angle1) * 0.55 + Math.sin(angle2) * 0.30 + Math.sin(angle3) * 0.15;
            short sample = (short) (sampleValue * bellEnvelope * Short.MAX_VALUE * 0.6 * VOLUME);
            buffer[2 * i] = (byte) (sample & 0xFF);
            buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }

    public static void playVictoryMusic() {
        new Thread(SoundPlayer::playVictoryFanfare, "SoundPlayer-Victory").start();
    }

    private static void playVictoryFanfare() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();

                double[] melody = {523.25, 659.25, 783.99, 1046.50, 783.99, 659.25, 523.25};
                int[] durations = {220, 220, 220, 360, 220, 220, 360};

                for (int i = 0; i < melody.length; i++) {
                    byte[] buffer = createToneBuffer(melody[i], durations[i], 0.8);
                    line.write(buffer, 0, buffer.length);
                }
                line.drain();
            }
        } catch (LineUnavailableException e) {
            // Ignore sound errors so the game still runs.
        }
    }

    private static byte[] createToneBuffer(double frequency, int durationMs, double volume) {
        int sampleCount = (int) (SAMPLE_RATE * durationMs / 1000);
        byte[] buffer = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            double progress = (double) i / sampleCount;
            double envelope = Math.sin(Math.PI * progress) * Math.exp(-3.0 * progress);
            double angle = 2.0 * Math.PI * frequency * i / SAMPLE_RATE;
            double sampleValue = Math.sin(angle) * 0.75 + Math.sin(2 * angle) * 0.15 + Math.sin(3 * angle) * 0.10;
            short sample = (short) (sampleValue * envelope * Short.MAX_VALUE * volume * VOLUME * 0.45);
            buffer[2 * i] = (byte) (sample & 0xFF);
            buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }
}
