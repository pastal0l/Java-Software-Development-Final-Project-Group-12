package domain;

import AI.Pathfinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MonsterEntity extends Entity {
    private static final double WALK_SPEED        = 1.0;
    private static final double CHASE_SPEED       = 2.2;
    private static final double COLLISION_RADIUS  = 16.0;
    private static final double MAX_CHASE_DISTANCE = 640.0;

    private final int    tileSize;
    private final Random random = new Random();

    private double  directionX = 1, directionY = 0;
    private boolean chasing      = false;
    private boolean pursuitActive = false;

    private List<Pathfinder.Node> currentPath          = new ArrayList<>();
    private long                  lastPathCalculationTime = 0;
    private int                   stuckFrames          = 0;
    private long                  ignoreSightUntil     = 0;

    public MonsterEntity(int tileX, int tileY, int tileSize) {
        // Pass calculated starting coordinates to Entity
        super(tileX * tileSize + tileSize / 2.0, tileY * tileSize + tileSize / 2.0);
        this.tileSize = tileSize;
    }

    public void reset(int tileX, int tileY) {
        x = tileX * tileSize + tileSize / 2.0; // Accessing protected 'x' from Entity
        y = tileY * tileSize + tileSize / 2.0; // Accessing protected 'y' from Entity
        directionX = 1; directionY = 0;
        chasing = false; pursuitActive = false;
        stuckFrames = 0; ignoreSightUntil = 0;
        currentPath.clear();
    }

    @Override // <--- Added Override annotation
    public void update(double playerX, double playerY, int[][] map, int tileSize) {
        long now = System.currentTimeMillis();
        boolean visible = (now >= ignoreSightUntil) && canSeePlayer(playerX, playerY, map, tileSize);

        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (visible && distance <= MAX_CHASE_DISTANCE) {
            pursuitActive = true;
            chasing = true;
            currentPath.clear();
            moveToTarget(playerX, playerY, CHASE_SPEED, map, tileSize);
        } else if (pursuitActive && visible && distance <= MAX_CHASE_DISTANCE) {
            chasing = true;
            followAStarPath(playerX, playerY, map, tileSize, CHASE_SPEED);
        } else {
            pursuitActive = false;
            chasing = false;
            wander(map, tileSize);
        }
    }

    public boolean collidesWithPlayer(double playerX, double playerY) {
        double dx = playerX - x;
        double dy = playerY - y;
        return Math.sqrt(dx * dx + dy * dy) < COLLISION_RADIUS;
    }

    public boolean canSeePlayer(double playerX, double playerY, int[][] map, int tileSize) {
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= 0) return true;
        int steps = Math.max(1, (int) (distance / 8));
        for (int step = 0; step <= steps; step++) {
            double progress = step / (double) steps;
            int mapX = (int) (x + dx * progress) / tileSize;
            int mapY = (int) (y + dy * progress) / tileSize;
            if (mapX < 0 || mapX >= map[0].length || mapY < 0 || mapY >= map.length) return false;
            if (map[mapY][mapX] > 0) return false;
        }
        return true;
    }

    // ── getters ──────────────────────────────────────────────────────────────
    public int     getTileSize() { return tileSize; }
    public boolean isChasing()   { return chasing; }
    
    /** Used by client-side rendering to reflect server state. */
    public void setChasing(boolean chasing)      { this.chasing = chasing; }

    /** Returns true if the monster is roughly facing toward the player (dot product > 0). */
    public boolean isFacingPlayer(double playerX, double playerY) {
        if (directionX == 0 && directionY == 0) return true;
        double dx = playerX - x;
        double dy = playerY - y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1e-3) return true;
        double dot = (dx / dist) * directionX + (dy / dist) * directionY;
        return dot > 0.0;
    }

    // ── private movement helpers (unchanged logic) ────────────────────────

    private void followAStarPath(double targetX, double targetY,
                                  int[][] map, int tileSize, double speed) {
        int cgx = (int) (x / tileSize), cgy = (int) (y / tileSize);
        int tgx = (int) (targetX / tileSize), tgy = (int) (targetY / tileSize);
        long now = System.currentTimeMillis();
        double cx = cgx * tileSize + tileSize / 2.0;
        double cy = cgy * tileSize + tileSize / 2.0;
        boolean nearCenter = Math.abs(x - cx) < 4.0 && Math.abs(y - cy) < 4.0;

        if (currentPath.isEmpty() || (nearCenter && now - lastPathCalculationTime > 500)) {
            currentPath = Pathfinder.findPath(map, cgx, cgy, tgx, tgy);
            lastPathCalculationTime = now;
        }
        if (!currentPath.isEmpty()) {
            Pathfinder.Node next = currentPath.get(0);
            double destX = next.gridX * tileSize + tileSize / 2.0;
            double destY = next.gridY * tileSize + tileSize / 2.0;
            moveToTarget(destX, destY, speed, map, tileSize);
            if (Math.abs(x - destX) < 5.0 && Math.abs(y - destY) < 5.0)
                currentPath.remove(0);
        }
    }

    private void wander(int[][] map, int tileSize) {
        if (currentPath.isEmpty()) {
            int cgx = (int) (x / tileSize), cgy = (int) (y / tileSize);
            int cdx = (Math.abs(directionX) > Math.abs(directionY))
                    ? (directionX > 0 ? 1 : -1) : 0;
            int cdy = (cdx == 0 && Math.abs(directionY) > 0)
                    ? (directionY > 0 ? 1 : -1) : (cdx == 0 ? 0 : 0);
            if (cdx == 0 && cdy == 0) cdx = 1;

            int[][] dirs = {{0,1},{1,0},{0,-1},{-1,0}};
            List<int[]> valid = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = cgx + d[0], ny = cgy + d[1];
                if (nx >= 0 && nx < map[0].length && ny >= 0 && ny < map.length
                        && map[ny][nx] == 0
                        && !(d[0] == -cdx && d[1] == -cdy))
                    valid.add(d);
            }
            if (valid.isEmpty()) {
                for (int[] d : dirs) {
                    int nx = cgx + d[0], ny = cgy + d[1];
                    if (nx >= 0 && nx < map[0].length && ny >= 0 && ny < map.length
                            && map[ny][nx] == 0)
                        valid.add(d);
                }
            }
            if (!valid.isEmpty()) {
                int[] chosen = valid.get(random.nextInt(valid.size()));
                currentPath.add(new Pathfinder.Node(cgx + chosen[0], cgy + chosen[1]));
            }
        }
        if (!currentPath.isEmpty()) {
            Pathfinder.Node next = currentPath.get(0);
            double destX = next.gridX * tileSize + tileSize / 2.0;
            double destY = next.gridY * tileSize + tileSize / 2.0;
            moveToTarget(destX, destY, WALK_SPEED, map, tileSize);
            if (Math.abs(x - destX) < 5.0 && Math.abs(y - destY) < 5.0)
                currentPath.clear();
        }
    }

    private void moveToTarget(double destX, double destY,
                               double speed, int[][] map, int tileSize) {
        double dx = destX - x, dy = destY - y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist <= 0.1) return;

        directionX = dx / dist;
        directionY = dy / dist;
        double prevX = x, prevY = y;

        if (!collides(x + directionX * speed, y, map, tileSize)) x += directionX * speed;
        if (!collides(x, y + directionY * speed, map, tileSize)) y += directionY * speed;

        if (Math.abs(x - prevX) < 0.001 && Math.abs(y - prevY) < 0.001) {
            if (++stuckFrames > 15) {
                stuckFrames = 0;
                pursuitActive = false;
                chasing = false;
                ignoreSightUntil = System.currentTimeMillis() + 1500;
                currentPath.clear();
            }
        } else {
            stuckFrames = 0;
        }
    }

    private boolean collides(double cx, double cy, int[][] map, int tileSize) {
        double p = 12.0;
        int left   = (int) (cx - p) / tileSize, right  = (int) (cx + p) / tileSize;
        int top    = (int) (cy - p) / tileSize, bottom = (int) (cy + p) / tileSize;
        if (left < 0 || right >= map[0].length || top < 0 || bottom >= map.length) return true;
        return map[top][left] != 0 || map[top][right] != 0
            || map[bottom][left] != 0 || map[bottom][right] != 0;
    }
}