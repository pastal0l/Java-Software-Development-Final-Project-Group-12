package rendering;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

import domain.MonsterEntity;

public class MonsterRenderer {

    private final MonsterEntity monster;
    private final BufferedImage spriteFront;
    private final BufferedImage spriteBack;

    public MonsterRenderer(MonsterEntity monster) {
        this.monster     = monster;
        this.spriteFront = loadFirst("asset/monster_front.png", "assets/monster_front.png",
                                     "monster_front.png", "asset/monster.png", "monster.png");
        BufferedImage back = loadFirst("asset/monster_back.png", "assets/monster_back.png", "monster_back.png");
        this.spriteBack  = back != null ? back : spriteFront;
    }

    /** Returns front or back sprite depending on whether the monster faces the player. */
    public BufferedImage getSprite(double playerX, double playerY) {
        BufferedImage img = monster.isFacingPlayer(playerX, playerY) ? spriteFront : spriteBack;
        return img != null ? img : spriteFront;
    }

    public void drawOnMinimap(Graphics g, int offsetX, int offsetY, int cellSize) {
        int px = offsetX + (int)(monster.getX() / monster.getTileSize() * cellSize);
        int py = offsetY + (int)(monster.getY() / monster.getTileSize() * cellSize);
        g.setColor(new Color(230, 40, 40));
        g.fillOval(px - 6, py - 6, 12, 12);
        g.setColor(monster.isChasing() ? Color.MAGENTA : Color.ORANGE);
        g.drawOval(px - 6, py - 6, 12, 12);
    }

    private static BufferedImage loadFirst(String... paths) {
        for (String path : paths) {
            try {
                File f = new File(path);
                if (f.exists()) return ImageIO.read(f);
            } catch (IOException ignored) {}
        }
        return null;
    }
}