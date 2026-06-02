import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class GamePanel extends JPanel implements ActionListener {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int FPS = 60;
    private static final double MOVE_SPEED = 2.8;
    private static final double ROTATE_SPEED = Math.toRadians(3.5);
    private static final int MAP_SIZE = 10;
    private static final int TILE_SIZE = 64;
    private static final int TEX_SIZE = 64;

    private final int[][] map = {
            {3, 3, 3, 3, 3, 3, 3, 3, 3, 3},
            {3, 0, 0, 0, 0, 3, 0, 0, 0, 3},
            {3, 0, 3, 3, 0, 3, 0, 3, 0, 3},
            {3, 0, 3, 0, 0, 0, 0, 3, 0, 3},
            {3, 0, 3, 0, 3, 3, 0, 3, 0, 3},
            {3, 0, 0, 0, 3, 0, 0, 0, 0, 3},
            {3, 3, 3, 0, 3, 0, 3, 3, 0, 3},
            {3, 0, 0, 0, 0, 0, 0, 3, 0, 3},
            {3, 0, 3, 3, 3, 3, 0, 0, 0, 3},
            {3, 3, 3, 3, 3, 3, 3, 3, 3, 3}
    };

    private final int startTileX = 1;
    private final int startTileY = 1;
    private final int exitTileX = 8;
    private final int exitTileY = 8;
    private boolean levelComplete = false;

    private double playerX = startTileX * TILE_SIZE + TILE_SIZE / 2.0;
    private double playerY = startTileY * TILE_SIZE + TILE_SIZE / 2.0;
    private double playerAngle = Math.toRadians(45);
    private boolean moveForward, moveBackward, turnLeft, turnRight, strafeLeft, strafeRight;
    private final Timer timer;
    // Back-buffer for faster pixel operations
    private BufferedImage screenBuffer;
    private int[] screenPixels;

    public GamePanel() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setOpaque(true);
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(new InputAdapter());

        timer = new Timer(1000 / FPS, this);
    }

    public void startGame() {
        timer.start();
    }

    public void requestFocusForInput() {
        requestFocusInWindow();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        updatePlayer();
        repaint();
    }

    private void updatePlayer() {
        if (levelComplete) {
            return;
        }

        double dx = 0;
        double dy = 0;

        if (moveForward) {
            dx += Math.cos(playerAngle) * MOVE_SPEED;
            dy += Math.sin(playerAngle) * MOVE_SPEED;
        }
        if (moveBackward) {
            dx -= Math.cos(playerAngle) * MOVE_SPEED;
            dy -= Math.sin(playerAngle) * MOVE_SPEED;
        }
        if (strafeLeft) {
            dx += Math.cos(playerAngle - Math.PI / 2) * MOVE_SPEED;
            dy += Math.sin(playerAngle - Math.PI / 2) * MOVE_SPEED;
        }
        if (strafeRight) {
            dx += Math.cos(playerAngle + Math.PI / 2) * MOVE_SPEED;
            dy += Math.sin(playerAngle + Math.PI / 2) * MOVE_SPEED;
        }
        if (turnLeft) {
            playerAngle -= ROTATE_SPEED;
        }
        if (turnRight) {
            playerAngle += ROTATE_SPEED;
        }

        double nextX = playerX + dx;
        double nextY = playerY + dy;

        if (!collides(nextX, playerY)) {
            playerX = nextX;
        }
        if (!collides(playerX, nextY)) {
            playerY = nextY;
        }

        checkExit();
    }

    private void restartGame() {
        levelComplete = false;
        playerX = startTileX * TILE_SIZE + TILE_SIZE / 2.0;
        playerY = startTileY * TILE_SIZE + TILE_SIZE / 2.0;
        playerAngle = Math.toRadians(45);
        timer.start();
    }

    private boolean collides(double x, double y) {
        int mapX = (int) x / TILE_SIZE;
        int mapY = (int) y / TILE_SIZE;
        if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) {
            return true;
        }
        return map[mapY][mapX] != 0;
    }

    private void checkExit() {
        int mapX = (int) playerX / TILE_SIZE;
        int mapY = (int) playerY / TILE_SIZE;

        if (mapX == exitTileX && mapY == exitTileY) {
            levelComplete = true;
            timer.stop();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        drawScene(g);
        drawMinimap(g);
        if (levelComplete) {
            drawGameOver(g);
        }
    }

    private void drawScene(Graphics g) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Ensure back-buffer matches current size
        if (screenBuffer == null || screenBuffer.getWidth() != width || screenBuffer.getHeight() != height) {
            screenBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            screenPixels = ((DataBufferInt) screenBuffer.getRaster().getDataBuffer()).getData();
        }

        int horizon = height / 2;
        int skyColor = (135 << 16) | (206 << 8) | 235;
        int floorColor = (34 << 16) | (139 << 8) | 34;

        // Fill sky and floor
        int row = 0;
        for (int y = 0; y < horizon; y++) {
            int offset = row * width;
            for (int x = 0; x < width; x++) {
                screenPixels[offset + x] = skyColor;
            }
            row++;
        }
        for (int y = horizon; y < height; y++) {
            int offset = row * width;
            for (int x = 0; x < width; x++) {
                screenPixels[offset + x] = floorColor;
            }
            row++;
        }

        double fov = Math.toRadians(60);
        int rayCount = width / 2; // keep existing visual density
        double rayStep = fov / rayCount;
        double startAngle = playerAngle - fov / 2;

        for (int ray = 0; ray < rayCount; ray++) {
            double rayAngle = startAngle + ray * rayStep;
            RayHit hit = castRay(rayAngle);
            double correctedDistance = hit.distance * Math.cos(rayAngle - playerAngle);
            int lineHeight = (int) ((TILE_SIZE * height) / correctedDistance);
            if (lineHeight < 1) lineHeight = 1;
            if (lineHeight > height) lineHeight = height;

            int lineOffset = horizon - lineHeight / 2;
            int drawStart = Math.max(0, lineOffset);
            int drawEnd = Math.min(height - 1, lineOffset + lineHeight);
            int screenX = ray * 2;
            if (screenX < 0) continue;

            for (int xOff = 0; xOff < 2; xOff++) {
                int px = screenX + xOff;
                if (px < 0 || px >= width) continue;
                int base = px;
                for (int y = drawStart; y <= drawEnd; y++) {
                    int textureY = (int) (((y - lineOffset) * TEX_SIZE) / (double) lineHeight);
                    textureY = Math.max(0, Math.min(TEX_SIZE - 1, textureY));
                    int color = hit.texture[hit.textureX][textureY];
                    if (hit.side == 1) color = shadeColor(color, 0.70);
                    color = shadeColor(color, Math.max(0.20, 1.0 / (1.0 + correctedDistance * correctedDistance * 0.00005)));
                    screenPixels[y * width + base] = color;
                }
            }
        }

        // Draw the prepared buffer once
        g.drawImage(screenBuffer, 0, 0, null);
    }

    private RayHit castRay(double rayAngle) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);

        int mapX = (int) (playerX / TILE_SIZE);
        int mapY = (int) (playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);

        int stepX;
        int stepY;
        double sideDistX;
        double sideDistY;

        if (rayDirX < 0) {
            stepX = -1;
            sideDistX = (playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE;
        } else {
            stepX = 1;
            sideDistX = ((mapX + 1) * TILE_SIZE - playerX) * deltaDistX / TILE_SIZE;
        }
        if (rayDirY < 0) {
            stepY = -1;
            sideDistY = (playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE;
        } else {
            stepY = 1;
            sideDistY = ((mapY + 1) * TILE_SIZE - playerY) * deltaDistY / TILE_SIZE;
        }

        boolean hit = false;
        int side = 0;
        int wallType = 1;

        while (!hit) {
            if (sideDistX < sideDistY) {
                sideDistX += deltaDistX;
                mapX += stepX;
                side = 0;
            } else {
                sideDistY += deltaDistY;
                mapY += stepY;
                side = 1;
            }

            if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) {
                break;
            }
            if (map[mapY][mapX] > 0) {
                hit = true;
                wallType = map[mapY][mapX];
            }
        }

        double distance;
        if (side == 0) {
            distance = (mapX * TILE_SIZE - playerX + (1 - stepX) * TILE_SIZE / 2.0) / rayDirX;
        } else {
            distance = (mapY * TILE_SIZE - playerY + (1 - stepY) * TILE_SIZE / 2.0) / rayDirY;
        }
        if (distance <= 0) {
            distance = 1;
        }

        double wallX;
        if (side == 0) {
            wallX = playerY + distance * rayDirY;
        } else {
            wallX = playerX + distance * rayDirX;
        }
        wallX %= TILE_SIZE;
        if (wallX < 0) {
            wallX += TILE_SIZE;
        }

        int textureX = (int) ((wallX / TILE_SIZE) * TEX_SIZE);
        if (textureX < 0) {
            textureX = 0;
        }
        if (textureX >= TEX_SIZE) {
            textureX = TEX_SIZE - 1;
        }
        if ((side == 0 && rayDirX > 0) || (side == 1 && rayDirY < 0)) {
            textureX = TEX_SIZE - 1 - textureX;
        }

        return new RayHit(distance, wallType, textureX, side);
    }

    /**
     * DDA helper: returns the perpendicular (fish-eye corrected) distance
     * from the player to the first wall hit for the given ray angle.
     */
    private double castRayDistance(double rayAngle) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);

        int mapX = (int) (playerX / TILE_SIZE);
        int mapY = (int) (playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);

        int stepX;
        int stepY;
        double sideDistX;
        double sideDistY;

        if (rayDirX < 0) {
            stepX = -1;
            sideDistX = (playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE;
        } else {
            stepX = 1;
            sideDistX = ((mapX + 1) * TILE_SIZE - playerX) * deltaDistX / TILE_SIZE;
        }
        if (rayDirY < 0) {
            stepY = -1;
            sideDistY = (playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE;
        } else {
            stepY = 1;
            sideDistY = ((mapY + 1) * TILE_SIZE - playerY) * deltaDistY / TILE_SIZE;
        }

        boolean hit = false;
        int side = 0;

        while (!hit) {
            if (sideDistX < sideDistY) {
                sideDistX += deltaDistX;
                mapX += stepX;
                side = 0;
            } else {
                sideDistY += deltaDistY;
                mapY += stepY;
                side = 1;
            }

            if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) {
                break;
            }
            if (map[mapY][mapX] > 0) {
                hit = true;
            }
        }

        double distance;
        if (side == 0) {
            distance = (mapX * TILE_SIZE - playerX + (1 - stepX) * TILE_SIZE / 2.0) / (rayDirX == 0 ? 1e-6 : rayDirX);
        } else {
            distance = (mapY * TILE_SIZE - playerY + (1 - stepY) * TILE_SIZE / 2.0) / (rayDirY == 0 ? 1e-6 : rayDirY);
        }
        if (distance <= 0) {
            distance = 1;
        }

        // Correct for fish-eye by returning perpendicular distance
        return distance * Math.cos(rayAngle - playerAngle);
    }

    private int shadeColor(int rgb, double factor) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = (int) Math.max(0, Math.min(255, r * factor));
        g = (int) Math.max(0, Math.min(255, g * factor));
        b = (int) Math.max(0, Math.min(255, b * factor));
        return (r << 16) | (g << 8) | b;
    }

    private void drawMinimap(Graphics g) {
        int minimapSize = MAP_SIZE * 16;
        int offset = 10;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(offset - 4, offset - 4, minimapSize + 8, minimapSize + 8);

        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                g.setColor(map[y][x] > 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.fillRect(offset + x * 16, offset + y * 16, 16, 16);
            }
        }

        int px = (int) (playerX / TILE_SIZE * 16);
        int py = (int) (playerY / TILE_SIZE * 16);
        g.setColor(Color.RED);
        g.fillOval(offset + px - 4, offset + py - 4, 8, 8);
        int lineX = offset + px + (int) (Math.cos(playerAngle) * 12);
        int lineY = offset + py + (int) (Math.sin(playerAngle) * 12);
        g.drawLine(offset + px, offset + py, lineX, lineY);

        int exitPx = offset + exitTileX * 16;
        int exitPy = offset + exitTileY * 16;
        g.setColor(Color.GREEN);
        g.fillRect(exitPx + 4, exitPy + 4, 8, 8);

        int startPx = offset + startTileX * 16;
        int startPy = offset + startTileY * 16;
        g.setColor(Color.BLUE);
        g.fillOval(startPx + 4, startPy + 4, 8, 8);
    }

    private class InputAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_W -> moveForward = true;
                case KeyEvent.VK_S -> moveBackward = true;
                case KeyEvent.VK_A -> strafeLeft = true;
                case KeyEvent.VK_D -> strafeRight = true;
                case KeyEvent.VK_LEFT -> turnLeft = true;
                case KeyEvent.VK_RIGHT -> turnRight = true;
                case KeyEvent.VK_R -> restartGame();
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_W -> moveForward = false;
                case KeyEvent.VK_S -> moveBackward = false;
                case KeyEvent.VK_A -> strafeLeft = false;
                case KeyEvent.VK_D -> strafeRight = false;
                case KeyEvent.VK_LEFT -> turnLeft = false;
                case KeyEvent.VK_RIGHT -> turnRight = false;
            }
        }
    }

    private static class RayHit {
        final double distance;
        final int wallType;
        final int textureX;
        final int side;
        final int[][] texture;

        RayHit(double distance, int wallType, int textureX, int side) {
            this.distance = distance;
            this.wallType = wallType;
            this.textureX = textureX;
            this.side = side;
            this.texture = Textures.STONE;
        }
    }

    private void drawGameOver(Graphics g) {
        int width = getWidth();
        int height = getHeight();

        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, width, height);

        g.setColor(Color.WHITE);
        g.fillRect(width / 2 - 220, height / 2 - 80, 440, 160);
        g.setColor(Color.BLACK);
        g.drawRect(width / 2 - 220, height / 2 - 80, 440, 160);

        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(32f));
        String title = "Maze Complete!";
        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, width / 2 - titleWidth / 2, height / 2 - 20);

        g.setFont(g.getFont().deriveFont(18f));
        String message = "You reached the exit. Press R to restart.";
        int messageWidth = g.getFontMetrics().stringWidth(message);
        g.drawString(message, width / 2 - messageWidth / 2, height / 2 + 20);
    }

    private static class Textures {
        static final int[][] BRICK = createBrick();
        static final int[][] WOOD = createWood();
        static final int[][] STONE = createStone();
        static final int[][] MARBLE = createMarble();

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

        private static int[][] createStone() {
            int[][] tex = new int[TEX_SIZE][TEX_SIZE];
            for (int y = 0; y < TEX_SIZE; y++) {
                for (int x = 0; x < TEX_SIZE; x++) {
                    int base = 0x2E7D32;
                    int variation = (((x * 5) ^ (y * 11)) & 0x1F) - 8;
                    int green = Math.max(0, Math.min(255, ((base >> 8) & 0xFF) + variation));
                    int red = Math.max(0, Math.min(255, ((base >> 16) & 0xFF) - 12 + ((x + y) % 4 == 0 ? 6 : 0)));
                    int blue = Math.max(0, Math.min(255, ((base) & 0xFF) - 10));
                    tex[x][y] = (red << 16) | (green << 8) | blue;
                }
            }
            return tex;
        }

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
    }
}
