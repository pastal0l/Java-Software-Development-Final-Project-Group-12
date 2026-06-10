import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Renderer handles all visual output: 3-D raycasted scene, sprite drawing,
 * minimap, HUD, status bar, and game-over / level-complete overlay.
 *
 * It holds a reference to {@link GamePanel} and reads state each frame.
 * MAP_SIZE is read from {@code game.config.mapSize} so all levels work
 * without any extra wiring.
 */
class Renderer {

    private static final int TILE_SIZE  = GamePanel.TILE_SIZE;
    private static final int TEX_SIZE   = GamePanel.TEX_SIZE;
    /** Minimap display size in pixels; tile pixel-size adapts per level. */
    private static final int MINIMAP_PX = 200;

    private final GamePanel game;
    private BufferedImage   screenBuffer;
    private int[]           screenPixels;

    Renderer(GamePanel game) {
        this.game = game;
    }

    // -----------------------------------------------------------------------
    // Public entry-point
    // -----------------------------------------------------------------------

    void render(Graphics g) {
        drawScene(g);
        drawMinimap(g);
        drawHUD(g);
        drawStatus(g);
        if (game.gameOverMenu) drawGameOverMenu(g);
    }

    // -----------------------------------------------------------------------
    // 3-D scene
    // -----------------------------------------------------------------------

    private void drawScene(Graphics g) {
        int width  = game.getWidth();
        int height = game.getHeight();
        if (width <= 0 || height <= 0) return;

        if (screenBuffer == null
                || screenBuffer.getWidth()  != width
                || screenBuffer.getHeight() != height) {
            screenBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            screenPixels = ((DataBufferInt) screenBuffer.getRaster().getDataBuffer()).getData();
        }

        int horizon    = height / 2;
        int skyColor   = (18 << 16) | (24 << 8) | 60;
        int floorColor = (16 << 16) | (20 << 8) | 32;

        int row = 0;
        for (int y = 0; y < horizon; y++) {
            int off = row++ * width;
            for (int x = 0; x < width; x++) screenPixels[off + x] = skyColor;
        }
        for (int y = horizon; y < height; y++) {
            int off = row++ * width;
            for (int x = 0; x < width; x++) screenPixels[off + x] = floorColor;
        }

        double fov        = Math.toRadians(60);
        int    rayCount   = width / 2;
        double rayStep    = fov / rayCount;
        double startAngle = game.playerAngle - fov / 2;
        double[] rayDistances = new double[rayCount];

        for (int ray = 0; ray < rayCount; ray++) {
            double rayAngle      = startAngle + ray * rayStep;
            RayHit hit           = castRay(rayAngle);
            double correctedDist = hit.distance * Math.cos(rayAngle - game.playerAngle);
            rayDistances[ray]    = correctedDist;

            int lineHeight = Math.max(1, Math.min((int) ((TILE_SIZE * height) / correctedDist), height));
            int lineOffset = horizon - lineHeight / 2;
            int drawStart  = Math.max(0, lineOffset);
            int drawEnd    = Math.min(height - 1, lineOffset + lineHeight);
            int screenX    = ray * 2;
            if (screenX < 0) continue;

            for (int xOff = 0; xOff < 2; xOff++) {
                int px = screenX + xOff;
                if (px < 0 || px >= width) continue;
                for (int y = drawStart; y <= drawEnd; y++) {
                    int texY  = Math.max(0, Math.min(TEX_SIZE - 1,
                            (int) (((y - lineOffset) * TEX_SIZE) / (double) lineHeight)));
                    int color = hit.texture[hit.textureX][texY];
                    if (!(hit.wallType == Door.DOOR_TILE && game.isExitOpen())) {
                        if (hit.side == 1) color = shadeColor(color, 0.70);
                        color = shadeColor(color,
                                Math.max(0.20, 1.0 / (1.0 + correctedDist * correctedDist * 0.00005)));
                    }
                    screenPixels[y * width + px] = color;
                }
            }
        }

        drawBallSprites(screenPixels, width, height, horizon,
                startAngle, rayStep, rayCount, rayDistances);

        g.drawImage(screenBuffer, 0, 0, null);
        drawAllMonsterSprites(g, width, height);
    }

    // -----------------------------------------------------------------------
    // Monster sprite rendering (one call per monster, back-to-front)
    // -----------------------------------------------------------------------

