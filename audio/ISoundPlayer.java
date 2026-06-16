package audio;

public interface ISoundPlayer {
    void playDing();
    void playFootstep();
    void playVictoryMusic();
    void updateMonsterSound(double pan, double volume);
    void stopMonsterSound();
}