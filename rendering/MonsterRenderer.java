package rendering;

import entity.MonsterEntity;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

// rendering/MonsterRenderer.java
public class MonsterRenderer {

    private final MonsterEntity monster;   // bound at construction
    private final BufferedImage spriteIdle;
    private final BufferedImage spriteChase;

    public MonsterRenderer(MonsterEntity monster) {
        this.monster     = monster;
        BufferedImage loaded = loadSprite();
        this.spriteIdle  = loaded;
        this.spriteChase = loaded;
    }

    // Exact same signature as the original
    public BufferedImage getSprite() {
        return monster.isChasing() ? spriteChase : spriteIdle;
    }

    public void drawOnMinimap(Graphics g, int offsetX, int offsetY, int cellSize) {
        int px = offsetX + (int)(monster.getX() / monster.getTileSize() * cellSize);
        int py = offsetY + (int)(monster.getY() / monster.getTileSize() * cellSize);
        g.setColor(new Color(230, 40, 40));
        g.fillOval(px - 6, py - 6, 12, 12);
        g.setColor(monster.isChasing() ? Color.MAGENTA : Color.ORANGE);
        g.drawOval(px - 6, py - 6, 12, 12);
    }

    private static BufferedImage loadSprite() {
        for (String path : new String[]{"assets/monster.png", "asset/monster.png", "monster.png"}) {
            try {
                File f = new File(path);
                if (f.exists()) return ImageIO.read(f);
            } catch (IOException ignored) {}
        }
        return null;
    }
}