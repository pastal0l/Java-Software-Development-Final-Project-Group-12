import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;


public class Monster {
    private static final double WALK_SPEED = 1.0;
    private static final double CHASE_SPEED = 2.2;
    private static final double COLLISION_RADIUS = 16.0;
    private static final Color BODY_COLOR = new Color(230, 40, 40);
    private static final double MAX_CHASE_DISTANCE = 640.0;

    private final int tileSize;
    private final Random random = new Random();
    private double x;
    private double y;
    private double directionX;
    private double directionY;
    private boolean chasing;
    private boolean pursuitActive;
    private BufferedImage spriteIdle = null;
    private BufferedImage spriteChase = null;

    public Monster(int tileX, int tileY, int tileSize) {
        this.tileSize = tileSize;
        this.x = tileX * tileSize + tileSize / 2.0;
        this.y = tileY * tileSize + tileSize / 2.0;
        this.directionX = 1;
        this.directionY = 0;
        this.chasing = false;
        this.pursuitActive = false;
        loadSprites();
    }

    public void reset(int tileX, int tileY) {
        this.x = tileX * tileSize + tileSize / 2.0;
        this.y = tileY * tileSize + tileSize / 2.0;
        this.directionX = 1;
        this.directionY = 0;
        this.chasing = false;
        this.pursuitActive = false;
        loadSprites();
    }

    private void loadSprites() {
        String[] names = {"assets/monster.png", "asset/monster.png", "monster.png"};
        for (String n : names) {
            try {
                File f = new File(n);
                if (f.exists()) {
                    if (spriteIdle == null) spriteIdle = ImageIO.read(f);
                    if (spriteChase == null) spriteChase = ImageIO.read(f);
                }
            } catch (IOException ignored) {
            }
        }
        if (spriteIdle != null && spriteChase == null) spriteChase = spriteIdle;
    }

    public BufferedImage getSprite() {
        if (chasing) return spriteChase != null ? spriteChase : spriteIdle;
        return spriteIdle;
    }

    public void update(double playerX, double playerY, int[][] map, int tileSize) {
        boolean visible = canSeePlayer(playerX, playerY, map, tileSize);
        boolean ahead = isPlayerAhead(playerX, playerY);
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        // Behavior rules:
        // - If already in pursuit, continue chasing solely based on distance (persistence).
        // - If idle, only start chasing when the player is visible and roughly ahead.
        if (pursuitActive) {
            // Continue chasing until player is far enough to give up.
            if (distance <= MAX_CHASE_DISTANCE) {
                chasing = true;
                chasePlayer(playerX, playerY, map, tileSize);
            } else {
                // Lost due to distance; stop chasing and resume wandering.
                pursuitActive = false;
                chasing = false;
                walk(map, tileSize);
            }
        } else {
            // Only initiate chase when the monster currently sees the player and player is ahead.
            if (visible && ahead && distance <= MAX_CHASE_DISTANCE) {
                pursuitActive = true;
                chasing = true;
                chasePlayer(playerX, playerY, map, tileSize);
            } else {
                chasing = false;
                walk(map, tileSize);
            }
        }
    }

