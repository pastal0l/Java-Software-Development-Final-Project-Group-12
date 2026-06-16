package domain;

import audio.ISoundPlayer;
import network.INetworkClient;
import world.IMapGenerator;
import static domain.GameConstants.TILE_SIZE;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GameState {

    public LevelConfig         config;
    public int[][]             map;
    public Door                door;
    
    // POLYMORPHIC LIST: Can hold Balls, Medkits, SpeedBoosts, etc.
    public final List<Item> items = new ArrayList<>();
    public final List<MonsterEntity> monsters = new ArrayList<>();
    
    private final IMapGenerator mapGenerator;
    private final ISoundPlayer sound;
    
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
    public GameState(int levelIndex, IMapGenerator mapGenerator, ISoundPlayer sound) {
        this.mapGenerator = mapGenerator;
        this.sound = sound;
        loadLevel(levelIndex);
    }

    // Multiplayer Constructor
    public GameState(INetworkClient client, int levelIndex, IMapGenerator mapGenerator, ISoundPlayer sound) {
        this.mapGenerator = mapGenerator;
        this.sound = sound;
        currentLevelIndex  = client.getLevelIdx();
        config             = LevelConfig.ALL[currentLevelIndex];
        
        // Use the map size specified by the server
        exitTileX          = client.getMapSize() - 1;
        exitTileY          = client.getMapSize() - 1;

        levelComplete      = false;
        gameOverMenu       = false;
        victory            = false;
        selectedMenuOption = 0;

        // 1. Load the exact map layout sent by the server
        this.map = client.getServerMap();
        this.door = new Door(exitTileX, exitTileY);

        // 2. Spawn dummy monsters. Their logic won't run locally.
        monsters.clear();
        for (int i = 0; i < config.monsterCount; i++) {
            monsters.add(new MonsterEntity(0, 0, TILE_SIZE));
        }

        // 3. FIXED: Load the exact ball locations into the generic items list
        items.clear();
        for (double[] b : client.getServerBalls()) {
            items.add(new Ball(b[0], b[1])); // Stored as generic Item
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
        
        // Clear all entities before respawning
        monsters.clear();
        items.clear(); 
        
        spawnMonsters(config.monsterCount);
        spawnBalls(config.objectiveCount);
        
        // Example: If you wanted Medkits later, you could safely call it here:
        // spawnMedkits(2); 

        remainingTimeMillis = config.timeLimitMillis;
    }

    public int getCurrentLevelIndex() { return currentLevelIndex; }

    public boolean collectItems(IPlayer player) {
        boolean collectedSomething = false;

        // Loop backwards so we can safely remove items while iterating
        for (int i = items.size() - 1; i >= 0; i--) {
            Item item = items.get(i);
            
            if (item.isPlayerNear(player)) {
                // The item itself decides if it should be consumed
                if (item.onCollect(player)) {
                    items.remove(i);
                    collectedSomething = true;
                }
            }
        }

        // Check win condition: Are all the diamonds gone?
        if (collectedSomething) {
            sound.playDing();
            
            // Look specifically for remaining Balls/Diamonds
            boolean ballsRemaining = items.stream().anyMatch(item -> item instanceof Ball);
            if (!ballsRemaining && !door.isOpen()) {
                door.open();
            }
        }
        
        return collectedSomething;
    }

    public boolean checkExit(double playerX, double playerY) {
        return door.isOpen()
            && (int) playerX / TILE_SIZE == exitTileX - 1
            && (int) playerY / TILE_SIZE == exitTileY;
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
        List<int[]> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] pos = findEmptyMonsterSpawn(taken);
            taken.add(pos);
            monsters.add(new MonsterEntity(pos[0], pos[1], TILE_SIZE));
        }
    }

    private void spawnBalls(int count) {
        int mapSize = config.mapSize;
        int spawned = 0;
        
        // FIXED: Loop until we spawn the correct amount, checking the items list for collisions
        while (spawned < count) {
            int tx = 1 + random.nextInt(mapSize - 2);
            int ty = 1 + random.nextInt(mapSize - 2);
            if (map[ty][tx] != 0) continue;
            
            if ((tx == START_TILE_X && ty == START_TILE_Y)
                    || (tx == exitTileX && ty == exitTileY)) continue;
                    
            // Check if ANY item is already occupying this exact tile
            boolean occupied = items.stream().anyMatch(
                item -> (int)(item.getX() / TILE_SIZE) == tx
                     && (int)(item.getY() / TILE_SIZE) == ty);
                     
            if (occupied) continue;
            
            // Add safely to the polymorphic list
            items.add(new Ball(
                tx * TILE_SIZE + TILE_SIZE / 2.0,
                ty * TILE_SIZE + TILE_SIZE / 2.0));
                
            spawned++;
        }
    }

    private void generateRandomMap() {
        map = mapGenerator.generateMaze(config.mapSize,
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