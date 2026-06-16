package domain;

import world.MazeGenerator;
import static domain.GameConstants.TILE_SIZE;


import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

public class GameLogic {

    private static final double PICKUP_RADIUS = 18.0;

    private LevelConfig config;
    private int[][]     map;
    private Door        door;

    private final List<Ball>          balls    = new ArrayList<>();
    private final List<MonsterEntity> monsters = new ArrayList<>();
    private final Random              random   = new Random();

    private long    remainingTimeMillis;
    private boolean doorOpen = false;

    // Callbacks so GameServer can broadcast without GameLogic touching sockets
    private Consumer<String>  broadcastFn;
    private Consumer<Boolean> onGameEnd;

    public GameLogic(Consumer<String> broadcastFn, Consumer<Boolean> onGameEnd) {
        this.broadcastFn = broadcastFn;
        this.onGameEnd   = onGameEnd;
    }

    // ── Level setup ───────────────────────────────────────────────────────

    public void loadLevel(int idx, double[][] playerPos) {
        config = LevelConfig.ALL[idx];
        int ms = config.mapSize;
        int startX = 1, startY = 1;
        int exitX = ms - 1, exitY = ms - 1;

        map = new MazeGenerator().generateMaze(ms, startX, startY, exitX - 1, exitY);
        map[startY][startX] = 0; map[startY][startX + 1] = 0;
        map[startY + 1][startX] = 0; map[exitY][exitX - 1] = 0;

        door = new Door(exitX, exitY);
        doorOpen = false;

        monsters.clear();
        List<int[]> taken = new ArrayList<>();
        for (int i = 0; i < config.monsterCount; i++) {
            int[] pos = findEmptySpawn(taken);
            taken.add(pos);
            monsters.add(new MonsterEntity(pos[0], pos[1], TILE_SIZE));
        }

        balls.clear();
        while (balls.size() < config.objectiveCount) {
            int tx = 1 + random.nextInt(ms - 2);
            int ty = 1 + random.nextInt(ms - 2);
            if (map[ty][tx] != 0) continue;
            if ((tx == startX && ty == startY) || (tx == exitX && ty == exitY)) continue;
            boolean dup = balls.stream().anyMatch(b ->
                (int)(b.getX() / TILE_SIZE) == tx &&
                (int)(b.getY() / TILE_SIZE) == ty);
            if (dup) continue;
            balls.add(new Ball(
                tx * TILE_SIZE + TILE_SIZE / 2.0,
                ty * TILE_SIZE + TILE_SIZE / 2.0));
        }

        remainingTimeMillis = config.timeLimitMillis;

        double px = startX * TILE_SIZE + TILE_SIZE / 2.0;
        double py = startY * TILE_SIZE + TILE_SIZE / 2.0;
        playerPos[0][0] = px;                          playerPos[0][1] = py;
        playerPos[0][2] = Math.toRadians(45);
        playerPos[1][0] = px + TILE_SIZE;    playerPos[1][1] = py;
        playerPos[1][2] = Math.toRadians(45);
    }

    // ── Per-tick update ───────────────────────────────────────────────────

    public void update(long dt, double[][] playerPos) {
        remainingTimeMillis -= dt;
        if (remainingTimeMillis <= 0) {
            remainingTimeMillis = 0;
            onGameEnd.accept(false);
            return;
        }

        // Monster AI
        for (MonsterEntity m : monsters) {
            double d0 = Math.hypot(playerPos[0][0] - m.getX(), playerPos[0][1] - m.getY());
            double d1 = Math.hypot(playerPos[1][0] - m.getX(), playerPos[1][1] - m.getY());
            double nearX = d0 < d1 ? playerPos[0][0] : playerPos[1][0];
            double nearY = d0 < d1 ? playerPos[0][1] : playerPos[1][1];
            m.update(nearX, nearY, map, TILE_SIZE);

            for (int p = 0; p < 2; p++) {
                if (m.collidesWithPlayer(playerPos[p][0], playerPos[p][1])) {
                    onGameEnd.accept(false);
                    return;
                }
            }
        }

        // Diamond collection
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b = balls.get(i);
            for (int p = 0; p < 2; p++) {
                double dx = playerPos[p][0] - b.getX();
                double dy = playerPos[p][1] - b.getY();
                if (dx * dx + dy * dy <= PICKUP_RADIUS * PICKUP_RADIUS) {
                    broadcastFn.accept("DIAMOND_TAKEN:" + (int)b.getX() + "," + (int)b.getY());
                    balls.remove(i);
                    if (balls.isEmpty()) {
                        door.open();
                        doorOpen = true;
                        broadcastFn.accept("DOOR_OPEN");
                    }
                    break;
                }
            }
        }

        // Exit check
        if (doorOpen) {
            for (int p = 0; p < 2; p++) {
                int tx = (int) playerPos[p][0] / TILE_SIZE;
                int ty = (int) playerPos[p][1] / TILE_SIZE;
                if (tx == config.mapSize - 2 && ty == config.mapSize - 1) {
                    onGameEnd.accept(true);
                    return;
                }
            }
        }
    }

    // ── Getters used by GameServer for state broadcast ────────────────────

    public int[][]              getMap()       { return map; }
    public List<Ball>           getBalls()     { return balls; }
    public List<MonsterEntity>  getMonsters()  { return monsters; }
    public long                 getRemainingTime() { return remainingTimeMillis; }
    public LevelConfig          getConfig()    { return config; }

    private int[] findEmptySpawn(List<int[]> taken) {
        int ms = config.mapSize, x, y;
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