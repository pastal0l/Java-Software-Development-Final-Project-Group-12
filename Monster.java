import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;


public class Monster {
    private List<Pathfinder.Node> currentPath = new ArrayList<>();
    private long lastPathCalculationTime = 0;
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
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        // Start or maintain pursuit if the player is visible, or if already chasing
        if ((visible || pursuitActive) && distance <= MAX_CHASE_DISTANCE) {
            pursuitActive = true;
            chasing = true;
            followAStarPath(playerX, playerY, map, tileSize, CHASE_SPEED);
        } else {
            // Lost the player, go back to wandering
            pursuitActive = false;
            chasing = false;
            wander(map, tileSize);
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


    // Smooth movement helper
private void moveToTarget(double destX, double destY, double speed, int[][] map, int tileSize) {
    double dx = destX - x;
    double dy = destY - y;
    double dist = Math.sqrt(dx * dx + dy * dy);
    
    if (dist > 0.1) {
        // Normalize direction and apply speed
        directionX = dx / dist;
        directionY = dy / dist;
        
        double nextX = x + directionX * speed;
        double nextY = y + directionY * speed;
        
        // Final collision check just to be safe
        if (!collides(nextX, y, map, tileSize)) x = nextX;
        if (!collides(x, nextY, map, tileSize)) y = nextY;
    }
}

// Patrolling logic when the player is out of sight
private void wander(int[][] map, int tileSize) {
    // If we don't have a tile to walk to, we need to pick one based on our momentum
    if (currentPath == null || currentPath.isEmpty()) {
        int currentGridX = (int) (x / tileSize);
        int currentGridY = (int) (y / tileSize);
        
        // 1. Determine our current primary moving direction
        int currentDirX = 0;
        int currentDirY = 0;
        if (Math.abs(directionX) > Math.abs(directionY)) {
            currentDirX = directionX > 0 ? 1 : -1;
        } else if (Math.abs(directionY) > 0) {
            currentDirY = directionY > 0 ? 1 : -1;
        } else {
            currentDirX = 1; // Default starting direction if completely stationary
        }
        
        // 2. Check if we can keep going straight
        boolean canGoStraight = false;
        int checkX = currentGridX + currentDirX;
        int checkY = currentGridY + currentDirY;
        if (checkX >= 0 && checkX < map[0].length && checkY >= 0 && checkY < map.length) {
            if (map[checkY][checkX] == 0) {
                canGoStraight = true;
            }
        }
        
        int nextX = currentDirX;
        int nextY = currentDirY;

        // 3. If we hit a wall, pick a new valid direction
        if (!canGoStraight) {
            int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
            List<int[]> validDirs = new ArrayList<>();
            
            // Find all valid adjacent tiles
            for (int[] d : directions) {
                int nx = currentGridX + d[0];
                int ny = currentGridY + d[1];
                if (nx >= 0 && nx < map[0].length && ny >= 0 && ny < map.length && map[ny][nx] == 0) {
                    // Try to avoid instantly reversing direction (e.g. bouncing back and forth) 
                    if (d[0] != -currentDirX || d[1] != -currentDirY) {
                        validDirs.add(d);
                    }
                }
            }
            
            // If it's trapped in a dead end, allow it to turn completely around
            if (validDirs.isEmpty()) {
                for (int[] d : directions) {
                    int nx = currentGridX + d[0];
                    int ny = currentGridY + d[1];
                    if (nx >= 0 && nx < map[0].length && ny >= 0 && ny < map.length && map[ny][nx] == 0) {
                        validDirs.add(d);
                    }
                }
            }
            
            // Pick randomly from the valid options
            if (!validDirs.isEmpty()) {
                int[] chosen = validDirs.get(random.nextInt(validDirs.size()));
                nextX = chosen[0];
                nextY = chosen[1];
            } else {
                nextX = 0; 
                nextY = 0; // Completely stuck
            }
        }
        
        // 4. Assign the next tile to move to
        if (nextX != 0 || nextY != 0) {
            currentPath = new ArrayList<>();
            currentPath.add(new Pathfinder.Node(currentGridX + nextX, currentGridY + nextY));
        }
    }
    
    // 5. Smoothly move towards the chosen tile
    if (currentPath != null && !currentPath.isEmpty()) {
        Pathfinder.Node nextNode = currentPath.get(0);
        double destX = nextNode.gridX * tileSize + tileSize / 2.0;
        double destY = nextNode.gridY * tileSize + tileSize / 2.0;

        moveToTarget(destX, destY, WALK_SPEED, map, tileSize);

        // Once it reaches the tile center, clear it so we calculate the next step
        if (Math.abs(x - destX) < 2.0 && Math.abs(y - destY) < 2.0) {
            currentPath.clear();
        }
    }
}
   private void followAStarPath(double targetX, double targetY, int[][] map, int tileSize, double speed) {
    int currentGridX = (int) (x / tileSize);
    int currentGridY = (int) (y / tileSize);
    int targetGridX = (int) (targetX / tileSize);
    int targetGridY = (int) (targetY / tileSize);

    long currentTime = System.currentTimeMillis();

    // Recalculate path every 500ms to adapt to player movement without hurting performance
    if (currentPath == null || currentPath.isEmpty() || currentTime - lastPathCalculationTime > 500) {
        currentPath = Pathfinder.findPath(map, currentGridX, currentGridY, targetGridX, targetGridY);
        lastPathCalculationTime = currentTime;
    }

    // If a path exists, move towards the first node in the list
    if (currentPath != null && !currentPath.isEmpty()) {
        Pathfinder.Node nextNode = currentPath.get(0);
        
        // Target the center of the next tile
        double destX = nextNode.gridX * tileSize + tileSize / 2.0;
        double destY = nextNode.gridY * tileSize + tileSize / 2.0;

        moveToTarget(destX, destY, speed, map, tileSize);

        // If we reached the center of the current target node, remove it so we move to the next
        if (Math.abs(x - destX) < 2.0 && Math.abs(y - destY) < 2.0) {
            currentPath.remove(0);
        }
    }
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
