package network;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Thin TCP client used by {@link GamePanel} in multiplayer mode.
 *
 * <p>Call {@link #connect(String, int, Runnable)} from a background thread —
 * it blocks until the server sends {@code START}, then returns so the caller
 * can build a {@link GamePanel} with the received map data.  A daemon reader
 * thread then handles all subsequent game-state messages.</p>
 *
 * <p>All fields that are written by the reader thread and read by the game
 * thread are {@code volatile}.  Diamond-taken events use a
 * {@link ConcurrentLinkedQueue} for safe cross-thread delivery.</p>
 */
public class NetworkClient implements INetworkClient {

    // -----------------------------------------------------------------------
    // Identity (set during setup)
    // -----------------------------------------------------------------------
    private volatile int    myPlayerId = -1;
    private volatile int    levelIdx   = 0;

    // Map data (set during setup, then immutable)
    private volatile int[][]      serverMap;
    public volatile int          mapSize;
    private volatile List<double[]> serverBalls = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Game state (written by reader thread, read on game thread)
    // -----------------------------------------------------------------------
    private volatile double  remotePlayerX;
    private volatile double  remotePlayerY;
    private volatile double  remotePlayerAngle;

    private volatile long    serverTimeMs;
    private volatile boolean serverDoorOpen = false;
    private volatile boolean gameOver          = false;
    private volatile boolean gameWon           = false;
    private volatile boolean remotePlayerLeft  = false;

    /** Per-monster positions and chase flag (length = monsterCount). */
    private volatile double[]  monsterX       = new double[0];
    private volatile double[]  monsterY       = new double[0];
    private volatile boolean[] monsterChasing = new boolean[0];

    /**
     * World coordinates of diamonds the server has confirmed as collected.
     * GamePanel polls this queue each frame.  Each entry is {@code int[]{wx,wy}}.
     */
    private final Queue<int[]> diamondsTaken = new ConcurrentLinkedQueue<>();

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------
    private Socket       socket;
    private PrintWriter  writer;
    private BufferedReader reader;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Connect to the server, perform the setup handshake (blocking), then
     * launch a daemon reader thread for ongoing game messages.
     *
     * @param host       server host-name or IP
     * @param port       TCP port
     * @param onWaiting  called (on the calling thread) when the server says
     *                   "WAITING" — lets the lobby update its status label;
     *                   may be {@code null}
     * @throws IOException if the connection or setup fails
     */
    @Override
    public void connect(String host, int port, Runnable onWaiting) throws IOException {
        socket = new Socket(host, port);
        writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
        readSetup(onWaiting);

        // Background thread reads ongoing STATE / event messages
        Thread t = new Thread(this::readLoop, "net-client-reader");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int getMapSize() { return mapSize; }
    
    @Override
    public int getPlayerId() { return myPlayerId; }

    @Override
    public int getLevelIdx() { return levelIdx; }

    @Override
    public int[][] getServerMap() { return serverMap; }

    @Override
    public List<double[]> getServerBalls() { return serverBalls; }

    @Override
    public long getServerTimeMs() { return serverTimeMs; }

    @Override
    public boolean isServerDoorOpen() { return serverDoorOpen; }

    @Override
    public boolean isGameOver() { return gameOver; }

    @Override
    public boolean isGameWon() { return gameWon; }

    @Override
    public boolean isRemotePlayerLeft() { return remotePlayerLeft; }

    @Override
    public double getRemotePlayerX() { return remotePlayerX; }

    @Override
    public double getRemotePlayerY() { return remotePlayerY; }

    @Override
    public double getRemotePlayerAngle() { return remotePlayerAngle; }

    @Override
    public double[] getMonsterX() { return monsterX; }

    @Override
    public double[] getMonsterY() { return monsterY; }

    @Override
    public boolean[] getMonsterChasing() { return monsterChasing; }

    @Override
    public Queue<int[]> getDiamondsTaken() { return diamondsTaken; }


    /** Send our current position to the server (called every game frame). */
    public void sendPosition(double x, double y, double angle) {
        if (writer != null) writer.println("POS:" + x + "," + y + "," + angle);
    }

    /** Cleanly close the socket. */
    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // -----------------------------------------------------------------------
    // Setup phase (blocking — run on a background thread)
    // -----------------------------------------------------------------------

    private void readSetup(Runnable onWaiting) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if        (line.startsWith("PLAYER_ID:")) {
                myPlayerId = Integer.parseInt(line.substring(10).trim());

            } else if (line.equals("WAITING")) {
                if (onWaiting != null) onWaiting.run();

            } else if (line.startsWith("MAP_SIZE:")) {
                mapSize = Integer.parseInt(line.substring(9).trim());

            } else if (line.startsWith("LEVEL:")) {
                levelIdx = Integer.parseInt(line.substring(6).trim());

            } else if (line.startsWith("MAP_DATA:")) {
                String[] parts = line.substring(9).split(",");
                int[][] m = new int[mapSize][mapSize];
                for (int i = 0; i < parts.length; i++) {
                    m[i / mapSize][i % mapSize] = Integer.parseInt(parts[i].trim());
                }
                serverMap = m;

            } else if (line.startsWith("BALLS:")) {
                List<double[]> list = new ArrayList<>();
                String payload = line.substring(6).trim();
                if (!payload.isEmpty()) {
                    for (String token : payload.split(";")) {
                        String[] xy = token.split(":");
                        list.add(new double[]{
                            Double.parseDouble(xy[0]),
                            Double.parseDouble(xy[1])
                        });
                    }
                }
                serverBalls = list;

            } else if (line.equals("START")) {
                return;   // setup complete
            }
        }
        throw new IOException("Server closed connection before START");
    }

    // -----------------------------------------------------------------------
    // Game phase (background reader thread)
    // -----------------------------------------------------------------------

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line);
            }
        } catch (IOException ignored) {
            // Socket closed — game handles this via gameOver flag timeout
        }
    }

    private void parseLine(String line) {
        if (line.startsWith("STATE:")) {
            parseState(line.substring(6));
        } else if (line.startsWith("DIAMOND_TAKEN:")) {
            String[] parts = line.substring(14).split(",");
            diamondsTaken.offer(new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim())
            });
        } else if (line.equals("DOOR_OPEN")) {
            serverDoorOpen = true;
        } else if (line.startsWith("PLAYER_LEFT:")) {
            remotePlayerLeft = true;
        } else if (line.startsWith("GAME_OVER:")) {
            gameWon  = line.endsWith(":1");
            gameOver = true;
        }
    }

    /**
     * STATE format:
     * {@code p0x,p0y,p0a,p1x,p1y,p1a,monCount,m0x,m0y,m0c[,...],timeMs}
     */
    private void parseState(String data) {
        try {
            String[] t = data.split(",");
            int i = 0;
            double p0x = Double.parseDouble(t[i++]);
            double p0y = Double.parseDouble(t[i++]);
            double p0a = Double.parseDouble(t[i++]);
            double p1x = Double.parseDouble(t[i++]);
            double p1y = Double.parseDouble(t[i++]);
            double p1a = Double.parseDouble(t[i++]);

            if (myPlayerId == 0) {
                remotePlayerX = p1x; remotePlayerY = p1y; remotePlayerAngle = p1a;
            } else {
                remotePlayerX = p0x; remotePlayerY = p0y; remotePlayerAngle = p0a;
            }

            int mc = Integer.parseInt(t[i++]);
            double[]  mx  = new double[mc];
            double[]  my  = new double[mc];
            boolean[] mch = new boolean[mc];
            for (int m = 0; m < mc; m++) {
                mx[m]  = Double.parseDouble(t[i++]);
                my[m]  = Double.parseDouble(t[i++]);
                mch[m] = t[i++].equals("1");
            }
            monsterX       = mx;
            monsterY       = my;
            monsterChasing = mch;

            serverTimeMs = Long.parseLong(t[i]);

        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
            // Malformed packet — skip silently
        }
    }
}
