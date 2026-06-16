package rendering;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * TextureFactory handles the procedural generation of textures.
 * Each texture is a TEX_SIZE×TEX_SIZE array of packed RGB integers,
 * indexed as [column][row] (x, y).
 */
public class TextureFactory {
    public static final int TEX_SIZE = 64;

    // ── Static texture instances ──────────────────────────────────────────

    public static final int[][] BRICK     = createBrick();
    public static final int[][] WOOD      = createWood();
    public static final int[][] STONE     = createStone();
    public static final int[][] DOOR      = createDoor();
    public static final int[][] DOOR_OPEN = createOpenDoor();
    public static final int[][] MARBLE    = createMarble();

    /**
     * Wall texture loaded from bush.png; falls back to STONE if missing.
     * Loaded lazily on first call to getWall() so the CWD is stable.
     */
    private static int[][] wallCache = null;
    public static int[][] getWall() {
        if (wallCache == null) {
            wallCache = loadImageTexture(
                new String[]{
                    "asset/bush.png", "asset/bush.jpg",
                    "bush.png",       "bush.jpg", "bush.jpeg",
                    "textures/bush.png", "textures/bush.jpg"
                },
                STONE);
        }
        return wallCache;
    }

    // ── Procedural generators ─────────────────────────────────────────────

    public static int[][] createBrick() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int blockSize = TEX_SIZE / 4;
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int block = ((x / blockSize) + (y / blockSize)) % 2 == 0 ? 0xA04030 : 0x8B2A1D;
                tex[x][y] = block;
            }
        }
        return tex;
    }

    public static int[][] createWood() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int plankWidth = Math.max(1, TEX_SIZE / 8);
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = 0xA07840;
                if ((x % plankWidth) == 0) base = 0x7A542D;
                tex[x][y] = base;
            }
        }
        return tex;
    }

    /**
     * Green stone blocks with dark mortar lines and diagonal shading.
     * Block+mortar pattern avoids ugly stretched noise at close range.
     */
    public static int[][] createStone() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int blockSize = TEX_SIZE / 4;
        int mortar    = Math.max(1, TEX_SIZE / 32);
        int[] blockColors = { 0x3A6B3E, 0x335E37, 0x2E5732, 0x40724A, 0x365F3A };
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int lx = x % blockSize;
                int ly = y % blockSize;
                if (lx < mortar || ly < mortar) { tex[x][y] = 0x1B2A1D; continue; }
                int bx   = x / blockSize;
                int by   = y / blockSize;
                int base = blockColors[(bx * 7 + by * 13) % blockColors.length];
                int shade = (lx + ly) * 30 / (2 * blockSize) - 15;
                tex[x][y] = (clamp(((base >> 16) & 0xFF) + shade) << 16)
                           | (clamp(((base >>  8) & 0xFF) + shade) << 8)
                           |  clamp((base & 0xFF) + shade);
            }
        }
        return tex;
    }

    public static int[][] createMarble() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int period    = Math.max(1, TEX_SIZE / 4);
        int veinWidth = Math.max(1, TEX_SIZE / 16);
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = 0xC3B091;
                if (((x + y) % period) < veinWidth) base = 0xA8977A;
                tex[x][y] = base;
            }
        }
        return tex;
    }

    public static int[][] createDoor() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int panelStart = TEX_SIZE * 20 / 64;
        int panelEnd   = TEX_SIZE * 44 / 64;
        int stripeX    = Math.max(1, TEX_SIZE / 8);
        int stripeY    = Math.max(1, TEX_SIZE * 12 / 64);
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int shade = ((x / stripeX) % 2 == 0) ? 0x755531 : 0x8B6A42;
                if (x > panelStart && x < panelEnd)
                    shade = ((y / stripeY) % 2 == 0) ? 0x5C3D1B : 0x6F4F28;
                tex[x][y] = shade;
            }
        }
        return tex;
    }

    public static int[][] createOpenDoor() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++)
            for (int x = 0; x < TEX_SIZE; x++)
                tex[x][y] = 0xFFFFFF;
        return tex;
    }

    // ── Image loading ─────────────────────────────────────────────────────

    /**
     * Loads from the first existing path, trying three strategies per path:
     *   1) Classpath resource via Class.getResourceAsStream (most reliable in Eclipse)
     *   2) Filesystem relative to CWD
     *   3) Filesystem relative to the project root (bin/../)
     * Falls back to the supplied texture if nothing works.
     */
    public static int[][] loadImageTexture(String[] paths, int[][] fallback) {
        // Resolve the project root from this class file's location once
        // TextureFactory.class → bin/rendering/TextureFactory.class
        // getCodeSource().getLocation() → .../all/bin/
        // getParentFile()              → .../all/   (project root)
        File projectRoot = null;
        try {
            java.net.URL loc = TextureFactory.class.getProtectionDomain()
                                   .getCodeSource().getLocation();
            File binDir = new File(loc.toURI());
            projectRoot = binDir.getParentFile(); // all/
        } catch (Exception ignored) {}

        for (String path : paths) {
            // 1) Eclipse copies non-Java files to bin/, so try classpath resource first
            //    Use leading "/" to search from the classpath root (bin/)
            String resPath = "/" + path;
            try {
                java.io.InputStream is = TextureFactory.class.getResourceAsStream(resPath);
                if (is != null) {
                    BufferedImage img = ImageIO.read(is);
                    is.close();
                    if (img != null) return resample(img);
                }
            } catch (IOException ignored) {}

            // 2) Try relative to CWD
            File f = new File(path);
            if (f.exists()) {
                try {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null) return resample(img);
                } catch (IOException ignored) {}
            }

            // 3) Try relative to project root (all/)
            if (projectRoot != null) {
                File f2 = new File(projectRoot, path);
                if (f2.exists()) {
                    try {
                        BufferedImage img = ImageIO.read(f2);
                        if (img != null) return resample(img);
                    } catch (IOException ignored) {}
                }
            }
        }
        System.err.println("[TextureFactory] Could not load texture. CWD="
            + System.getProperty("user.dir") + "  projectRoot=" + projectRoot);
        return fallback;
    }

    /**
     * Area-average (box-filter) resample into TEX_SIZE×TEX_SIZE.
     * Averaging source pixels per texel prevents stretched-noise artifacts
     * on nearby walls.
     */
    public static int[][] resample(BufferedImage img) {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int w = img.getWidth(), h = img.getHeight();
        for (int x = 0; x < TEX_SIZE; x++) {
            int sx0 = x * w / TEX_SIZE;
            int sx1 = Math.min(Math.max(sx0 + 1, (x + 1) * w / TEX_SIZE), w);
            for (int y = 0; y < TEX_SIZE; y++) {
                int sy0 = y * h / TEX_SIZE;
                int sy1 = Math.min(Math.max(sy0 + 1, (y + 1) * h / TEX_SIZE), h);
                long rSum = 0, gSum = 0, bSum = 0;
                int count = 0;
                for (int sx = sx0; sx < sx1; sx++) {
                    for (int sy = sy0; sy < sy1; sy++) {
                        int rgb = img.getRGB(sx, sy);
                        rSum += (rgb >> 16) & 0xFF;
                        gSum += (rgb >>  8) & 0xFF;
                        bSum +=  rgb        & 0xFF;
                        count++;
                    }
                }
                if (count == 0) count = 1;
                tex[x][y] = ((int)(rSum / count) << 16)
                           | ((int)(gSum / count) << 8)
                           |  (int)(bSum / count);
            }
        }
        return tex;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
