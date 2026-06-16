package rendering;

import java.util.HashMap;
import java.util.Map;
import domain.Door; // Ensure this is imported for the Door.DOOR_TILE constant

/**
 * TextureRegistry applies the Open-Closed Principle.
 * New texture mappings can be added at runtime via register() 
 * without modifying this class.
 */
public class TextureRegistry {
    
    private final Map<Integer, int[][]> textures = new HashMap<>();

    public TextureRegistry() {
        // Map block IDs from your MazeGenerator to specific textures
        register(1, TextureFactory.getWall());
        register(2, TextureFactory.createWood());
        register(3, TextureFactory.createStone());
        register(4, TextureFactory.createMarble());
        
        // Map special tiles
        register(Door.DOOR_TILE, TextureFactory.createDoor());
    }

    /**
     * Extension point: Allows adding new textures dynamically.
     */
    public void register(int wallType, int[][] texture) {
        textures.put(wallType, texture);
    }

    /**
     * Retrieves a texture. Fallbacks to a default texture if the ID is missing,
     * preventing NullPointerExceptions in the renderer.
     */
    public int[][] get(int wallType) {
        return textures.getOrDefault(wallType, textures.get(1)); // Default to brick
    }
}