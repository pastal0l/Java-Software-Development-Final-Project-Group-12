package audio;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.*;

public class SoundPlayer implements ISoundPlayer {

    private static final float       SAMPLE_RATE        = 44100f;
    private static final AudioFormat AUDIO_FORMAT       = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final double      VOLUME             = 2;
    private static final File        MONSTER_SOUND_FILE = new File("asset/monster sound.wav");
    private static final File        FOOTSTEP_SOUND_FILE= new File("asset/walking sound.wav");

    // ── Instance fields (were static) ─────────────────────────────────────
    private final byte[]  dingBuffer;
    private Clip          dingClip;
    private Clip          monsterClip;
    private Clip          footstepClip;
    private FloatControl  monsterGainControl;
    private FloatControl  monsterPanControl;
    private FloatControl  footstepGainControl;

    public SoundPlayer() {
        dingBuffer = createDingBuffer();
        initializeClip();
        initializeMonsterSound();
        initializeFootstepSound();
    }

    // ── ISoundPlayer implementation ───────────────────────────────────────

    @Override
    public void playDing() {
        new Thread(() -> {
            if (dingClip != null) {
                synchronized (dingClip) {
                    if (dingClip.isRunning()) dingClip.stop();
                    dingClip.setFramePosition(0);
                    dingClip.start();
                }
                return;
            }
            playFallbackDing();
        }, "SoundPlayer-Ding").start();
    }

    @Override
    public void playVictoryMusic() {
        new Thread(this::playVictoryFanfare, "SoundPlayer-Victory").start();
    }

    @Override
    public void playFootstep() {
        if (footstepClip == null) return;
        new Thread(() -> {
            synchronized (footstepClip) {
                if (footstepClip.isRunning()) footstepClip.stop();
                footstepClip.setFramePosition(0);
                footstepClip.start();
            }
        }, "SoundPlayer-Footstep").start();
    }

    @Override
    public void updateMonsterSound(double pan, double volume) {
        if (monsterClip == null) return;
        if (!monsterClip.isRunning()) {
            monsterClip.loop(Clip.LOOP_CONTINUOUSLY);
            monsterClip.start();
        }
        setMonsterPan(pan);
        setMonsterVolume(volume);
    }

    @Override
    public void stopMonsterSound() {
        if (monsterClip == null) return;
        setMonsterVolume(0.0);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void initializeClip() {
        try {
            dingClip = AudioSystem.getClip();
            try (AudioInputStream stream = new AudioInputStream(
                    new ByteArrayInputStream(dingBuffer), AUDIO_FORMAT, dingBuffer.length / 2)) {
                dingClip.open(stream);
            }
        } catch (Exception e) {
            dingClip = null;
        }
    }

    private void playFallbackDing() {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT)) {
            line.open(AUDIO_FORMAT);
            line.start();
            line.write(dingBuffer, 0, dingBuffer.length);
            line.drain();
        } catch (LineUnavailableException e) {
            // ignore — game continues without sound
        }
    }

    private void playVictoryFanfare() {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT)) {
            line.open(AUDIO_FORMAT);
            line.start();
            double[] melody    = {523.25, 659.25, 783.99, 1046.50, 783.99, 659.25, 523.25};
            int[]    durations = {220, 220, 220, 360, 220, 220, 360};
            for (int i = 0; i < melody.length; i++)
                line.write(createToneBuffer(melody[i], durations[i], 0.8), 0,
                           createToneBuffer(melody[i], durations[i], 0.8).length);
            line.drain();
        } catch (LineUnavailableException e) {
            // ignore
        }
    }

    private void initializeMonsterSound() {
        if (!MONSTER_SOUND_FILE.exists()) return;
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(MONSTER_SOUND_FILE)) {
            monsterClip = AudioSystem.getClip();
            monsterClip.open(stream);
            if (monsterClip.isControlSupported(FloatControl.Type.MASTER_GAIN))
                monsterGainControl = (FloatControl) monsterClip.getControl(FloatControl.Type.MASTER_GAIN);
            if (monsterClip.isControlSupported(FloatControl.Type.PAN))
                monsterPanControl = (FloatControl) monsterClip.getControl(FloatControl.Type.PAN);
            setMonsterVolume(0.0);
            setMonsterPan(0.0);
            monsterClip.loop(Clip.LOOP_CONTINUOUSLY);
        } catch (LineUnavailableException | IOException | UnsupportedAudioFileException e) {
            monsterClip = null;
            monsterGainControl = null;
            monsterPanControl = null;
        }
    }

    private void initializeFootstepSound() {
        if (!FOOTSTEP_SOUND_FILE.exists()) return;
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

    private void setMonsterVolume(double volume) {
        if (monsterGainControl != null) {
            double min  = monsterGainControl.getMinimum();
            double max  = monsterGainControl.getMaximum();
            double gain = volume <= 0.0001 ? min : 20.0 * Math.log10(volume);
            monsterGainControl.setValue((float) Math.max(min, Math.min(max, gain)));
        } else if (monsterClip != null) {
            if (volume <= 0.01 && monsterClip.isRunning())  monsterClip.stop();
            else if (volume > 0.01 && !monsterClip.isRunning()) {
                monsterClip.loop(Clip.LOOP_CONTINUOUSLY);
                monsterClip.start();
            }
        }
    }

    private void setMonsterPan(double pan) {
        if (monsterPanControl != null)
            monsterPanControl.setValue((float) Math.max(-1.0, Math.min(1.0, pan)));
    }

    private void setFootstepVolume(double volume) {
        if (footstepGainControl != null) {
            double min  = footstepGainControl.getMinimum();
            double max  = footstepGainControl.getMaximum();
            double gain = volume <= 0.0001 ? min : 20.0 * Math.log10(volume);
            footstepGainControl.setValue((float) Math.max(min, Math.min(max, gain)));
        }
    }

    private byte[] createDingBuffer() {
        int    sampleCount = (int) (SAMPLE_RATE * 280 / 1000);
        byte[] buffer      = new byte[sampleCount * 2];
        double baseFreq    = 880.0;
        for (int i = 0; i < sampleCount; i++) {
            double progress     = (double) i / sampleCount;
            double bellEnvelope = Math.exp(-3.8 * progress) * Math.sin(Math.PI * progress);
            double sample       = Math.sin(2 * Math.PI * baseFreq       * i / SAMPLE_RATE) * 0.55
                                + Math.sin(2 * Math.PI * baseFreq * 1.95 * i / SAMPLE_RATE) * 0.30
                                + Math.sin(2 * Math.PI * baseFreq * 3.75 * i / SAMPLE_RATE) * 0.15;
            short s = (short) (sample * bellEnvelope * Short.MAX_VALUE * 0.6 * VOLUME);
            buffer[2 * i]     = (byte) (s & 0xFF);
            buffer[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return buffer;
    }

    private byte[] createToneBuffer(double frequency, int durationMs, double volume) {
        int    sampleCount = (int) (SAMPLE_RATE * durationMs / 1000);
        byte[] buffer      = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            double progress = (double) i / sampleCount;
            double envelope = Math.sin(Math.PI * progress) * Math.exp(-3.0 * progress);
            double sample   = Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE) * 0.75
                            + Math.sin(4 * Math.PI * frequency * i / SAMPLE_RATE) * 0.15
                            + Math.sin(6 * Math.PI * frequency * i / SAMPLE_RATE) * 0.10;
            short s = (short) (sample * envelope * Short.MAX_VALUE * volume * VOLUME * 0.45);
            buffer[2 * i]     = (byte) (s & 0xFF);
            buffer[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return buffer;
    }
}