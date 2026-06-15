package entity;

/**
 * Base class for all physical objects in the game world.
 */
public abstract class Entity {
    
    protected double x;
    protected double y;

    public Entity(double x, double y) {
        this.x = x;
        this.y = y;
    }

    // Common getters and setters used by the GameState and Renderer
    public double getX() { return x; }
    public double getY() { return y; }
    
    public void setPosition(double x, double y) { 
        this.x = x; 
        this.y = y; 
    }

    /**
     * Every entity must define how it behaves each frame.
     */
    public abstract void update(double playerX, double playerY, int[][] map, int tileSize);
}