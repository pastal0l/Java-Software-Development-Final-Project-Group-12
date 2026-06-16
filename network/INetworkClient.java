package network;

import java.io.IOException;
import java.util.List;
import java.util.Queue;

public interface INetworkClient {
    int getPlayerId();
    int getLevelIdx();
    int[][] getServerMap();
    long getServerTimeMs();
    List<double[]> getServerBalls();
    boolean isServerDoorOpen();
    boolean isGameOver();
    boolean isGameWon();
    boolean isRemotePlayerLeft();
    double getRemotePlayerX();
    double getRemotePlayerY();
    double getRemotePlayerAngle();
    double[] getMonsterX();
    double[] getMonsterY();
    boolean[] getMonsterChasing();
    double[] getMonsterAngle();
    Queue<int[]> getDiamondsTaken();
    void sendPosition(double x, double y, double angle);
    void disconnect();
    int getMapSize();
    void connect(String host, int port, Runnable onWaiting) throws IOException;

    /** True once the server has sent NEXT_LEVEL + full map data + START_NEXT. */
    boolean isNextLevelReady();

    /** Called by GamePanel after it has consumed the next-level data; resets the flag. */
    void clearNextLevel();

    /** True while the server is showing the between-level intermission (LEVEL_COMPLETE). */
    boolean isLevelCompleteScreen();

    /** Tell the server this player is ready to continue to the next level. */
    void sendReady();

    /** Tell the server this player wants to quit; ends the session for both. */
    void sendQuitLevel();
}