    private void drawAllMonsterSprites(Graphics g, int width, int height) {
        // Painter's algorithm: draw farthest monsters first so near ones
        // visually overlap them correctly.
        game.monsters.stream()
            .sorted((a, b) -> {
                double da = Math.hypot(a.getX() - game.playerX, a.getY() - game.playerY);
                double db = Math.hypot(b.getX() - game.playerX, b.getY() - game.playerY);
                return Double.compare(db, da);   // descending — farthest first
            })
            .forEach(m -> drawMonsterSprite(g, width, height, m));
    }

    private void drawMonsterSprite(Graphics g, int width, int height, Monster m) {
        double dx = m.getX() - game.playerX;
        double dy = m.getY() - game.playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= 0) return;

        double angleToMonster = Math.atan2(dy, dx);
        double relativeAngle  = normalizeAngle(angleToMonster - game.playerAngle);
        double fov = Math.toRadians(60);
        if (Math.abs(relativeAngle) > fov / 2) return;

        double spritePerpDist = distance * Math.cos(relativeAngle);
        if (castRayDistance(game.playerAngle + relativeAngle) + 1.0 < spritePerpDist) return;

        int screenX    = (int) (((relativeAngle / (fov / 2)) * 0.5 + 0.5) * width);
        int spriteSize = Math.max(12, Math.min((int) ((TILE_SIZE * height) / distance * 0.4), 80));
        int spriteY    = height / 2 - spriteSize / 2;

