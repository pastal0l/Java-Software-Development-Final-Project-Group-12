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
    Queue<int[]> getDiamondsTaken();
    void sendPosition(double x, double y, double angle);
    void disconnect();
    int getMapSize();
    void connect(String host, int port, Runnable onWaiting) throws IOException ;
}
