/**
 * Immutable configuration for a single game level.
 *
 * <p>Edit entries in {@link #ALL} to tune difficulty.
 * GamePanel and Renderer read everything from here at runtime.</p>
 */
class LevelConfig {

    /** All levels in play order (0-based index). */
    static final LevelConfig[] ALL = {
        //                level  map   diamonds  time (ms)          monsters
        new LevelConfig(  1,     20,   3,        5  * 60 * 1000L,  1 ),
        new LevelConfig(  2,     25,   5,        7  * 60 * 1000L,  2 ),
        new LevelConfig(  3,     30,   7,        10 * 60 * 1000L,  3 ),
    };

    /** 1-based level number shown to the player. */
    final int  level;
    /** Map grid dimension (square). */
    final int  mapSize;
    /** Diamonds needed to unlock the exit. */
    final int  objectiveCount;
    /** Total time in milliseconds. */
    final long timeLimitMillis;
    /** Number of monsters on this level. */
    final int  monsterCount;

    private LevelConfig(int level, int mapSize, int objectiveCount,
                        long timeLimitMillis, int monsterCount) {
        this.level           = level;
        this.mapSize         = mapSize;
        this.objectiveCount  = objectiveCount;
        this.timeLimitMillis = timeLimitMillis;
        this.monsterCount    = monsterCount;
    }

    /** {@code true} when this is the final level. */
    boolean isLast() {
        return level == ALL.length;
    }
}