    private boolean isPlayerAhead(double playerX, double playerY) {
        if (directionX == 0 && directionY == 0) return false;
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 1e-3) return true;
        double ndx = dx / distance;
        double ndy = dy / distance;
        double dot = ndx * directionX + ndy * directionY;
        return dot > 0.3;
    }

    private void chasePlayer(double playerX, double playerY, int[][] map, int tileSize) {
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 1e-3) return;
        double step = Math.min(CHASE_SPEED, distance);
        double nextX = x + dx / distance * step;
        double nextY = y + dy / distance * step;
        if (!collides(nextX, y, map, tileSize)) x = nextX;
        if (!collides(x, nextY, map, tileSize)) y = nextY;
    }

    private void walk(int[][] map, int tileSize) {
        if (!canMoveDirection(directionX, directionY, map, tileSize)) chooseNewDirection(map, tileSize);
        double nextX = x + directionX * WALK_SPEED;
        double nextY = y + directionY * WALK_SPEED;
        boolean canMoveX = !collides(nextX, y, map, tileSize);
        boolean canMoveY = !collides(x, nextY, map, tileSize);
        if (canMoveX && canMoveY) { x = nextX; y = nextY; return; }
        if (canMoveX) { x = nextX; return; }
        if (canMoveY) { y = nextY; return; }
        chooseNewDirection(map, tileSize);
    }

    private boolean canMoveDirection(double dx, double dy, int[][] map, int tileSize) {
        if (dx == 0 && dy == 0) return false;
        return !collides(x + dx * WALK_SPEED, y, map, tileSize) && !collides(x, y + dy * WALK_SPEED, map, tileSize);
    }

    private void chooseNewDirection(int[][] map, int tileSize) {
        int tileX = (int) x / tileSize;
        int tileY = (int) y / tileSize;
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        int[] options = new int[4];
        int count = 0;
        int oppositeIndex = getOppositeDirectionIndex();
        for (int i = 0; i < 4; i++) {
            int nx = tileX + dx[i];
            int ny = tileY + dy[i];
            if (nx < 0 || nx >= map[0].length || ny < 0 || ny >= map.length) continue;
            if (map[ny][nx] == 0 && i != oppositeIndex) options[count++] = i;
        }
        if (count == 0) {
            for (int i = 0; i < 4; i++) {
                int nx = tileX + dx[i];
                int ny = tileY + dy[i];
                if (nx < 0 || nx >= map[0].length || ny < 0 || ny >= map.length) continue;
                if (map[ny][nx] == 0) options[count++] = i;
            }
        }
        if (count == 0) { directionX = 0; directionY = 0; return; }
        int choice = options[random.nextInt(count)];
        directionX = dx[choice]; directionY = dy[choice];
    }

    private int getOppositeDirectionIndex() {
        if (directionX > 0) return 1;
        if (directionX < 0) return 0;
        if (directionY > 0) return 3;
        if (directionY < 0) return 2;
        return -1;
    }

    public boolean canSeePlayer(double playerX, double playerY, int[][] map, int tileSize) {
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= 0) return true;
        int steps = Math.max(1, (int) (distance / 8));
        for (int step = 0; step <= steps; step++) {
            double progress = step / (double) steps;
            double sampleX = x + dx * progress;
            double sampleY = y + dy * progress;
            int mapX = (int) sampleX / tileSize;
            int mapY = (int) sampleY / tileSize;
            if (mapX < 0 || mapX >= map[0].length || mapY < 0 || mapY >= map.length) return false;
            if (map[mapY][mapX] > 0) return false;
        }
        return true;
    }

    private boolean collides(double x, double y, int[][] map, int tileSize) {
        int mapX = (int) x / tileSize;
        int mapY = (int) y / tileSize;
        if (mapX < 0 || mapX >= map[0].length || mapY < 0 || mapY >= map.length) return true;
        return map[mapY][mapX] != 0;
    }

    public boolean collidesWithPlayer(double playerX, double playerY, int tileSize) {
        double dx = playerX - x;
        double dy = playerY - y;
        return Math.sqrt(dx * dx + dy * dy) < COLLISION_RADIUS;
    }

    public boolean isChasing() { return chasing; }
    public double getX() { return x; }
    public double getY() { return y; }

    public void drawOnMinimap(Graphics g, int offsetX, int offsetY, int minimapCellSize) {
        double ratioX = x / tileSize;
        double ratioY = y / tileSize;
        int px = offsetX + (int) (ratioX * minimapCellSize);
        int py = offsetY + (int) (ratioY * minimapCellSize);
        g.setColor(BODY_COLOR);
        g.fillOval(px - 6, py - 6, 12, 12);
        g.setColor(chasing ? Color.MAGENTA : Color.ORANGE);
        g.drawOval(px - 6, py - 6, 12, 12);
    }
}
