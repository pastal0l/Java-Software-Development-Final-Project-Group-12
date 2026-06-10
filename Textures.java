/**
 * Procedurally generated wall/door textures used by the raycaster renderer.
 * Each texture is a TEX_SIZE x TEX_SIZE array of packed RGB integers,
 * indexed as [column][row] (x, y).
 */
class Textures {
    private static final int TEX_SIZE = 64;

    static final int[][] BRICK = createBrick();
    static final int[][] WOOD = createWood();
    static final int[][] STONE = createStone();
    static final int[][] DOOR = createDoor();
    static final int[][] DOOR_OPEN = createOpenDoor();
    static final int[][] MARBLE = createMarble();

    /**
     * Alternating brick-color pattern.
     */
    private static int[][] createBrick() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int block = ((x / 16) + (y / 16)) % 2 == 0 ? 0xA04030 : 0x8B2A1D;
                tex[x][y] = block;
            }
        }
        return tex;
    }

    /**
     * Vertical wood-plank stripes.
     */
    private static int[][] createWood() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = 0xA07840;
                if ((x % 8) == 0) {
                    base = 0x7A542D;
                }
                tex[x][y] = base;
            }
        }
        return tex;
    }

    /**
     * Green stone with slight per-pixel color variation.
     */
    private static int[][] createStone() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = 0x2E7D32;
                int variation = (((x * 5) ^ (y * 11)) & 0x1F) - 8;
                int green = Math.max(0, Math.min(255, ((base >> 8) & 0xFF) + variation));
                int red = Math.max(0, Math.min(255, ((base >> 16) & 0xFF) - 12 + ((x + y) % 4 == 0 ? 6 : 0)));
                int blue = Math.max(0, Math.min(255, (base & 0xFF) - 10));
                tex[x][y] = (red << 16) | (green << 8) | blue;
            }
        }
        return tex;
    }

    /**
     * Diagonal marble-tile pattern.
     */
    private static int[][] createMarble() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = 0xC3B091;
                if (((x + y) % 16) < 4) {
                    base = 0xA8977A;
                }
                tex[x][y] = base;
            }
        }
        return tex;
    }

    /**
     * Wooden door with a central panel.
     */
    private static int[][] createDoor() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int shade = ((x / 8) % 2 == 0) ? 0x755531 : 0x8B6A42;
                if (x > 20 && x < 44) {
                    shade = ((y / 12) % 2 == 0) ? 0x5C3D1B : 0x6F4F28;
                }
                tex[x][y] = shade;
            }
        }
        return tex;
    }

    /**
     * Solid white — represents the open-door passage.
     */
    private static int[][] createOpenDoor() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                tex[x][y] = 0xFFFFFF;
            }
        }
        return tex;
    }
}
