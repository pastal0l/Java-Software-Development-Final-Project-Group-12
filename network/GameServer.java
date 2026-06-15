package network;

import java.io.*;
import java.net.*;

public class GameServer implements Runnable {

    public static final int PORT = 5555;

    private final double[][] playerPos = {{0,0,0},{0,0,0}};
    private final PrintWriter[] writers = new PrintWriter[2];

    private GameLogic logic;
    private boolean   gameActive = false;

    @Override public void run() {
        try { serve(); } catch (Exception e) {
            System.err.println("[Server] Fatal: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception { new GameServer().serve(); }

    void serve() throws IOException, InterruptedException {
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("[Server] Listening on port " + PORT);

        Socket s0 = ss.accept();
        writers[0] = writer(s0);
        writers[0].println("PLAYER_ID:0");
        writers[0].println("WAITING");

        Socket s1 = ss.accept();
        writers[1] = writer(s1);
        writers[1].println("PLAYER_ID:1");
        ss.close();

        // Wire GameLogic callbacks to network broadcast
        logic = new GameLogic(this::broadcast, won -> {
            broadcast("GAME_OVER:" + (won ? 1 : 0));
            gameActive = false;
        });

        logic.loadLevel(0, playerPos);
        pushLevelData();
        broadcast("START");
        gameActive = true;

        daemon(() -> readClient(s0, 0), "reader-0");
        daemon(() -> readClient(s1, 1), "reader-1");

        long lastTick = System.currentTimeMillis();
        while (gameActive) {
            long now = System.currentTimeMillis();
            long dt  = now - lastTick;
            lastTick = now;
            if (dt > 0) synchronized (this) { logic.update(dt, playerPos); }
            sendState();
            Thread.sleep(50);
        }
    }

    private void pushLevelData() {
        int ms = logic.getConfig().mapSize;
        broadcast("MAP_SIZE:" + ms);
        broadcast("LEVEL:0");

        int[][] map = logic.getMap();
        StringBuilder sb = new StringBuilder("MAP_DATA:");
        for (int y = 0; y < ms; y++)
            for (int x = 0; x < ms; x++) {
                if (y > 0 || x > 0) sb.append(',');
                sb.append(map[y][x]);
            }
        broadcast(sb.toString());

        sb = new StringBuilder("BALLS:");
        var balls = logic.getBalls();
        for (int i = 0; i < balls.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append((int)balls.get(i).getX()).append(':').append((int)balls.get(i).getY());
        }
        broadcast(sb.toString());
    }

    private synchronized void sendState() {
        StringBuilder sb = new StringBuilder("STATE:");
        for (int p = 0; p < 2; p++)
            sb.append(playerPos[p][0]).append(',')
              .append(playerPos[p][1]).append(',')
              .append(playerPos[p][2]).append(',');

        var monsters = logic.getMonsters();
        sb.append(monsters.size());
        for (var m : monsters)
            sb.append(',').append(m.getX())
              .append(',').append(m.getY())
              .append(',').append(m.isChasing() ? "1" : "0");

        sb.append(',').append(logic.getRemainingTime());
        broadcast(sb.toString());
    }

    private void readClient(Socket socket, int id) {
        try (var br = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("POS:")) {
                    String[] p = line.substring(4).split(",");
                    if (p.length >= 3) synchronized (this) {
                        playerPos[id][0] = Double.parseDouble(p[0]);
                        playerPos[id][1] = Double.parseDouble(p[1]);
                        playerPos[id][2] = Double.parseDouble(p[2]);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[Server] Player " + id + " disconnected.");
        }
        if (gameActive) { broadcast("PLAYER_LEFT:" + id); gameActive = false; }
    }

    private synchronized void broadcast(String msg) {
        for (PrintWriter w : writers) if (w != null) w.println(msg);
    }

    private static PrintWriter writer(Socket s) throws IOException {
        return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, "server-" + name);
        t.setDaemon(true);
        t.start();
    }
}