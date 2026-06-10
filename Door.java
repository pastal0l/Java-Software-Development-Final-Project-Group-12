public class Door {
    public static final int DOOR_TILE = 2;
    private final int tileX;
    private final int tileY;
    private boolean open;

    public Door(int tileX, int tileY) {
        this.tileX = tileX;
        this.tileY = tileY;
        this.open = false;
    }

    public int getTileX() {
        return tileX;
    }

    public int getTileY() {
        return tileY;
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        this.open = true;
    }

    public void close() {
        this.open = false;
        this.openProgress = 0.0; //(new) reset progress on close (for restart)
    }

    public boolean isAt(int mapX, int mapY) {
        return mapX == tileX && mapY == tileY;
    }

    public int getMapValue() {
        return DOOR_TILE;
    }

    public boolean isPlayerNear(double playerX, double playerY, double tileSize) {
        double left = tileX * tileSize;
        double top = tileY * tileSize;
        double right = left + tileSize;
        double bottom = top + tileSize;
        double nearestX = Math.max(left, Math.min(playerX, right));
        double nearestY = Math.max(top, Math.min(playerY, bottom));
        double dx = playerX - nearestX;
        double dy = playerY - nearestY;
        return dx * dx + dy * dy <= 48 * 48;
    }

    private double openProgress = 0.0; // 0.0 = closed, 1.0 = fully open
    private static final double OPEN_SPEED = 0.03; // per frame (~0.5s at 60fps)

    public void updateAnimation() {
        if (open && openProgress < 1.0) {
            openProgress = Math.min(1.0, openProgress + OPEN_SPEED);
        } else if (!open && openProgress > 0.0) {
            openProgress = Math.max(0.0, openProgress - OPEN_SPEED);
        }
    }

    public double getOpenProgress() {
        return openProgress;
}
}
