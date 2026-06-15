package UI;

import audio.SoundPlayer;
import domain.Ball;
import domain.Door;
import domain.LevelConfig;
import entity.MonsterEntity;
import network.NetworkClient;
import world.MazeGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GameState {

    public LevelConfig         config;
    public int[][]             map;
    public Door                door;
    public final List<Ball>          balls    = new ArrayList<>();
    public final List<MonsterEntity> monsters = new ArrayList<>();

    public long    remainingTimeMillis;
    public boolean gameOverMenu       = false;
    public boolean victory            = false;
    public boolean levelComplete      = false;
    public boolean remotePlayerLeft   = false;
    public int     selectedMenuOption = 0;
    public String[] menuOptions       = {"Restart", "Quit"};
    public boolean paused             = false;
    public int     pauseMenuSelected  = 0;
    public String[] pauseMenuOptions  = {"Resume", "Back to Menu"};
    public double  floatPhase         = 0;

    public int exitTileX;
    public int exitTileY;
    public static final int START_TILE_X = 1;
    public static final int START_TILE_Y = 1;

    private int     currentLevelIndex;
    private final Random random = new Random();
    // Singleplayer Constructor
    public GameState(int levelIndex) {
        loadLevel(levelIndex);
    }

    // Multiplayer Constructor
    public GameState(NetworkClient client, int levelIndex) {
        currentLevelIndex  = client.levelIdx;
        config             = LevelConfig.ALL[currentLevelIndex];
        
        // Use the map size specified by the server
        exitTileX          = client.mapSize - 1;
        exitTileY          = client.mapSize - 1;

        levelComplete      = false;
        gameOverMenu       = false;
        victory            = false;
        selectedMenuOption = 0;

        // 1. Load the exact map layout sent by the server
        this.map = client.serverMap;
        this.door = new Door(exitTileX, exitTileY);

        // 2. Spawn dummy monsters. Their logic won't run locally; 
        // GamePanel.updateMultiplayer() will override their X/Y every frame.
        monsters.clear();
        for (int i = 0; i < config.monsterCount; i++) {
            monsters.add(new MonsterEntity(0, 0, GamePanel.TILE_SIZE));
        }

        // 3. Load the exact ball locations sent by the server
        balls.clear();
        for (double[] b : client.serverBalls) {
            balls.add(new Ball(b[0], b[1]));
        }

        remainingTimeMillis = config.timeLimitMillis;
    }

    public void loadLevel(int index) {
        currentLevelIndex  = index;
        config             = LevelConfig.ALL[index];
        exitTileX          = config.mapSize - 1;
        exitTileY          = config.mapSize - 1;

        levelComplete      = false;
        gameOverMenu       = false;
        victory            = false;
        selectedMenuOption = 0;

        generateRandomMap();
        door = new Door(exitTileX, exitTileY);
        spawnMonsters(config.monsterCount);
        spawnBalls(config.objectiveCount);

        remainingTimeMillis = config.timeLimitMillis;
    }

    public int getCurrentLevelIndex() { return currentLevelIndex; }

    public boolean collectBalls(double playerX, double playerY) {
        double  pickupRadius = 18;
        boolean collected    = false;
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b = balls.get(i);
            double dx = playerX - b.getX(), dy = playerY - b.getY();
            if (dx * dx + dy * dy <= pickupRadius * pickupRadius) {
                balls.remove(i);
                collected = true;
            }
        }
        if (collected) {
            SoundPlayer.playDing();
            if (balls.isEmpty()) door.open();
        }
        return collected;
    }

    public boolean checkExit(double playerX, double playerY) {
        return door.isOpen()
            && (int) playerX / GamePanel.TILE_SIZE == exitTileX - 1
            && (int) playerY / GamePanel.TILE_SIZE == exitTileY;
    }

    public int getMapTile(int mapX, int mapY) {
        if (door.isAt(mapX, mapY)) return door.getMapValue();
        return map[mapY][mapX];
    }

    public boolean isWallTile(int mapX, int mapY) {
        if (mapX < 0 || mapX >= config.mapSize || mapY < 0 || mapY >= config.mapSize) return true;
        if (door.isAt(mapX, mapY)) return !door.isOpen();
        return getMapTile(mapX, mapY) != 0;
    }

    public boolean isExitOpen() { return door.isOpen(); }

    // ── private helpers ───────────────────────────────────────────────────

    private void spawnMonsters(int count) {
        monsters.clear();
        List<int[]> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] pos = findEmptyMonsterSpawn(taken);
            taken.add(pos);
            monsters.add(new MonsterEntity(pos[0], pos[1], GamePanel.TILE_SIZE));
        }
    }

    private void spawnBalls(int count) {
        int mapSize = config.mapSize;
        balls.clear();
        while (balls.size() < count) {
            int tx = 1 + random.nextInt(mapSize - 2);
            int ty = 1 + random.nextInt(mapSize - 2);
            if (map[ty][tx] != 0) continue;
            if ((tx == START_TILE_X && ty == START_TILE_Y)
                    || (tx == exitTileX && ty == exitTileY)) continue;
            boolean occupied = balls.stream().anyMatch(
                b -> (int)(b.getX() / GamePanel.TILE_SIZE) == tx
                  && (int)(b.getY() / GamePanel.TILE_SIZE) == ty);
            if (occupied) continue;
            balls.add(new Ball(
                tx * GamePanel.TILE_SIZE + GamePanel.TILE_SIZE / 2.0,
                ty * GamePanel.TILE_SIZE + GamePanel.TILE_SIZE / 2.0));
        }
    }

    private void generateRandomMap() {
        map = MazeGenerator.generateMaze(config.mapSize,
                START_TILE_X, START_TILE_Y, exitTileX - 1, exitTileY);
        map[START_TILE_Y][START_TILE_X]     = 0;
        map[START_TILE_Y][START_TILE_X + 1] = 0;
        map[START_TILE_Y + 1][START_TILE_X] = 0;
        map[exitTileY][exitTileX - 1]       = 0;
    }

    private int[] findEmptyMonsterSpawn(List<int[]> taken) {
        int mapSize = config.mapSize, sx, sy;
        do {
            sx = 1 + random.nextInt(mapSize - 2);
            sy = 1 + random.nextInt(mapSize - 2);
            final int fx = sx, fy = sy;
            if (map[fy][fx] != 0) continue;
            if (fx == START_TILE_X && fy == START_TILE_Y) continue;
            if (fx == exitTileX   && fy == exitTileY)     continue;
            if (taken.stream().anyMatch(p -> p[0] == fx && p[1] == fy)) continue;
            break;
        } while (true);
        return new int[]{sx, sy};
    }
}