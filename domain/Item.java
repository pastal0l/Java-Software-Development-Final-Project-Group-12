package domain;

import entity.Entity;
import java.awt.Graphics;

public abstract class Item extends Entity {
    
    protected double pickupRadius = 18.0;

    public Item(double x, double y) {
        super(x, y);
    }

    public boolean isPlayerNear(IPlayer player) {
        double dx = player.getX() - getX();
        double dy = player.getY() - getY();
        return dx * dx + dy * dy <= pickupRadius * pickupRadius;
    }

    /**
     * Executes the item's effect when touched.
     * @return true if the item should be removed from the map after collection.
     */
    public abstract boolean onCollect(IPlayer player);

    @Override
    public void update(double playerX, double playerY, int[][] map, int tileSize) {
        // Items are static, so this remains empty.
    }
    public abstract void drawOnMinimap(Graphics g, int screenX, int screenY);
}