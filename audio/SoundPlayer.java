package audio;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

public class SoundPlayer {
    private static final float SAMPLE_RATE = 44100f;
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final File MONSTER_SOUND_FILE = new File("asset/monster sound.wav");
    private static final File FOOTSTEP_SOUND_FILE = new File("asset/walking sound.wav");
    private static final byte[] DING_BUFFER = createDingBuffer();
    private static Clip dingClip;
    private static Clip monsterClip;
    private static Clip footstepClip;
    private static FloatControl monsterGainControl;
    private static FloatControl monsterPanControl;
    private static FloatControl footstepGainControl;

    static {
        initializeClip();
        initializeMonsterSound();
        initializeFootstepSound();
    }

    private static void initializeClip() {
        try {
            dingClip = AudioSystem.getClip();
            try (AudioInputStream stream = new AudioInputStream(
                    new ByteArrayInputStream(DING_BUFFER), AUDIO_FORMAT, DING_BUFFER.length / 2)) {
                dingClip.open(stream);
            }
        } catch (Exception e) {
            dingClip = null;
        }
    }

    public static void playDing() {
        new Thread(() -> {
            if (dingClip != null) {
                synchronized (dingClip) {
                    if (dingClip.isRunning()) {
                        dingClip.stop();
                    }
                    dingClip.setFramePosition(0);
                    dingClip.start();
                }
                return;
            }
            playFallbackDing();
        }, "SoundPlayer-Ding").start();
    }

    private static void playFallbackDing() {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT)) {
            line.open(AUDIO_FORMAT);
            line.start();
            line.write(DING_BUFFER, 0, DING_BUFFER.length);
            line.drain();
        } catch (LineUnavailableException e) {
            // Sound playback failed; ignore so the game still works.
        }
    }

    public static void playVictoryMusic() {
        new Thread(SoundPlayer::playVictoryFanfare, "SoundPlayer-Victory").start();
    }

    public static void playFootstep() {
        if (footstepClip == null) {
            return;
        }
        new Thread(() -> {
            synchronized (footstepClip) {
                if (footstepClip.isRunning()) {
                    footstepClip.stop();
                }
                footstepClip.setFramePosition(0);
                footstepClip.start();
            }
        }, "SoundPlayer-Footstep").start();
    }

    public static void updateMonsterSound(double pan, double volume) {
        if (monsterClip == null) {
            return;
        }
        if (!monsterClip.isRunning()) {
            monsterClip.loop(Clip.LOOP_CONTINUOUSLY);
            monsterClip.start();
        }
        setMonsterPan(pan);
        setMonsterVolume(volume);
    }

    public static void stopMonsterSound() {
        if (monsterClip == null) {
            return;
        }
        setMonsterVolume(0.0);
    }

    private static void setMonsterVolume(double volume) {
        if (monsterGainControl != null) {
            double min = monsterGainControl.getMinimum();
            double max = monsterGainControl.getMaximum();
            double gain = volume <= 0.0001 ? min : 20.0 * Math.log10(volume);
            gain = Math.max(min, Math.min(max, gain));
            monsterGainControl.setValue((float) gain);
        } else if (monsterClip != null) {
            if (volume <= 0.01 && monsterClip.isRunning()) {
                monsterClip.stop();
            } else if (volume > 0.01 && !monsterClip.isRunning()) {
                monsterClip.loop(Clip.LOOP_CONTINUOUSLY);
                monsterClip.start();
            }
        }
    }

    private static void setMonsterPan(double pan) {
        if (monsterPanControl != null) {
            double clamped = Math.max(-1.0, Math.min(1.0, pan));
            monsterPanControl.setValue((float) clamped);
        }
    }

    private static void playVictoryFanfare() {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT)) {
            line.open(AUDIO_FORMAT);
            line.start();
            double[] melody = {523.25, 659.25, 783.99, 1046.50, 783.99, 659.25, 523.25};
            int[] durations = {220, 220, 220, 360, 220, 220, 360};
            for (int i = 0; i < melody.length; i++) {
                byte[] buffer = createToneBuffer(melody[i], durations[i], 0.8);
                line.write(buffer, 0, buffer.length);
            }
            line.drain();
        } catch (LineUnavailableException e) {
            // Ignore sound errors so the game still runs.
        }
    }

    private static void initializeMonsterSound() {
        if (!MONSTER_SOUND_FILE.exists()) {
            return;
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(MONSTER_SOUND_FILE)) {
            monsterClip = AudioSystem.getClip();
            monsterClip.open(stream);
            if (monsterClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                monsterGainControl = (FloatControl) monsterClip.getControl(FloatControl.Type.MASTER_GAIN);
            }
            if (monsterClip.isControlSupported(FloatControl.Type.PAN)) {
                monsterPanControl = (FloatControl) monsterClip.getControl(FloatControl.Type.PAN);
            }
            setMonsterVolume(0.0);
            setMonsterPan(0.0);
            monsterClip.loop(Clip.LOOP_CONTINUOUSLY);
        } catch (LineUnavailableException | IOException | UnsupportedAudioFileException e) {
            monsterClip = null;
            monsterGainControl = null;
            monsterPanControl = null;
        }
    }

    private static void initializeFootstepSound() {
        if (!FOOTSTEP_SOUND_FILE.exists()) {
            return;
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(FOOTSTEP_SOUND_FILE)) {
            footstepClip = AudioSystem.getClip();
            footstepClip.open(stream);
            if (footstepClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                footstepGainControl = (FloatControl) footstepClip.getControl(FloatControl.Type.MASTER_GAIN);
                setFootstepVolume(0.52);
            }
        } catch (LineUnavailableException | IOException | UnsupportedAudioFileException e) {
            footstepClip = null;
            footstepGainControl = null;
        }
    }

    private static void setFootstepVolume(double volume) {
        if (footstepGainControl != null) {
            double min = footstepGainControl.getMinimum();
            double max = footstepGainControl.getMaximum();
            double gain = volume <= 0.0001 ? min : 20.0 * Math.log10(volume);
            gain = Math.max(min, Math.min(max, gain));
            footstepGainControl.setValue((float) gain);
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
