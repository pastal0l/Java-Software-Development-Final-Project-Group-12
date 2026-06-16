package rendering;

import java.awt.Graphics;
import domain.GameState;
import network.RemotePlayer;

/**
 * Defines the contract for rendering the game.
 * Completely decoupled from the UI layer.
 */
public interface IRenderer {
    
    void render(Graphics g, int width, int height, GameState state, 
                double playerX, double playerY, double playerAngle, 
                boolean sprinting, double staminaPct, boolean exhausted, 
                RemotePlayer remotePlayer, boolean isMultiplayer);
                
}