package domain;

import java.awt.Color;
import java.awt.Graphics;


public class Ball extends Item {

    public Ball(double startX, double startY) {
        // Pass the starting coordinates up to the Entity class
        super(startX, startY);
    }

    @Override
    public boolean onCollect(IPlayer player) {
        // Diamonds are always picked up immediately when touched.
        return true; 
    }

    @Override
    public void drawOnMinimap(Graphics g, int screenX, int screenY) {
        g.setColor(Color.MAGENTA);
        g.fillOval(screenX - 4, screenY - 4, 8, 8);
        g.setColor(Color.YELLOW);
        g.fillOval(screenX - 3, screenY - 3, 6, 6);
    }
}