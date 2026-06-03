import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * GamePanel: main game component handling rendering, input, and updates.
 * Implements the raycasting renderer, HUD/minimap, player movement,
 * and updates the `Monster` AI and game state.
 */
public class GamePanel extends JPanel implements ActionListener {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int FPS = 60;
    private static final double MOVE_SPEED = 3.5;
    private static final double ROTATE_SPEED = Math.toRadians(3.5);
    private static final int MAP_SIZE = 20;
    private static final int TILE_SIZE = 64;
    private static final int TEX_SIZE = 64;
    private static final int OBJECTIVE_COUNT = 3;

    private final List<Ball> balls = new ArrayList<>();
    private final Random random = new Random();

    private int[][] map;

    private final int startTileX = 1;
    private final int startTileY = 1;
    private final int exitTileX = MAP_SIZE - 1;
    private final int exitTileY = MAP_SIZE - 1;
    private final int monsterStartTileX = 8;
    private final int monsterStartTileY = 1;
    private boolean levelComplete = false;
    private boolean victoryMusicPlayed = false;

    // initialize start position and angle
    private double playerX = startTileX * TILE_SIZE + TILE_SIZE / 2.0;
    private double playerY = startTileY * TILE_SIZE + TILE_SIZE / 2.0;
    private double playerAngle = Math.toRadians(45);
    private boolean moveForward, moveBackward, turnLeft, turnRight, strafeLeft, strafeRight;
    private final Monster monster;
    private final Door door;
    private static final long START_TIME_MILLIS = 5 * 60 * 1000; // 5 minutes
    private long remainingTimeMillis = START_TIME_MILLIS;
    private double floatPhase = 0;
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
        generateRandomMap();
        door = new Door(exitTileX, exitTileY);

