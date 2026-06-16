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

        // Draw a directional triangle pointing in the monster's facing direction
        double angle  = monster.getFacingAngle();
        int    tipR   = 8;   // tip distance from centre
        int    baseR  = 6;   // base half-width distance
        double backA  = angle + Math.PI; // opposite direction

        int[] xs = {
            px + (int)(Math.cos(angle)                   * tipR),
            px + (int)(Math.cos(backA - Math.toRadians(40)) * baseR),
            px + (int)(Math.cos(backA + Math.toRadians(40)) * baseR)
        };
        int[] ys = {
            py + (int)(Math.sin(angle)                   * tipR),
            py + (int)(Math.sin(backA - Math.toRadians(40)) * baseR),
            py + (int)(Math.sin(backA + Math.toRadians(40)) * baseR)
        };

        g.setColor(monster.isChasing() ? new Color(255, 30, 200) : new Color(230, 40, 40));
        g.fillPolygon(xs, ys, 3);
        g.setColor(Color.BLACK);
        g.drawPolygon(xs, ys, 3);
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