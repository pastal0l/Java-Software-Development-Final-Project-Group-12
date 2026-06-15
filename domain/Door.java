package domain;
public class Door {
    public static final int DOOR_TILE = 5; // Unique ID for door tiles in the map
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
}