        monster = new Monster(monsterStartTileX, monsterStartTileY, TILE_SIZE);
        timer = new Timer(1000 / FPS, this);
        spawnBalls(OBJECTIVE_COUNT);
    }

    private void generateRandomMap() {
        map = MazeGenerator.generateMaze(MAP_SIZE, startTileX, startTileY, exitTileX - 1, exitTileY);
        map[startTileY][startTileX] = 0;
        map[startTileY][startTileX + 1] = 0;
        map[startTileY + 1][startTileX] = 0;
        map[exitTileY][exitTileX - 1] = 0;

        map[monsterStartTileY][monsterStartTileX] = 0;
        map[monsterStartTileY][monsterStartTileX - 1] = 0;
        map[monsterStartTileY][monsterStartTileX + 1] = 0;
        map[monsterStartTileY + 1][monsterStartTileX] = 0;
    }

    /**
     * Start the main game timer which drives updates and repainting.
     */
    public void startGame() {
        timer.start();
    }

    /**
     * Request keyboard focus for this panel so input events are received.
     */
    public void requestFocusForInput() {
        requestFocusInWindow();
    }

    @Override
    /**
     * Timer callback invoked every frame. Updates game state and requests repaint.
     */
    public void actionPerformed(ActionEvent e) {
        updatePlayer();
        floatPhase += 0.08;
        repaint();
    }

    /**
     * Update player movement, handle collisions, update monster and check game state.
     */
    private void updatePlayer() {
        if (levelComplete) {
            return;
        }

        // Decrease remaining time; when it reaches zero the level ends.
        remainingTimeMillis -= 1000 / FPS;
        if (remainingTimeMillis <= 0) {
            remainingTimeMillis = 0;
            levelComplete = true;
            timer.stop();
            return;
        }

        double dx = 0;
        double dy = 0;

        if (moveForward) {
            // Move in the direction the player is facing
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

        monster.update(playerX, playerY, map, TILE_SIZE);
        if (monster.collidesWithPlayer(playerX, playerY, TILE_SIZE)) {
            restartGame();
            return;
        }

        collectBalls();
        checkExit();
    }

    /**
     * Reset player and monster state and restart the timer for a new run.
     */
    private void restartGame() {
        levelComplete = false;
        victoryMusicPlayed = false;
        door.close();
        playerX = startTileX * TILE_SIZE + TILE_SIZE / 2.0;
        playerY = startTileY * TILE_SIZE + TILE_SIZE / 2.0;
        playerAngle = Math.toRadians(45);
        monster.reset(8, 1);
        spawnBalls(OBJECTIVE_COUNT);
        timer.start();
        remainingTimeMillis = START_TIME_MILLIS;
    }
    // Simple AABB collision check against the map grid
    /**
     * Check whether the provided point collides with a non-zero tile in the map.
     */

    private void spawnBalls(int count) {
        balls.clear();
        while (balls.size() < count) {
            int tileX = 1 + random.nextInt(MAP_SIZE - 2);
            int tileY = 1 + random.nextInt(MAP_SIZE - 2);
            if (map[tileY][tileX] != 0) {
                continue;
            }
            if ((tileX == startTileX && tileY == startTileY) || (tileX == exitTileX && tileY == exitTileY)) {
                continue;
            }
            boolean exists = false;
            for (Ball ball : balls) {
                if ((int) (ball.x / TILE_SIZE) == tileX && (int) (ball.y / TILE_SIZE) == tileY) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                continue;
            }
            double x = tileX * TILE_SIZE + TILE_SIZE / 2.0;
            double y = tileY * TILE_SIZE + TILE_SIZE / 2.0;
            balls.add(new Ball(x, y));
        }
    }

    private void collectBalls() {
        double pickupRadius = 18;
        boolean collected = false;
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball ball = balls.get(i);
            double dx = playerX - ball.x;
            double dy = playerY - ball.y;
            if (dx * dx + dy * dy <= pickupRadius * pickupRadius) {
                balls.remove(i);
                collected = true;
            }
        }
        if (collected) {
            SoundPlayer.playDing();
            if (balls.isEmpty()) {
                door.open();
            }
        }
    }

    private boolean collides(double x, double y) {
        int mapX = (int) x / TILE_SIZE;
        int mapY = (int) y / TILE_SIZE;
        return isWallTile(mapX, mapY);
    }

    /**
     * Check whether the player is on the exit tile and finish the level.
     */
    private void checkExit() {
        if (door.isOpen() && isPlayerTouchingDoor()) {
            levelComplete = true;
            timer.stop();
            if (!victoryMusicPlayed) {
                victoryMusicPlayed = true;
                SoundPlayer.playVictoryMusic();
            }
        }
    }

    private boolean isPlayerTouchingDoor() {
        int mapX = (int) playerX / TILE_SIZE;
        int mapY = (int) playerY / TILE_SIZE;
        return mapX == exitTileX - 1 && mapY == exitTileY;
    }

    private boolean isExitOpen() {
        return door.isOpen();
    }

    private int getMapTile(int mapX, int mapY) {
        if (door.isAt(mapX, mapY)) {
            return door.isOpen() ? 0 : door.getMapValue();
        }
        return map[mapY][mapX];
    }

    private boolean isWallTile(int mapX, int mapY) {
        if (mapX < 0 || mapX >= MAP_SIZE || mapY < 0 || mapY >= MAP_SIZE) {
            return true;
        }
        return getMapTile(mapX, mapY) != 0;
    }

    @Override
    /**
     * Swing paint callback. Draws the 3D scene, minimap, HUD and overlays.
     */
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        drawScene(g);
        drawMinimap(g);
        drawHUD(g);
        drawStatus(g);
        if (levelComplete) {
            drawGameOver(g);
        }
    }

    // Main rendering method: draws the 3D scene using raycasting
    /**
     * Perform raycasting to render the 3D view into the screen buffer.
     */
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
        double[] rayDistances = new double[rayCount];

        for (int ray = 0; ray < rayCount; ray++) {
            double rayAngle = startAngle + ray * rayStep;
            RayHit hit = castRay(rayAngle);
            double correctedDistance = hit.distance * Math.cos(rayAngle - playerAngle);
            rayDistances[ray] = correctedDistance;
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

        drawBallSprites(screenPixels, width, height, horizon, startAngle, rayStep, rayCount, rayDistances);

        // Draw the prepared buffer once
        g.drawImage(screenBuffer, 0, 0, null);
        drawMonsterSprite(g, width, height);
    }
    /**
     * Draw a simple 2D sprite for the monster in the 3D view when visible.
     */
    private void drawMonsterSprite(Graphics g, int width, int height) {
        double mx = monster.getX();
        double my = monster.getY();
        double dx = mx - playerX;
        double dy = my - playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= 0) {
            return;
        }

        double angleToMonster = Math.atan2(dy, dx);
        double relativeAngle = normalizeAngle(angleToMonster - playerAngle);
        double fov = Math.toRadians(60);
        if (Math.abs(relativeAngle) > fov / 2) {
            return;
        }

        // Use raw ray hit distance (along the ray) to test occlusion accurately.
        RayHit rayHit = castRay(angleToMonster);
        double wallDistanceRaw = rayHit.distance;
        // If a wall is closer than the monster along this ray, the monster is occluded.
        if (wallDistanceRaw + 1.0 < distance) {
            return;
        }

        int screenX = (int) (((relativeAngle / (fov / 2)) * 0.5 + 0.5) * width);
        int spriteSize = (int) ((TILE_SIZE * height) / distance * 0.4);
        spriteSize = Math.max(12, Math.min(spriteSize, 80));
        int spriteY = height / 2 - spriteSize / 2;

        java.awt.image.BufferedImage sprite = monster.getSprite();
        if (sprite != null) {
            g.drawImage(sprite, screenX - spriteSize / 2, spriteY, spriteSize, spriteSize, null);
        } else {
            g.setColor(new Color(230, 40, 40, 220));
            g.fillOval(screenX - spriteSize / 2, spriteY, spriteSize, spriteSize);
            g.setColor(Color.BLACK);
            g.drawOval(screenX - spriteSize / 2, spriteY, spriteSize, spriteSize);
        }
    }

    private void drawBallSprites(int[] pixels, int width, int height, int horizon, double startAngle, double rayStep, int rayCount, double[] rayDistances) {
        double fov = Math.toRadians(60);
        double projectionPlane = (width / 2.0) / Math.tan(fov / 2.0);

        for (Ball ball : balls) {
            double dx = ball.x - playerX;
            double dy = ball.y - playerY;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < 0.1) {
                continue;
            }

            double angleToBall = Math.atan2(dy, dx);
            double angleDiff = normalizeAngle(angleToBall - playerAngle);
            if (Math.abs(angleDiff) > fov / 2.0) {
                continue;
            }

            int spriteScreenX = (int) ((width / 2.0) + projectionPlane * Math.tan(angleDiff));
            int spriteSize = (int) ((TILE_SIZE * height) / distance * 0.20);
            if (spriteSize < 18) spriteSize = 18;
            if (spriteSize > 90) spriteSize = 90;
            int spriteHalf = spriteSize / 2;

            int floatOffset = (int) (Math.sin(floatPhase + distance * 0.05) * 8);
            int spriteScreenY = horizon - spriteHalf - 12 + floatOffset;
            int spriteTop = spriteScreenY - spriteHalf;
            int spriteBottom = spriteScreenY + spriteHalf;
            int spriteLeft = spriteScreenX - spriteHalf;
            int spriteRight = spriteScreenX + spriteHalf;

            double wallDistance = castRayDistance(playerAngle + angleDiff);
            if (wallDistance < distance - 1) {
                continue;
            }

            int baseColor = 0x7ED6FF;
            int glow = 0xD8F2FF;
            for (int screenX = spriteLeft; screenX < spriteRight; screenX++) {
                if (screenX < 0 || screenX >= width) continue;
                int rayIndex = screenX / 2;
                if (rayIndex < 0 || rayIndex >= rayCount) continue;
                double spritePerpDist = distance * Math.cos(angleDiff);
                if (spritePerpDist >= rayDistances[rayIndex] - 1) continue;
                for (int screenY = spriteTop; screenY < spriteBottom; screenY++) {
                    if (screenY < 0 || screenY >= height) continue;
                    int dxSprite = screenX - spriteScreenX;
                    int dySprite = screenY - spriteScreenY;
                    int absX = Math.abs(dxSprite);
                    int absY = Math.abs(dySprite);
                    if (absX + absY > spriteHalf) continue;

                    double facetShade;
                    if (dySprite < 0) {
                        facetShade = 1.15;
                    } else if (dxSprite < 0) {
                        facetShade = 0.95;
                    } else {
                        facetShade = 0.80;
                    }
                    double edge = 1.0 - (double) (absX + absY) / (spriteHalf * 2.0);
                    int color = shadeColor(baseColor, Math.max(0.6, facetShade));
                    if (absX + absY < spriteHalf / 4) {
                        color = shadeColor(glow, 0.5 + edge * 0.5);
                    }
                    double depthFactor = 1.0 - (distance / 800.0);
                    int shaded = shadeColor(color, Math.max(0.35, 0.5 + depthFactor * 0.5));
                    pixels[screenY * width + screenX] = shaded;
                }
            }
        }
    }

    private double normalizeAngle(double angle) {
        // Normalize angle into [-PI, PI]
        while (angle > Math.PI) {
            angle -= 2 * Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2 * Math.PI;
        }
        return angle;
    }

    // DDA raycasting to find wall hit and texture info
    /**
     * Cast a DDA ray at the given angle and return hit information including texture coords.
     */
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
            int tile = getMapTile(mapX, mapY);
            if (tile > 0) {
                hit = true;
                wallType = tile;
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

        int[][] texture = wallType == Door.DOOR_TILE
                ? (isExitOpen() ? Textures.DOOR_OPEN : Textures.DOOR)
                : Textures.STONE;
        return new RayHit(distance, wallType, textureX, side, texture);
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
            if (getMapTile(mapX, mapY) > 0) {
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

    /**
     * Apply a simple shading factor to an RGB color.
     */
    private int shadeColor(int rgb, double factor) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = (int) Math.max(0, Math.min(255, r * factor));
        g = (int) Math.max(0, Math.min(255, g * factor));
        b = (int) Math.max(0, Math.min(255, b * factor));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Draw a small top-down minimap showing tiles, player, start/exit and the monster.
     */
    private void drawMinimap(Graphics g) {
        int minimapSize = MAP_SIZE * 16;
        int offsetX = 10;
        int offsetY = 80;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(offsetX - 4, offsetY - 4, minimapSize + 8, minimapSize + 8);

        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                if (x == exitTileX && y == exitTileY) {
                    if (isExitOpen()) {
                        g.setColor(Color.WHITE);
                        g.fillRect(offsetX + x * 16, offsetY + y * 16, 16, 16);
                        g.setColor(Color.GRAY);
                        g.drawRect(offsetX + x * 16, offsetY + y * 16, 16, 16);
                    } else {
                        g.setColor(new Color(34, 139, 34));
                        g.fillRect(offsetX + x * 16, offsetY + y * 16, 16, 16);
                        g.setColor(new Color(120, 60, 20));
                        g.fillRect(offsetX + x * 16 + 4, offsetY + y * 16 + 2, 8, 12);
                        g.setColor(Color.BLACK);
                        g.drawRect(offsetX + x * 16 + 4, offsetY + y * 16 + 2, 8, 12);
                    }
                    continue;
                }
                g.setColor(map[y][x] > 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.fillRect(offsetX + x * 16, offsetY + y * 16, 16, 16);
            }
        }

        int px = (int) (playerX / TILE_SIZE * 16);
        int py = (int) (playerY / TILE_SIZE * 16);
        g.setColor(Color.RED);
        g.fillOval(offsetX + px - 4, offsetY + py - 4, 8, 8);
        int lineX = offsetX + px + (int) (Math.cos(playerAngle) * 12);
        int lineY = offsetY + py + (int) (Math.sin(playerAngle) * 12);
        g.drawLine(offsetX + px, offsetY + py, lineX, lineY);

        int startPx = offsetX + startTileX * 16;
        int startPy = offsetY + startTileY * 16;
        g.setColor(Color.BLUE);
        g.fillOval(startPx + 4, startPy + 4, 8, 8);

        g.setColor(Color.YELLOW);
        for (Ball ball : balls) {
            int bx = offsetX + (int) (ball.x / TILE_SIZE * 16);
            int by = offsetY + (int) (ball.y / TILE_SIZE * 16);
            g.fillOval(bx - 3, by - 3, 6, 6);
        }

        monster.drawOnMinimap(g, offsetX, offsetY, 16);
    }

    /**
     * Draw on-screen HUD including elapsed time and monster state.
     */
    private void drawHUD(Graphics g) {
        int width = getWidth();
        int padding = 12;
        int timeSeconds = (int) (remainingTimeMillis / 1000);
        int minutes = timeSeconds / 60;
        int seconds = timeSeconds % 60;
        String timerText = String.format("Time: %02d:%02d", minutes, seconds);
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(width - 220, 10, 210, 36);

        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(16f));
        g.drawString(timerText, width - 208, 32);
    }

    private class InputAdapter extends KeyAdapter {
        @Override
        /**
         * Handle key press events to set movement flags.
         */
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
        /**
         * Handle key release events to clear movement flags.
         */
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

        RayHit(double distance, int wallType, int textureX, int side, int[][] texture) {
            this.distance = distance;
            this.wallType = wallType;
            this.textureX = textureX;
            this.side = side;
            this.texture = texture;
        }
    }

    private void drawStatus(Graphics g) {
        int width = getWidth();
        int statusWidth = 240;
        int statusHeight = 60;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(10, 10, statusWidth, statusHeight);
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(16f));
        String status = "Balls: " + (OBJECTIVE_COUNT - balls.size()) + " / " + OBJECTIVE_COUNT;
        g.drawString(status, 18, 32);
        String hint = balls.isEmpty() ? "Now go to the exit." : "Collect all balls first.";
        g.drawString(hint, 18, 52);
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
        static final int[][] DOOR = createDoor();
        static final int[][] DOOR_OPEN = createOpenDoor();
        static final int[][] MARBLE = createMarble();

        /**
         * Generate a simple brick texture pattern.
         */
        private static int[][] createBrick() {
            int[][] tex = new int[TEX_SIZE][TEX_SIZE];
            for (int y = 0; y < TEX_SIZE; y++) {
                for (int x = 0; x < TEX_SIZE; x++) {
                    int block = ((x / 16) + (y / 16)) % 2 == 0 ? 0xA04030 : 0x8B2A1D; // alternating brick colors
                    tex[x][y] = block;
                }
            }
            return tex;
        }

        /**
         * Generate a simple wood planks texture.
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
         * Generate a stone texture with slight color variation.
         */
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

        /**
         * Generate a marble-like tile texture.
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
}
