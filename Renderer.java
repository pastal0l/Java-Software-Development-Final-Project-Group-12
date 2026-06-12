import java.awt.Color;
import java.awt.Font;
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
    private String          localIP = null;

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
        drawIPLabel(g);
        if (game.paused)       drawPauseMenu(g);
        else if (game.gameOverMenu) drawGameOverMenu(g);
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
        int floorColor = (46 << 16) | (94 << 8) | 42; // grassy green

        // Stars drift sideways as the player turns, so the sky feels like a
        // distant dome rather than a flat backdrop painted on the screen.
        int starShift = (int) (game.playerAngle * 300.0);

        int row = 0;
        for (int y = 0; y < horizon; y++) {
            int off = row++ * width;
            for (int x = 0; x < width; x++) {
                int color = skyColor;
                // Cheap deterministic hash -> sparse twinkling stars, kept away
                // from the horizon so they don't clutter the skyline.
                int hash = (x + starShift) * 374761393 + y * 668265263;
                hash = (hash ^ (hash >> 13)) * 1274126177;
                hash ^= (hash >>> 16);
                int twinkle = hash & 0x3FF; // 0..1023
                if (y < horizon - horizon / 8) {
                    if (twinkle == 0)      color = 0xFFFFFF; // bright star
                    else if (twinkle < 4)  color = 0x9AA0C8; // dim star
                }
                screenPixels[off + x] = color;
            }
        }

        drawMoon(screenPixels, width, height, horizon, skyColor);

        for (int y = horizon; y < height; y++) {
            // Darker/hazier near the horizon, full brightness close to the player.
            double t = (height == horizon) ? 1.0 : (y - horizon) / (double) (height - horizon);
            int baseColor = shadeColor(floorColor, 0.45 + 0.55 * t);
            int off = row++ * width;
            for (int x = 0; x < width; x++) {
                // Speckled grass-blade texture via a cheap per-pixel hash.
                int hash = x * 374761393 + y * 668265263;
                hash = (hash ^ (hash >> 13)) * 1274126177;
                hash ^= (hash >>> 16);
                int speck = hash & 0xFF;
                int color = baseColor;
                if (speck < 10)       color = shadeColor(baseColor, 1.30); // light blade highlight
                else if (speck > 246) color = shadeColor(baseColor, 0.65); // dark soil speck
                screenPixels[off + x] = color;
            }
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
                    // Sample the texture column at a fractional row and blend
                    // between the two nearest texels (bilinear filtering).
                    // Without this, a wall that fills most of the screen stretches
                    // each texel row into a large flat band, producing harsh
                    // horizontal stripes ("stretched"/blocky) at close range.
                    double texYf = ((y - lineOffset) * TEX_SIZE) / (double) lineHeight;
                    texYf = Math.max(0, Math.min(TEX_SIZE - 1, texYf));
                    int texY0 = (int) texYf;
                    int texY1 = Math.min(TEX_SIZE - 1, texY0 + 1);
                    double frac = texYf - texY0;
                    int colA = hit.texture[hit.textureX][texY0];
                    int colB = hit.texture[hit.textureX][texY1];
                    int color = lerpColor(colA, colB, frac);
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
        drawRemotePlayerSprite(g, width, height);
    }

    /**
     * Draws a crescent moon at a fixed point in the world's "sky dome".
     * As the player turns, the moon slides across the sky and off-screen
     * rather than staying glued to one spot on the display.
     */
    private void drawMoon(int[] pixels, int width, int height, int horizon, int skyColor) {
        double moonWorldAngle = Math.toRadians(120); // fixed direction in the world
        double skySpan        = Math.toRadians(180); // arc of sky the moon travels across
        double relAngle       = normalizeAngle(moonWorldAngle - game.playerAngle);
        if (Math.abs(relAngle) > skySpan / 2) return; // moon is behind the player

        int moonRadius  = Math.max(14, height / 16);
        int glowRadius  = (int) (moonRadius * 1.8);
        int moonX = (int) (width / 2.0
                + (relAngle / (skySpan / 2.0)) * (width / 2.0 + glowRadius));
        int moonY = horizon / 3;

        int moonColor = 0xF4F1D8;
        int glowColor = 0x9FA6C0;

        // Crescent cutout: an offset circle painted back over with sky color.
        double crescentX = moonX + moonRadius * 0.45;
        double crescentY = moonY - moonRadius * 0.25;
        double crescentR = moonRadius * 0.85;

        int xMin = Math.max(0, moonX - glowRadius);
        int xMax = Math.min(width - 1, moonX + glowRadius);
        int yMin = Math.max(0, moonY - glowRadius);
        int yMax = Math.min(horizon - 1, moonY + glowRadius);

        for (int y = yMin; y <= yMax; y++) {
            int rowOff = y * width;
            for (int x = xMin; x <= xMax; x++) {
                double dist = Math.hypot(x - moonX, y - moonY);
                if (dist <= moonRadius) {
                    double craterDist = Math.hypot(x - crescentX, y - crescentY);
                    pixels[rowOff + x] = (craterDist <= crescentR) ? skyColor : moonColor;
                } else if (dist <= glowRadius) {
                    double f = 1.0 - (dist - moonRadius) / (glowRadius - moonRadius);
                    pixels[rowOff + x] = lerpColor(skyColor, glowColor, f * 0.5);
                }
            }
        }
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

        int screenX = (int) (((relativeAngle / (fov / 2)) * 0.5 + 0.5) * width);

        // Bigger base size than before so the monster reads clearly up close.
        int baseSize = Math.max(20, Math.min((int) ((TILE_SIZE * height) / distance * 0.7), 150));

        // Align the monster's feet with the floor edge of the walls at this
        // distance (same projection walls use) so it doesn't float in midair.
        int horizon = height / 2;
        int floorLineHeight = Math.max(1, Math.min((int) ((TILE_SIZE * height) / Math.max(1.0, spritePerpDist)), height));
        int groundY = horizon + floorLineHeight / 2;

        // "Breathing"/squish animation: widen while shortening and vice versa,
        // phase-offset by position so multiple monsters don't pulse in sync.
        double squishPhase = game.floatPhase * 1.5 + (m.getX() + m.getY()) * 0.01;
        double squish      = Math.sin(squishPhase) * 0.12; // +/-12%
        int spriteW = (int) Math.round(baseSize * (1.0 + squish));
        int spriteH = (int) Math.round(baseSize * (1.0 - squish));

        int spriteX = screenX - spriteW / 2;
        int spriteY = groundY - spriteH;

        BufferedImage sprite = m.getSprite(game.playerX, game.playerY);
        if (sprite != null) {
            g.drawImage(sprite, spriteX, spriteY, spriteW, spriteH, null);
        } else {
            g.setColor(new Color(230, 40, 40, 220));
            g.fillOval(spriteX, spriteY, spriteW, spriteH);
            g.setColor(Color.BLACK);
            g.drawOval(spriteX, spriteY, spriteW, spriteH);
        }
    }

    // -----------------------------------------------------------------------
    // Remote co-op player sprite
    // -----------------------------------------------------------------------

    /**
     * Draws the remote player as a cyan humanoid silhouette in the 3-D view.
     * Uses the same perspective projection as the monster sprites but with a
     * distinct colour so the two players can tell each other apart.
     */
    private void drawRemotePlayerSprite(Graphics g, int width, int height) {
        RemotePlayer rp = game.remotePlayer;
        if (rp == null) return;

        double dx       = rp.x - game.playerX;
        double dy       = rp.y - game.playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 1.0) return;

        double angleTo  = Math.atan2(dy, dx);
        double relAngle = normalizeAngle(angleTo - game.playerAngle);
        double fov      = Math.toRadians(60);
        if (Math.abs(relAngle) > fov / 2) return;

        // Wall occlusion: don't draw through walls
        if (castRayDistance(game.playerAngle + relAngle) + 1.0 < distance) return;

        int screenX    = (int) (((relAngle / (fov / 2)) * 0.5 + 0.5) * width);
        int spriteH    = Math.max(16, Math.min((int) ((TILE_SIZE * height) / distance * 0.9), 140));
        int centerY    = height / 2;
        int alpha      = (int) Math.min(255, 200 * (1.0 - distance / 1200.0));
        if (alpha <= 10) return;

        // ── Head ──────────────────────────────────────────────────────────
        int headR  = Math.max(4, spriteH / 6);
        int headCY = centerY - spriteH / 2 + headR;
        g.setColor(new Color(0, 210, 255, alpha));
        g.fillOval(screenX - headR, headCY - headR, headR * 2, headR * 2);

        // ── Torso ──────────────────────────────────────────────────────────
        int torsoW = Math.max(4, spriteH / 5);
        int torsoH = spriteH / 3;
        int torsoY = headCY + headR + 2;
        g.setColor(new Color(0, 160, 200, alpha));
        g.fillRect(screenX - torsoW / 2, torsoY, torsoW, torsoH);

        // ── Legs ───────────────────────────────────────────────────────────
        int legW = Math.max(2, torsoW / 3);
        int legH = spriteH / 4;
        int legY = torsoY + torsoH + 1;
        g.setColor(new Color(0, 120, 160, alpha));
        g.fillRect(screenX - torsoW / 2,         legY, legW, legH);   // left
        g.fillRect(screenX + torsoW / 2 - legW,  legY, legW, legH);   // right

        // ── Name label ─────────────────────────────────────────────────────
        if (distance < 500 && spriteH > 30) {
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            g.setColor(new Color(200, 240, 255, alpha));
            int lw = g.getFontMetrics().stringWidth(rp.label);
            g.drawString(rp.label, screenX - lw / 2, headCY - headR - 3);
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

        // Remote player dot + direction (cyan)
        RemotePlayer rp = game.remotePlayer;
        if (rp != null) {
            int rpx = (int) (rp.x / TILE_SIZE * tilePx);
            int rpy = (int) (rp.y / TILE_SIZE * tilePx);
            g.setColor(new Color(0, 220, 255));
            g.fillOval(offsetX + rpx - 4, offsetY + rpy - 4, 8, 8);
            g.setColor(new Color(0, 180, 220));
            g.drawLine(offsetX + rpx, offsetY + rpy,
                       offsetX + rpx + (int) (Math.cos(rp.angle) * 10),
                       offsetY + rpy + (int) (Math.sin(rp.angle) * 10));
        }

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
     * Small IP-address label in the bottom-right corner.
     */
    private void drawIPLabel(Graphics g) {
        if (localIP == null) {
            try { localIP = java.net.InetAddress.getLocalHost().getHostAddress(); }
            catch (Exception e) { localIP = "?.?.?.?"; }
        }
        int width  = game.getWidth();
        int height = game.getHeight();
        String text = "IP: " + localIP;
        g.setFont(g.getFont().deriveFont(12f));
        int sw = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(width - sw - 20, height - 24, sw + 14, 18);
        g.setColor(new Color(170, 215, 255, 210));
        g.drawString(text, width - sw - 13, height - 10);
    }

    /**
     * Semi-transparent pause overlay (ESC).  In multiplayer the game keeps
     * running behind it; in single-player the timer is stopped.
     */
    private void drawPauseMenu(Graphics g) {
        int width  = game.getWidth();
        int height = game.getHeight();

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, width, height);

        int numOpts        = game.pauseMenuOptions.length;
        int boxWidth       = 460;
        int titleAreaH     = 130; // space reserved for the title + gap before the first option
        int optionSpacing  = 58;
        int bottomPadding  = 50;
        int boxHeight = titleAreaH + numOpts * optionSpacing + bottomPadding;
        int boxX = width  / 2 - boxWidth  / 2;
        int boxY = height / 2 - boxHeight / 2;

        g.setColor(new Color(18, 20, 45, 245));
        g.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 18, 18);
        g.setColor(new Color(70, 110, 210));
        g.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 18, 18);

        String title = game.networkClient != null ? "PAUSED" : "PAUSED";
        g.setFont(g.getFont().deriveFont(Font.BOLD, 40f));
        g.setColor(Color.WHITE);
        int tw = g.getFontMetrics().stringWidth(title);
        g.drawString(title, width / 2 - tw / 2, boxY + 60);

        g.setFont(g.getFont().deriveFont(26f));
        for (int i = 0; i < numOpts; i++) {
            String opt = game.pauseMenuOptions[i];
            int oy = boxY + titleAreaH + i * optionSpacing;
            if (i == game.pauseMenuSelected) {
                g.setColor(new Color(50, 100, 220));
                g.fillRoundRect(width / 2 - 160, oy - 32, 320, 44, 12, 12);
                g.setColor(Color.WHITE);
            } else {
                g.setColor(new Color(170, 200, 240));
            }
            int ow = g.getFontMetrics().stringWidth(opt);
            g.drawString(opt, width / 2 - ow / 2, oy);
        }

        if (game.networkClient != null) {
            g.setFont(g.getFont().deriveFont(11f));
            g.setColor(new Color(140, 140, 150));
            String note = "Game continues in the background (multiplayer)";
            int nw = g.getFontMetrics().stringWidth(note);
            g.drawString(note, width / 2 - nw / 2, boxY + boxHeight - 10);
        } else {
            g.setFont(g.getFont().deriveFont(12f));
            g.setColor(new Color(140, 140, 150));
            String hint = "UP / DOWN + ENTER to choose";
            int hw = g.getFontMetrics().stringWidth(hint);
            g.drawString(hint, width / 2 - hw / 2, boxY + boxHeight - 10);
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
            subtitle = game.remotePlayerLeft ? "Other player disconnected." : "You were defeated.";
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
                : Textures.WALL;
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

    /** Linearly blends two packed RGB colors; {@code t} in [0,1] (0 = c0, 1 = c1). */
    private int lerpColor(int c0, int c1, double t) {
        int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r = (int) (r0 + (r1 - r0) * t);
        int g = (int) (g0 + (g1 - g0) * t);
        int b = (int) (b0 + (b1 - b0) * t);
        return (r << 16) | (g << 8) | b;
    }

    private double normalizeAngle(double angle) {
        while (angle >  Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}
