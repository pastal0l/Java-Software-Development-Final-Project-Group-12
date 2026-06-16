package rendering;

import java.util.HashMap;
import java.util.Map;
import domain.Door;

/**
 * TextureRegistry applies the Open-Closed Principle.
 * New texture mappings can be added at runtime via register()
 * without modifying this class.
 */
public class TextureRegistry {

    private final Map<Integer, int[][]> textures = new HashMap<>();

    // Wall type constants — match values used in MazeGenerator
    public static final int WALL_BRICK  = 1;
    public static final int WALL_WOOD   = 2;
    public static final int WALL_STONE  = 3;
    public static final int WALL_MARBLE = 4;

    public TextureRegistry() {
        // Standard wall textures
        register(WALL_BRICK,  TextureFactory.createBrick());
        register(WALL_WOOD,   TextureFactory.createWood());
        register(WALL_STONE,  TextureFactory.createStone());
        register(WALL_MARBLE, TextureFactory.createMarble());

        // Door textures — keyed by Door's own constant
        register(Door.DOOR_TILE,     TextureFactory.createDoor());
        register(Door.DOOR_TILE + 1, TextureFactory.createOpenDoor()); // open door variant

        // Image-based overrides — falls back to procedural if file missing
        register(WALL_BRICK, TextureFactory.loadImageTexture(
            new String[]{"assets/brick.png", "asset/brick.png"},
            TextureFactory.createBrick()
        ));
    }

    /**
     * Extension point: add or override textures at runtime without
     * touching this class — satisfies Open-Closed Principle.
     */
    public void register(int wallType, int[][] texture) {
        textures.put(wallType, texture);
    }

    /**
     * Returns the texture for a wall type.
     * Falls back to brick if the ID is not registered,
     * preventing NullPointerExceptions in the renderer.
     */
    public int[][] get(int wallType) {
        return textures.getOrDefault(wallType, textures.get(WALL_BRICK));
    }

    /**
     * Convenience — true if a texture is registered for this wall type.
     * Useful for the renderer to skip unknown tile types.
     */
    public boolean has(int wallType) {
        return textures.containsKey(wallType);
    }
}