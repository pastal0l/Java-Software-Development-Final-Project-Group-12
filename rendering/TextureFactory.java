package rendering;

import domain.GameConstants;

public class TextureFactory {
    
    private static final int TEX_SIZE = GameConstants.TEX_SIZE;

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
                if ((x % plankWidth) == 0) {
                    base = 0x7A542D;
                }
                tex[x][y] = base;
            }
        }
        return tex;
    }

    public static int[][] createStone() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int blockSize = TEX_SIZE / 4;                  
        int mortar     = Math.max(1, TEX_SIZE / 32);   

        int[] blockColors = {
            0x3A6B3E, 0x335E37, 0x2E5732, 0x40724A, 0x365F3A
        };

        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int lx = x % blockSize;
                int ly = y % blockSize;

                if (lx < mortar || ly < mortar) {
                    tex[x][y] = 0x1B2A1D;
                    continue;
                }

                int bx = x / blockSize;
                int by = y / blockSize;
                int base = blockColors[(bx * 7 + by * 13) % blockColors.length];

                int shade = (lx + ly) * 30 / (2 * blockSize) - 15; 
                int red   = clamp(((base >> 16) & 0xFF) + shade);
                int green = clamp(((base >> 8)  & 0xFF) + shade);
                int blue  = clamp((base & 0xFF) + shade);
                tex[x][y] = (red << 16) | (green << 8) | blue;
            }
        }
        return tex;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public static int[][] loadImageTexture(String[] paths, int[][] fallback) {
        for (String path : paths) {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) continue;
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(file);
                if (img == null) continue;
                return resample(img);
            } catch (java.io.IOException e) {
                System.err.println("[Textures] Failed to load " + path + ": " + e.getMessage());
            }
        }
        return fallback;
    }

    private static int[][] resample(java.awt.image.BufferedImage img) {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int w = img.getWidth();
        int h = img.getHeight();
        for (int x = 0; x < TEX_SIZE; x++) {
            int sx0 = x * w / TEX_SIZE;
            int sx1 = Math.max(sx0 + 1, (x + 1) * w / TEX_SIZE);
            sx1 = Math.min(sx1, w);
            for (int y = 0; y < TEX_SIZE; y++) {
                int sy0 = y * h / TEX_SIZE;
                int sy1 = Math.max(sy0 + 1, (y + 1) * h / TEX_SIZE);
                sy1 = Math.min(sy1, h);

                long rSum = 0, gSum = 0, bSum = 0;
                int count = 0;
                for (int sx = sx0; sx < sx1; sx++) {
                    for (int sy = sy0; sy < sy1; sy++) {
                        int rgb = img.getRGB(sx, sy);
                        rSum += (rgb >> 16) & 0xFF;
                        gSum += (rgb >> 8) & 0xFF;
                        bSum += rgb & 0xFF;
                        count++;
                    }
                }
                if (count == 0) count = 1;
                int r = (int) (rSum / count);
                int g = (int) (gSum / count);
                int b = (int) (bSum / count);
                tex[x][y] = (r << 16) | (g << 8) | b;
            }
        }
        return tex;
    }

    public static int[][] createMarble() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int period = Math.max(1, TEX_SIZE / 4);      
        int veinWidth = Math.max(1, TEX_SIZE / 16);
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = 0xC3B091;
                if (((x + y) % period) < veinWidth) {
                    base = 0xA8977A;
                }
                tex[x][y] = base;
            }
        }
        return tex;
    }

    public static int[][] createDoor() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        int panelStart  = TEX_SIZE * 20 / 64;
        int panelEnd    = TEX_SIZE * 44 / 64;
        int stripeX     = Math.max(1, TEX_SIZE / 8);
        int stripeY     = Math.max(1, TEX_SIZE * 12 / 64);
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int shade = ((x / stripeX) % 2 == 0) ? 0x755531 : 0x8B6A42;
                if (x > panelStart && x < panelEnd) {
                    shade = ((y / stripeY) % 2 == 0) ? 0x5C3D1B : 0x6F4F28;
                }
                tex[x][y] = shade;
            }
        }
        return tex;
    }

    public static int[][] createOpenDoor() {
        int[][] tex = new int[TEX_SIZE][TEX_SIZE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                tex[x][y] = 0xFFFFFF;
            }
        }
        return tex;
    }
}