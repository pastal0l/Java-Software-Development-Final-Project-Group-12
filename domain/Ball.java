package domain;

import entity.Entity;

public class Ball extends Entity {

    public Ball(double startX, double startY) {
        // Pass the starting coordinates up to the Entity class
        super(startX, startY);
    }

    @Override
    public void update(double playerX, double playerY, int[][] map, int tileSize) {
        // Balls are static items, so they don't do anything on update!
    }
}