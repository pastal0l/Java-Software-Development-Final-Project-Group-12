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
    
    // Anti-Stuck variables
    private int stuckFrames = 0;
    private long ignoreSightUntil = 0;
    
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
        this.stuckFrames = 0;
        this.ignoreSightUntil = 0;
        currentPath.clear();
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
        long currentTime = System.currentTimeMillis();
        
        // Anti-stuck: If the monster got snagged on a corner, it temporarily "blinds" 
        // itself to force the A* pathfinder to navigate around the obstacle.
        if (currentTime < ignoreSightUntil) {
            visible = false;
        }

        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (visible && distance <= MAX_CHASE_DISTANCE) {
            pursuitActive = true;
            chasing = true;
            currentPath.clear(); 
            moveToTarget(playerX, playerY, CHASE_SPEED, map, tileSize);
        } 
        else if (pursuitActive && distance <= MAX_CHASE_DISTANCE) {
            chasing = true;
            followAStarPath(playerX, playerY, map, tileSize, CHASE_SPEED);
        } 
        else {
            pursuitActive = false;
            chasing = false;
            wander(map, tileSize);
        }
    }

    private void followAStarPath(double targetX, double targetY, int[][] map, int tileSize, double speed) {
        int currentGridX = (int) (x / tileSize);
        int currentGridY = (int) (y / tileSize);
        int targetGridX = (int) (targetX / tileSize);
        int targetGridY = (int) (targetY / tileSize);

        long currentTime = System.currentTimeMillis();
        
        double centerX = currentGridX * tileSize + tileSize / 2.0;
        double centerY = currentGridY * tileSize + tileSize / 2.0;
        boolean nearCenter = Math.abs(x - centerX) < 4.0 && Math.abs(y - centerY) < 4.0;

        if (currentPath == null || currentPath.isEmpty() || (nearCenter && currentTime - lastPathCalculationTime > 500)) {
            currentPath = Pathfinder.findPath(map, currentGridX, currentGridY, targetGridX, targetGridY);
            lastPathCalculationTime = currentTime;
        }

        if (currentPath != null && !currentPath.isEmpty()) {
            Pathfinder.Node nextNode = currentPath.get(0);
            
            double destX = nextNode.gridX * tileSize + tileSize / 2.0;
            double destY = nextNode.gridY * tileSize + tileSize / 2.0;

            moveToTarget(destX, destY, speed, map, tileSize);

            // Increased threshold to 5.0 to prevent overshooting at higher speeds
            if (Math.abs(x - destX) < 5.0 && Math.abs(y - destY) < 5.0) {
                currentPath.remove(0);
            }
        }
    }

    private void wander(int[][] map, int tileSize) {
        if (currentPath == null || currentPath.isEmpty()) {
            int currentGridX = (int) (x / tileSize);
            int currentGridY = (int) (y / tileSize);
            
            int currentDirX = 0;
            int currentDirY = 0;
            if (Math.abs(directionX) > Math.abs(directionY)) {
                currentDirX = directionX > 0 ? 1 : -1;
            } else if (Math.abs(directionY) > 0) {
                currentDirY = directionY > 0 ? 1 : -1;
            } else {
                currentDirX = 1; 
            }
            
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

            if (!canGoStraight) {
                int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
                List<int[]> validDirs = new ArrayList<>();
                
                for (int[] d : directions) {
                    int nx = currentGridX + d[0];
                    int ny = currentGridY + d[1];
                    if (nx >= 0 && nx < map[0].length && ny >= 0 && ny < map.length && map[ny][nx] == 0) {
                        if (d[0] != -currentDirX || d[1] != -currentDirY) {
                            validDirs.add(d);
                        }
                    }
                }
                
                if (validDirs.isEmpty()) {
                    for (int[] d : directions) {
                        int nx = currentGridX + d[0];
                        int ny = currentGridY + d[1];
                        if (nx >= 0 && nx < map[0].length && ny >= 0 && ny < map.length && map[ny][nx] == 0) {
                            validDirs.add(d);
                        }
                    }
                }
                
                if (!validDirs.isEmpty()) {
                    int[] chosen = validDirs.get(random.nextInt(validDirs.size()));
                    nextX = chosen[0];
                    nextY = chosen[1];
                } else {
                    nextX = 0; 
                    nextY = 0; 
                }
            }
            
            if (nextX != 0 || nextY != 0) {
                currentPath = new ArrayList<>();
                currentPath.add(new Pathfinder.Node(currentGridX + nextX, currentGridY + nextY));
            }
        }
        
        if (currentPath != null && !currentPath.isEmpty()) {
            Pathfinder.Node nextNode = currentPath.get(0);
            double destX = nextNode.gridX * tileSize + tileSize / 2.0;
            double destY = nextNode.gridY * tileSize + tileSize / 2.0;

            moveToTarget(destX, destY, WALK_SPEED, map, tileSize);

            if (Math.abs(x - destX) < 5.0 && Math.abs(y - destY) < 5.0) {
                currentPath.clear();
            }
        }
    }

   private void moveToTarget(double destX, double destY, double speed, int[][] map, int tileSize) {
        double dx = destX - x;
        double dy = destY - y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        
        if (dist > 0.1) {
            directionX = dx / dist;
            directionY = dy / dist;
            
            double nextX = x + directionX * speed;
            double nextY = y + directionY * speed;
            
            double prevX = x;
            double prevY = y;

            if (!collides(nextX, y, map, tileSize)) x = nextX;
            if (!collides(x, nextY, map, tileSize)) y = nextY;
            
            // --- SMART STUCK DETECTION ---
            // If we are trying to move but haven't changed coordinates, we hit a snag.
            if (Math.abs(x - prevX) < 0.001 && Math.abs(y - prevY) < 0.001) {
                stuckFrames++;
                if (stuckFrames > 15) { // Stuck for roughly 1/4th of a second (15 frames)
                    stuckFrames = 0;
                    
                    // 1. Change the monster's state back to normal
                    pursuitActive = false;
                    chasing = false;
                    
                    // 2. Ignore the player for 1.5 seconds so it actually walks away
                    ignoreSightUntil = System.currentTimeMillis() + 1500; 
                    
                    // 3. Clear the path so the wander() method generates a new random direction
                    if (currentPath != null) currentPath.clear(); 
                }
            } else {
                stuckFrames = 0; // Reset if we successfully moved
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

    // UPDATED: Now uses a Hitbox rather than a single center point
    private boolean collides(double checkX, double checkY, int[][] map, int tileSize) {
        double padding = 12.0; // Acts as the physical width of the monster
        
        // Check the 4 corners of the monster's bounding box
        int left = (int) (checkX - padding) / tileSize;
        int right = (int) (checkX + padding) / tileSize;
        int top = (int) (checkY - padding) / tileSize;
        int bottom = (int) (checkY + padding) / tileSize;

        if (left < 0 || right >= map[0].length || top < 0 || bottom >= map.length) return true;

        return map[top][left] != 0 || map[top][right] != 0 || 
               map[bottom][left] != 0 || map[bottom][right] != 0;
    }

    public boolean collidesWithPlayer(double playerX, double playerY, int tileSize) {
        double dx = playerX - x;
        double dy = playerY - y;
        return Math.sqrt(dx * dx + dy * dy) < COLLISION_RADIUS;
    }

    public boolean isChasing() { return chasing; }
    public double  getX()      { return x; }
    public double  getY()      { return y; }

    /** Override world position — used in multiplayer where the server drives AI. */
    public void setPosition(double x, double y) { this.x = x; this.y = y; }
    /** Override chase flag — used in multiplayer rendering. */
    public void setChasing(boolean chasing)      { this.chasing = chasing; }

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