        BufferedImage sprite = m.getSprite();
        if (sprite != null) {
            g.drawImage(sprite, screenX - spriteSize / 2, spriteY, spriteSize, spriteSize, null);
        } else {
            g.setColor(new Color(230, 40, 40, 220));
            g.fillOval(screenX - spriteSize / 2, spriteY, spriteSize, spriteSize);
            g.setColor(Color.BLACK);
            g.drawOval(screenX - spriteSize / 2, spriteY, spriteSize, spriteSize);
        }
    }

    // -----------------------------------------------------------------------
    // Ball sprite rendering
    // -----------------------------------------------------------------------

    private void drawBallSprites(int[] pixels, int width, int height, int horizon,
                                 double startAngle, double rayStep,
                                 int rayCount, double[] rayDistances) {
        double fov             = Math.toRadians(60);
        double projectionPlane = (width / 2.0) / Math.tan(fov / 2.0);

        for (Ball ball : game.balls) {
            double dx = ball.x - game.playerX;
            double dy = ball.y - game.playerY;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < 0.1) continue;

            double angleToBall = Math.atan2(dy, dx);
            double angleDiff   = normalizeAngle(angleToBall - game.playerAngle);
            if (Math.abs(angleDiff) > fov / 2.0) continue;

            int spriteScreenX = (int) ((width / 2.0) + projectionPlane * Math.tan(angleDiff));
            int spriteSize    = Math.max(18, Math.min((int) ((TILE_SIZE * height) / distance * 0.20), 90));
            int spriteHalf    = spriteSize / 2;

            int floatOffset   = (int) (Math.sin(game.floatPhase + distance * 0.05) * 8);
            int spriteScreenY = horizon - spriteHalf - 12 + floatOffset;
            int spriteTop     = spriteScreenY - spriteHalf;
            int spriteBottom  = spriteScreenY + spriteHalf;
            int spriteLeft    = spriteScreenX - spriteHalf;
            int spriteRight   = spriteScreenX + spriteHalf;

            if (castRayDistance(game.playerAngle + angleDiff) < distance - 1) continue;

            int baseColor = 0x7ED6FF;
            int glow      = 0xD8F2FF;

            for (int sx = spriteLeft; sx < spriteRight; sx++) {
                if (sx < 0 || sx >= width) continue;
                int rayIndex = sx / 2;
                if (rayIndex < 0 || rayIndex >= rayCount) continue;
                if (distance * Math.cos(angleDiff) >= rayDistances[rayIndex] - 1) continue;
                for (int sy = spriteTop; sy < spriteBottom; sy++) {
                    if (sy < 0 || sy >= height) continue;
                    int absX = Math.abs(sx - spriteScreenX);
                    int absY = Math.abs(sy - spriteScreenY);
                    if (absX + absY > spriteHalf) continue;
                    double facetShade = (sy < spriteScreenY) ? 1.15 : (sx < spriteScreenX) ? 0.95 : 0.80;
                    int color = shadeColor(baseColor, Math.max(0.6, facetShade));
                    if (absX + absY < spriteHalf / 4) {
                        double edge = 1.0 - (double) (absX + absY) / (spriteHalf * 2.0);
                        color = shadeColor(glow, 0.5 + edge * 0.5);
                    }
                    pixels[sy * width + sx] = shadeColor(color,
                            Math.max(0.35, 0.5 + (1.0 - distance / 800.0) * 0.5));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // HUD / UI overlays
    // -----------------------------------------------------------------------

    /**
     * Top-right: level number, countdown, sprint indicator.
     */
    private void drawHUD(Graphics g) {
        int    width       = game.getWidth();
        int    timeSeconds = (int) (game.remainingTimeMillis / 1000);
        String timeStr     = String.format("%02d:%02d", timeSeconds / 60, timeSeconds % 60);
        String levelStr    = "Level " + game.config.level;
        String sprintStr   = game.sprinting ? "  [SPRINT]" : "";

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(width - 290, 10, 280, 36);

        g.setFont(g.getFont().deriveFont(16f));
        g.setColor(Color.WHITE);
        g.drawString(levelStr + "   " + timeStr, width - 278, 32);

        if (game.sprinting) {
            g.setColor(new Color(255, 220, 50));   // yellow
            int tw = g.getFontMetrics().stringWidth(levelStr + "   " + timeStr);
            g.drawString(sprintStr, width - 278 + tw, 32);
        }
    }

    /**
     * Top-left: diamond progress, hint, and stamina bar.
     */
    private void drawStatus(Graphics g) {
        int collected = game.config.objectiveCount - game.balls.size();
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(10, 10, 250, 90);
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(16f));
        g.drawString("Diamonds: " + collected + " / " + game.config.objectiveCount, 18, 32);
        g.drawString(game.balls.isEmpty() ? "Now go to the exit." : "Collect all diamonds.", 18, 52);
        drawStaminaBar(g, 18, 62, 230, 14);
    }

    /**
     * Draws a horizontal stamina bar at (x, y) with given width/height.
     * Color: green >50 %, yellow 25–50 %, red <25 %, gray when exhausted.
     * Shows "RECOVERING" label in red when sprint-locked.
     */
    private void drawStaminaBar(Graphics g, int x, int y, int w, int h) {
        // Background track
        g.setColor(new Color(50, 50, 50, 220));
        g.fillRoundRect(x, y, w, h, 6, 6);

        // Filled portion
        double pct   = game.stamina / GamePanel.MAX_STAMINA;
        int    fillW = (int) (w * pct);
        Color  fill;
        if (game.exhausted) {
            fill = new Color(100, 100, 100);
        } else if (pct > 0.50) {
            fill = new Color(50, 200, 80);
        } else if (pct > 0.25) {
            fill = new Color(230, 190, 30);
        } else {
            fill = new Color(210, 50, 50);
        }
        if (fillW > 0) {
            g.setColor(fill);
            g.fillRoundRect(x, y, fillW, h, 6, 6);
        }

        // Border
        g.setColor(new Color(180, 180, 180, 180));
        g.drawRoundRect(x, y, w, h, 6, 6);

        // Label
        g.setFont(g.getFont().deriveFont(10f));
        String label = game.exhausted ? "RECOVERING" : "STAMINA";
        g.setColor(game.exhausted ? new Color(220, 80, 80) : Color.WHITE);
        g.drawString(label, x + 3, y + h - 2);
    }

    /**
     * Left column: adaptive minimap showing tiles, player, monsters, diamonds.
     */
    private void drawMinimap(Graphics g) {
        int mapSize = game.config.mapSize;
        int tilePx  = MINIMAP_PX / mapSize;
        int mapPx   = mapSize * tilePx;
        int offsetX = 10;
        int offsetY = 110;

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(offsetX - 4, offsetY - 4, mapPx + 8, mapPx + 8);

        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                if (x == game.exitTileX && y == game.exitTileY) {
                    drawMinimapDoor(g, offsetX, offsetY, x, y, tilePx);
                    continue;
                }
                g.setColor(game.map[y][x] > 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.fillRect(offsetX + x * tilePx, offsetY + y * tilePx, tilePx, tilePx);
            }
        }

        // Player dot + direction
        int px = (int) (game.playerX / TILE_SIZE * tilePx);
        int py = (int) (game.playerY / TILE_SIZE * tilePx);
        g.setColor(Color.RED);
        g.fillOval(offsetX + px - 4, offsetY + py - 4, 8, 8);
        g.drawLine(offsetX + px, offsetY + py,
                   offsetX + px + (int) (Math.cos(game.playerAngle) * 10),
                   offsetY + py + (int) (Math.sin(game.playerAngle) * 10));

        // Start tile
        g.setColor(Color.BLUE);
        g.fillOval(offsetX + game.startTileX * tilePx + 2, offsetY + game.startTileY * tilePx + 2, 6, 6);

        // Diamonds
        for (Ball ball : game.balls) {
            int bx = offsetX + (int) (ball.x / TILE_SIZE * tilePx);
            int by = offsetY + (int) (ball.y / TILE_SIZE * tilePx);
            g.setColor(Color.MAGENTA);
            g.fillOval(bx - 4, by - 4, 8, 8);
            g.setColor(Color.YELLOW);
            g.fillOval(bx - 3, by - 3, 6, 6);
        }

        // All monsters
        for (Monster m : game.monsters) {
            m.drawOnMinimap(g, offsetX, offsetY, tilePx);
        }
    }

    private void drawMinimapDoor(Graphics g, int offsetX, int offsetY, int x, int y, int tilePx) {
        if (game.isExitOpen()) {
            g.setColor(Color.WHITE);
            g.fillRect(offsetX + x * tilePx, offsetY + y * tilePx, tilePx, tilePx);
        } else {
            g.setColor(new Color(34, 139, 34));
            g.fillRect(offsetX + x * tilePx, offsetY + y * tilePx, tilePx, tilePx);
            int dw = Math.max(4, tilePx / 2);
            int dh = Math.max(6, (int) (tilePx * 0.75));
            g.setColor(new Color(120, 60, 20));
            g.fillRect(offsetX + x * tilePx + tilePx / 4, offsetY + y * tilePx + 2, dw, dh);
            g.setColor(Color.BLACK);
            g.drawRect(offsetX + x * tilePx + tilePx / 4, offsetY + y * tilePx + 2, dw, dh);
        }
    }

    /**
     * Full-screen overlay: level complete / final victory / game over.
     */
    private void drawGameOverMenu(Graphics g) {
        int width  = game.getWidth();
        int height = game.getHeight();

        boolean finalVictory = game.victory && game.config.isLast();
        boolean levelWon     = game.victory && !game.config.isLast();

        String title, subtitle;
        if (finalVictory) {
            title    = "You Win!";
            subtitle = "All 3 levels cleared!";
        } else if (levelWon) {
            title    = "Level " + game.config.level + " Complete!";
            subtitle = "Get ready for Level " + (game.config.level + 1) + "...";
        } else {
            title    = "Game Over";
            subtitle = "You were defeated.";
        }

        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(0, 0, width, height);

        int boxWidth  = 440;
        int boxHeight = 60 + game.menuOptions.length * 34 + 80;
        int boxX = width  / 2 - boxWidth  / 2;
        int boxY = height / 2 - boxHeight / 2;

        g.setColor(Color.WHITE);
        g.fillRect(boxX, boxY, boxWidth, boxHeight);
        g.setColor(Color.BLACK);
        g.drawRect(boxX, boxY, boxWidth, boxHeight);

        g.setFont(g.getFont().deriveFont(36f));
        int titleW = g.getFontMetrics().stringWidth(title);
        g.drawString(title, width / 2 - titleW / 2, boxY + 55);

        g.setFont(g.getFont().deriveFont(18f));
        int subW = g.getFontMetrics().stringWidth(subtitle);
        g.setColor(new Color(60, 60, 60));
        g.drawString(subtitle, width / 2 - subW / 2, boxY + 85);

        g.setFont(g.getFont().deriveFont(22f));
        for (int i = 0; i < game.menuOptions.length; i++) {
            String option = game.menuOptions[i];
            int optionY   = boxY + 115 + i * 34;
            if (i == game.selectedMenuOption) {
                g.setColor(new Color(50, 100, 220));
                g.fillRoundRect(width / 2 - 110, optionY - 24, 220, 30, 12, 12);
                g.setColor(Color.WHITE);
            } else {
                g.setColor(Color.BLACK);
            }
            int optW = g.getFontMetrics().stringWidth(option);
            g.drawString(option, width / 2 - optW / 2, optionY);
        }

        g.setFont(g.getFont().deriveFont(14f));
        g.setColor(Color.DARK_GRAY);
        String hint = "UP / DOWN + ENTER to choose";
        g.drawString(hint, width / 2 - g.getFontMetrics().stringWidth(hint) / 2,
                boxY + boxHeight - 14);
    }

    // -----------------------------------------------------------------------
    // Raycasting
    // -----------------------------------------------------------------------

    private RayHit castRay(double rayAngle) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);
        int    mapSize = game.config.mapSize;

        int mapX = (int) (game.playerX / TILE_SIZE);
        int mapY = (int) (game.playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);
        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (game.playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE; }
        else             { stepX =  1; sideDistX = ((mapX + 1) * TILE_SIZE - game.playerX) * deltaDistX / TILE_SIZE; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (game.playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE; }
        else             { stepY =  1; sideDistY = ((mapY + 1) * TILE_SIZE - game.playerY) * deltaDistY / TILE_SIZE; }

        boolean hit = false;
        int side = 0, wallType = 1;
        while (!hit) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) break;
            int tile = game.getMapTile(mapX, mapY);
            if (tile > 0) { hit = true; wallType = tile; }
        }

        double dist = (side == 0)
                ? (mapX * TILE_SIZE - game.playerX + (1 - stepX) * TILE_SIZE / 2.0) / rayDirX
                : (mapY * TILE_SIZE - game.playerY + (1 - stepY) * TILE_SIZE / 2.0) / rayDirY;
        if (dist <= 0) dist = 1;

        double wallX = (side == 0) ? game.playerY + dist * rayDirY : game.playerX + dist * rayDirX;
        wallX %= TILE_SIZE;
        if (wallX < 0) wallX += TILE_SIZE;

        int textureX = (int) ((wallX / TILE_SIZE) * TEX_SIZE);
        textureX = Math.max(0, Math.min(TEX_SIZE - 1, textureX));
        if ((side == 0 && rayDirX > 0) || (side == 1 && rayDirY < 0)) textureX = TEX_SIZE - 1 - textureX;

        int[][] texture = (wallType == Door.DOOR_TILE)
                ? (game.isExitOpen() ? Textures.DOOR_OPEN : Textures.DOOR)
                : Textures.STONE;
        return new RayHit(dist, wallType, textureX, side, texture);
    }

    private double castRayDistance(double rayAngle) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);
        int    mapSize = game.config.mapSize;

        int mapX = (int) (game.playerX / TILE_SIZE);
        int mapY = (int) (game.playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);
        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (game.playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE; }
        else             { stepX =  1; sideDistX = ((mapX + 1) * TILE_SIZE - game.playerX) * deltaDistX / TILE_SIZE; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (game.playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE; }
        else             { stepY =  1; sideDistY = ((mapY + 1) * TILE_SIZE - game.playerY) * deltaDistY / TILE_SIZE; }

        boolean hit = false;
        int side = 0;
        while (!hit) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) break;
            if (game.getMapTile(mapX, mapY) > 0) hit = true;
        }

        double dist = (side == 0)
                ? (mapX * TILE_SIZE - game.playerX + (1 - stepX) * TILE_SIZE / 2.0) / (rayDirX == 0 ? 1e-6 : rayDirX)
                : (mapY * TILE_SIZE - game.playerY + (1 - stepY) * TILE_SIZE / 2.0) / (rayDirY == 0 ? 1e-6 : rayDirY);
        if (dist <= 0) dist = 1;
        return dist * Math.cos(rayAngle - game.playerAngle);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int shadeColor(int rgb, double factor) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 0xFF) * factor));
        int g = (int) Math.max(0, Math.min(255, ((rgb >>  8) & 0xFF) * factor));
        int b = (int) Math.max(0, Math.min(255, ( rgb        & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private double normalizeAngle(double angle) {
        while (angle >  Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}
