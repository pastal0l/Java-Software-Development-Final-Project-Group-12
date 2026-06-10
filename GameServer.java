import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Authoritative co-op game server.
 *
 * <p>Listens on {@link #PORT} for exactly two TCP clients, generates the maze,
 * runs the monster AI, manages diamonds / door / timer, and broadcasts world
 * state to both clients at 20 Hz.</p>
 *
 * <h3>Message protocol</h3>
 * <pre>
 * Server → Client (setup):
 *   PLAYER_ID:0          assigned slot
 *   WAITING              sent to player 0 while waiting for player 1
 *   MAP_SIZE:20
 *   LEVEL:0
 *   MAP_DATA:0,3,...     mapSize*mapSize comma-separated cell values
 *   BALLS:x0:y0;x1:y1;  world-space ball positions (semicolon-separated)
 *   START
 *
 * Server → Client (game):
 *   STATE:p0x,p0y,p0a,p1x,p1y,p1a,monCount,m0x,m0y,m0c,...,timeMs
 *   DIAMOND_TAKEN:wx,wy  world-space centre of collected ball
 *   DOOR_OPEN
 *   GAME_OVER:0|1        0 = defeat, 1 = victory
 *
 * Client → Server:
 *   POS:x,y,angle
 * </pre>
 */
public class GameServer implements Runnable {

    public static final int PORT = 5555;

    // -----------------------------------------------------------------------
    // Game state
    // -----------------------------------------------------------------------
    private LevelConfig config;
    private int         currentLevel = 0;
    private int[][]     map;
    private Door        door;
    private final List<Ball>    balls    = new ArrayList<>();
    private final List<Monster> monsters = new ArrayList<>();

    private long    remainingTimeMillis;
    private boolean gameActive = false;
    private boolean doorOpen   = false;

    /** [playerId][0=x, 1=y, 2=angle] — written by reader threads, read by game loop. */
    private final double[][] playerPos = {{0, 0, 0}, {0, 0, 0}};

    private final PrintWriter[] writers = new PrintWriter[2];
    private final Random random = new Random();

    // -----------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------

    /** Runnable entry — used when hosted in-process from {@link LobbyPanel}. */
    @Override
    public void run() {
        try {
            serve();
        } catch (Exception e) {
            System.err.println("[Server] Fatal: " + e.getMessage());
        }
    }

    /** Standalone server entry point. */
    public static void main(String[] args) throws Exception {
        new GameServer().serve();
    }

    // -----------------------------------------------------------------------
    // Core server logic
    // -----------------------------------------------------------------------

    void serve() throws IOException, InterruptedException {
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("[Server] Listening on port " + PORT);

        // ── Accept player 0 ────────────────────────────────────────────────
        Socket s0 = ss.accept();
        System.out.println("[Server] Player 0 connected from " + s0.getInetAddress());
        writers[0] = new PrintWriter(
            new OutputStreamWriter(s0.getOutputStream(), "UTF-8"), true);
        writers[0].println("PLAYER_ID:0");
        writers[0].println("WAITING");

        // ── Accept player 1 ────────────────────────────────────────────────
        Socket s1 = ss.accept();
        System.out.println("[Server] Player 1 connected from " + s1.getInetAddress());
        writers[1] = new PrintWriter(
            new OutputStreamWriter(s1.getOutputStream(), "UTF-8"), true);
        writers[1].println("PLAYER_ID:1");

        ss.close();   // stop accepting more clients

        // ── Load level and push map ────────────────────────────────────────
        loadLevel(0);
        pushLevelData();
        broadcast("START");
        gameActive = true;

        // ── Start reader threads ──────────────────────────────────────────
        Thread r0 = new Thread(() -> readClient(s0, 0), "server-reader-0");
        Thread r1 = new Thread(() -> readClient(s1, 1), "server-reader-1");
        r0.setDaemon(true);
        r1.setDaemon(true);
        r0.start();
        r1.start();

        // ── Game loop at 20 Hz ────────────────────────────────────────────
        long lastTick = System.currentTimeMillis();
        while (gameActive) {
            long now = System.currentTimeMillis();
            long dt  = now - lastTick;
            lastTick = now;
            if (dt > 0) updateGame(dt);
            sendState();
            Thread.sleep(50);
        }
        System.out.println("[Server] Game ended.");
    }

    // -----------------------------------------------------------------------
    // Level management
    // -----------------------------------------------------------------------

    private void loadLevel(int idx) {
        config       = LevelConfig.ALL[idx];
        currentLevel = idx;

        int ms     = config.mapSize;
        int startX = 1, startY = 1;
        int exitX  = ms - 1, exitY = ms - 1;

        map = MazeGenerator.generateMaze(ms, startX, startY, exitX - 1, exitY);
        map[startY][startX]     = 0;
        map[startY][startX + 1] = 0;
        map[startY + 1][startX] = 0;
        map[exitY][exitX - 1]   = 0;

        door     = new Door(exitX, exitY);
        doorOpen = false;

        // Monsters
        monsters.clear();
        List<int[]> taken = new ArrayList<>();
        for (int i = 0; i < config.monsterCount; i++) {
            int[] pos = findEmptySpawn(taken);
            taken.add(pos);
            monsters.add(new Monster(pos[0], pos[1], GamePanel.TILE_SIZE));
        }

        // Balls
        balls.clear();
        while (balls.size() < config.objectiveCount) {
            int tx = 1 + random.nextInt(ms - 2);
            int ty = 1 + random.nextInt(ms - 2);
            if (map[ty][tx] != 0) continue;
            if ((tx == startX && ty == startY) || (tx == exitX && ty == exitY)) continue;
            boolean dup = balls.stream().anyMatch(b ->
                (int) (b.x / GamePanel.TILE_SIZE) == tx &&
                (int) (b.y / GamePanel.TILE_SIZE) == ty);
            if (dup) continue;
            balls.add(new Ball(
                tx * GamePanel.TILE_SIZE + GamePanel.TILE_SIZE / 2.0,
                ty * GamePanel.TILE_SIZE + GamePanel.TILE_SIZE / 2.0));
        }

        remainingTimeMillis = config.timeLimitMillis;

        // Reset player starting positions
        double px = startX * GamePanel.TILE_SIZE + GamePanel.TILE_SIZE / 2.0;
        double py = startY * GamePanel.TILE_SIZE + GamePanel.TILE_SIZE / 2.0;
        synchronized (this) {
            playerPos[0][0] = px;           playerPos[0][1] = py;
            playerPos[0][2] = Math.toRadians(45);
            playerPos[1][0] = px + GamePanel.TILE_SIZE;
            playerPos[1][1] = py;
            playerPos[1][2] = Math.toRadians(45);
        }
    }

    private void pushLevelData() {
        int ms = config.mapSize;

        broadcast("MAP_SIZE:" + ms);
        broadcast("LEVEL:" + currentLevel);

        // Flat map array
        StringBuilder sb = new StringBuilder("MAP_DATA:");
        for (int y = 0; y < ms; y++) {
            for (int x = 0; x < ms; x++) {
                if (y > 0 || x > 0) sb.append(',');
                sb.append(map[y][x]);
            }
        }
        broadcast(sb.toString());

        // Ball world positions
        sb = new StringBuilder("BALLS:");
        for (int i = 0; i < balls.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append((int) balls.get(i).x).append(':').append((int) balls.get(i).y);
        }
        broadcast(sb.toString());
    }

    // -----------------------------------------------------------------------
    // Game update (20 Hz, called from game loop)
    // -----------------------------------------------------------------------

    private synchronized void updateGame(long dt) {
        if (!gameActive) return;

        // ── Timer ───────────────────────────────────────────────────────────
        remainingTimeMillis -= dt;
        if (remainingTimeMillis <= 0) {
            remainingTimeMillis = 0;
            endGame(false);
            return;
        }

        // ── Monster AI ──────────────────────────────────────────────────────
        for (Monster m : monsters) {
            double d0 = Math.hypot(playerPos[0][0] - m.getX(), playerPos[0][1] - m.getY());
            double d1 = Math.hypot(playerPos[1][0] - m.getX(), playerPos[1][1] - m.getY());
            double nearX = d0 < d1 ? playerPos[0][0] : playerPos[1][0];
            double nearY = d0 < d1 ? playerPos[0][1] : playerPos[1][1];
            m.update(nearX, nearY, map, GamePanel.TILE_SIZE);

            for (int p = 0; p < 2; p++) {
                if (m.collidesWithPlayer(playerPos[p][0], playerPos[p][1], GamePanel.TILE_SIZE)) {
                    endGame(false);
                    return;
                }
            }
        }

        // ── Diamond collection ───────────────────────────────────────────────
        double pickupR = 18;
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b = balls.get(i);
            boolean picked = false;
            for (int p = 0; p < 2; p++) {
                double dx = playerPos[p][0] - b.x;
                double dy = playerPos[p][1] - b.y;
                if (dx * dx + dy * dy <= pickupR * pickupR) { picked = true; break; }
            }
            if (picked) {
                broadcast("DIAMOND_TAKEN:" + (int) b.x + "," + (int) b.y);
                balls.remove(i);
                if (balls.isEmpty()) {
                    door.open();
                    doorOpen = true;
                    broadcast("DOOR_OPEN");
                }
            }
        }

        // ── Exit check ────────────────────────────────────────────────────────
        if (doorOpen) {
            for (int p = 0; p < 2; p++) {
                int tx = (int) playerPos[p][0] / GamePanel.TILE_SIZE;
                int ty = (int) playerPos[p][1] / GamePanel.TILE_SIZE;
                if (tx == config.mapSize - 2 && ty == config.mapSize - 1) {
                    endGame(true);
                    return;
                }
            }
        }
    }

    private void endGame(boolean won) {
        broadcast("GAME_OVER:" + (won ? 1 : 0));
        gameActive = false;
    }

    // -----------------------------------------------------------------------
    // State broadcast
    // -----------------------------------------------------------------------

    private synchronized void sendState() {
        StringBuilder sb = new StringBuilder("STATE:");
        for (int p = 0; p < 2; p++) {
            sb.append(playerPos[p][0]).append(',')
              .append(playerPos[p][1]).append(',')
              .append(playerPos[p][2]).append(',');
        }
        sb.append(monsters.size());
        for (Monster m : monsters) {
            sb.append(',').append(m.getX())
              .append(',').append(m.getY())
              .append(',').append(m.isChasing() ? "1" : "0");
        }
        sb.append(',').append(remainingTimeMillis);
        broadcast(sb.toString());
    }

    // -----------------------------------------------------------------------
    // Client reader thread
    // -----------------------------------------------------------------------

    private void readClient(Socket socket, int playerId) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("POS:")) {
                    String[] p = line.substring(4).split(",");
                    if (p.length >= 3) {
                        synchronized (this) {
                            playerPos[playerId][0] = Double.parseDouble(p[0]);
                            playerPos[playerId][1] = Double.parseDouble(p[1]);
                            playerPos[playerId][2] = Double.parseDouble(p[2]);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[Server] Player " + playerId + " disconnected.");
        }
        if (gameActive) endGame(false);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private synchronized void broadcast(String msg) {
        for (PrintWriter w : writers) {
            if (w != null) w.println(msg);
        }
    }

    private int[] findEmptySpawn(List<int[]> taken) {
        int ms = config.mapSize;
        int x, y;
        do {
            x = 1 + random.nextInt(ms - 2);
            y = 1 + random.nextInt(ms - 2);
            if (map[y][x] != 0) continue;
            if (x == 1 && y == 1) continue;
            if (x == ms - 1 && y == ms - 1) continue;
            final int fx = x, fy = y;
            if (taken.stream().anyMatch(p -> p[0] == fx && p[1] == fy)) continue;
            break;
        } while (true);
        return new int[]{x, y};
    }
}
