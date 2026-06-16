package rendering;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import UI.GamePanel;
import UI.GameState;
import domain.GameConstants;
import UI.PlayerController;
import domain.Ball;
import domain.Door;
import domain.Item;
import domain.RayHit;
import entity.MonsterEntity;
import network.RemotePlayer;

/**
 * Renderer handles all visual output: 3-D raycasted scene, sprite drawing,
 * minimap, HUD, status bar, and game-over / level-complete overlay.
 *
 * It holds a reference to {@link GamePanel} and reads state each frame.
 */
public class Renderer implements IRenderer {
    private final TextureRegistry textureRegistry;
    private static final int TILE_SIZE  = GameConstants.TILE_SIZE;
    private static final int TEX_SIZE   = GameConstants.TEX_SIZE;
    /** Minimap display size in pixels; tile pixel-size adapts per level. */
    private static final int MINIMAP_PX = 200;

    private final GamePanel game;
    private BufferedImage   screenBuffer;
    private int[]           screenPixels;
    private String          localIP = null;

    public Renderer(GamePanel game) {
        this.game = game;
        this.textureRegistry = new TextureRegistry();
    }

    // -----------------------------------------------------------------------
    // Public entry-point
    // -----------------------------------------------------------------------

    @Override
    public void render(Graphics g) {
        drawScene(g);
        drawMinimap(g);
        drawHUD(g);
        drawStatus(g);
        drawIPLabel(g);
        if (game.state.paused)       drawPauseMenu(g);
        else if (game.state.gameOverMenu) drawGameOverMenu(g);
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

        int horizon = height / 2;

        // ── Sky: flat dark-navy + hash-based stars that drift with playerAngle ──
        int skyColor   = (18 << 16) | (24 << 8) | 60;
        int starShift  = (int) (game.player.playerAngle * 300.0);
        for (int y = 0; y < horizon; y++) {
            int off = y * width;
            for (int x = 0; x < width; x++) {
                int color = skyColor;
                int hash = (x + starShift) * 374761393 + y * 668265263;
                hash = (hash ^ (hash >> 13)) * 1274126177;
                hash ^= (hash >>> 16);
                int twinkle = hash & 0x3FF;
                if (y < horizon - horizon / 8) {
                    if      (twinkle == 0) color = 0xFFFFFF;   // bright star
                    else if (twinkle <  4) color = 0x9AA0C8;   // dim star
                }
                screenPixels[off + x] = color;
            }
        }

        // Crescent moon that tracks playerAngle across the sky
        drawMoon(screenPixels, width, height, horizon, skyColor);

        // ── Floor: grassy green + depth shading + speckled grass blades ──
        int floorColor = (46 << 16) | (94 << 8) | 42;
        for (int y = horizon; y < height; y++) {
            double t = (height == horizon) ? 1.0 : (y - horizon) / (double) (height - horizon);
            int baseColor = shadeColor(floorColor, 0.45 + 0.55 * t);
            int off = y * width;
            for (int x = 0; x < width; x++) {
                int hash = x * 374761393 + y * 668265263;
                hash = (hash ^ (hash >> 13)) * 1274126177;
                hash ^= (hash >>> 16);
                int speck = hash & 0xFF;
                int color = baseColor;
                if      (speck <  10)  color = shadeColor(baseColor, 1.30); // light blade
                else if (speck > 246)  color = shadeColor(baseColor, 0.65); // dark soil
                screenPixels[off + x] = color;
            }
        }

        double fov        = Math.toRadians(60);
        int    rayCount   = width / 2;
        double rayStep    = fov / rayCount;
        double startAngle = game.player.playerAngle - fov / 2;
        double[] rayDistances = new double[rayCount];

        for (int ray = 0; ray < rayCount; ray++) {
            double rayAngle      = startAngle + ray * rayStep;
            RayHit hit           = castRay(rayAngle);
            double correctedDist = hit.distance * Math.cos(rayAngle - game.player.playerAngle);
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
                    if (!(hit.wallType == Door.DOOR_TILE && game.state.isExitOpen())) {
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
     * Crescent moon at a fixed world direction; slides across the sky as the
     * player turns so it feels like a real distant object.
     */
    private void drawMoon(int[] pixels, int width, int height, int horizon, int skyColor) {
        double moonWorldAngle = Math.toRadians(120);
        double skySpan        = Math.toRadians(180);
        double relAngle       = normalizeAngle(moonWorldAngle - game.player.playerAngle);
        if (Math.abs(relAngle) > skySpan / 2) return;

        int moonRadius = Math.max(14, height / 16);
        int glowRadius = (int) (moonRadius * 1.8);
        int moonX = (int) (width / 2.0 + (relAngle / (skySpan / 2.0)) * (width / 2.0 + glowRadius));
        int moonY = horizon / 3;

        int moonColor = 0xF4F1D8;
        int glowColor = 0x9FA6C0;

        double crescentX = moonX + moonRadius * 0.45;
        double crescentY = moonY - moonRadius * 0.25;
        double crescentR = moonRadius * 0.85;

        int xMin = Math.max(0,         moonX - glowRadius);
        int xMax = Math.min(width - 1, moonX + glowRadius);
        int yMin = Math.max(0,          moonY - glowRadius);
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
    // Monster sprite rendering
    // -----------------------------------------------------------------------

    private void drawAllMonsterSprites(Graphics g, int width, int height) {
        game.state.monsters.stream()
            .sorted((a, b) -> {
                double da = Math.hypot(a.getX() - game.player.playerX, a.getY() - game.player.playerY);
                double db = Math.hypot(b.getX() - game.player.playerX, b.getY() - game.player.playerY);
                return Double.compare(db, da);
            })
            .forEach(m -> drawMonsterSprite(g, width, height, m));
    }

    private void drawMonsterSprite(Graphics g, int width, int height, MonsterEntity m) {
        double dx = m.getX() - game.player.playerX;
        double dy = m.getY() - game.player.playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= 0) return;

        double angleToMonster = Math.atan2(dy, dx);
        double relativeAngle  = normalizeAngle(angleToMonster - game.player.playerAngle);
        double fov = Math.toRadians(60);
        if (Math.abs(relativeAngle) > fov / 2) return;

        double spritePerpDist = distance * Math.cos(relativeAngle);
        if (castRayDistance(game.player.playerAngle + relativeAngle) + 1.0 < spritePerpDist) return;

        int screenX  = (int) (((relativeAngle / (fov / 2)) * 0.5 + 0.5) * width);
        int baseSize = Math.max(20, Math.min((int) ((TILE_SIZE * height) / distance * 0.7), 150));

        // Ground-align: feet stick to the projected floor line
        int horizonY   = height / 2;
        int floorLineH = Math.max(1, Math.min((int) ((TILE_SIZE * height) / Math.max(1.0, spritePerpDist)), height));
        int groundY    = horizonY + floorLineH / 2;

        // Squish/breathing animation
        double squishPhase = game.state.floatPhase * 1.5 + (m.getX() + m.getY()) * 0.01;
        double squish      = Math.sin(squishPhase) * 0.12;
        int spriteW = (int) Math.round(baseSize * (1.0 + squish));
        int spriteH = (int) Math.round(baseSize * (1.0 - squish));
        int spriteX = screenX - spriteW / 2;
        int spriteY = groundY  - spriteH;

        MonsterRenderer renderer = new MonsterRenderer(m);
        BufferedImage sprite = renderer.getSprite(game.player.playerX, game.player.playerY);
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

    private void drawRemotePlayerSprite(Graphics g, int width, int height) {
        RemotePlayer rp = game.remotePlayer;
        if (rp == null) return;

        double dx       = rp.x - game.player.playerX;
        double dy       = rp.y - game.player.playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 1.0) return;

        double angleTo  = Math.atan2(dy, dx);
        double relAngle = normalizeAngle(angleTo - game.player.playerAngle);
        double fov      = Math.toRadians(60);
        if (Math.abs(relAngle) > fov / 2) return;

        if (castRayDistance(game.player.playerAngle + relAngle) + 1.0 < distance) return;

        int screenX    = (int) (((relAngle / (fov / 2)) * 0.5 + 0.5) * width);
        int spriteH    = Math.max(16, Math.min((int) ((TILE_SIZE * height) / distance * 0.9), 140));
        int centerY    = height / 2;
        int alpha      = (int) Math.min(255, 200 * (1.0 - distance / 1200.0));
        if (alpha <= 10) return;

        int headR  = Math.max(4, spriteH / 6);
        int headCY = centerY - spriteH / 2 + headR;
        g.setColor(new Color(0, 210, 255, alpha));
        g.fillOval(screenX - headR, headCY - headR, headR * 2, headR * 2);

        int torsoW = Math.max(4, spriteH / 5);
        int torsoH = spriteH / 3;
        int torsoY = headCY + headR + 2;
        g.setColor(new Color(0, 160, 200, alpha));
        g.fillRect(screenX - torsoW / 2, torsoY, torsoW, torsoH);

        int legW = Math.max(2, torsoW / 3);
        int legH = spriteH / 4;
        int legY = torsoY + torsoH + 1;
        g.setColor(new Color(0, 120, 160, alpha));
        g.fillRect(screenX - torsoW / 2,         legY, legW, legH); 
        g.fillRect(screenX + torsoW / 2 - legW,  legY, legW, legH); 

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

        for (Item item : game.state.items) {
            if (!(item instanceof Ball)) continue;
            Ball ball = (Ball) item;
            double dx = ball.getX() - game.player.playerX;
            double dy = ball.getY() - game.player.playerY;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < 0.1) continue;

            double angleToBall = Math.atan2(dy, dx);
            double angleDiff   = normalizeAngle(angleToBall - game.player.playerAngle);
            if (Math.abs(angleDiff) > fov / 2.0) continue;

            int spriteScreenX = (int) ((width / 2.0) + projectionPlane * Math.tan(angleDiff));
            int spriteSize    = Math.max(18, Math.min((int) ((TILE_SIZE * height) / distance * 0.20), 90));
            int spriteHalf    = spriteSize / 2;

            int floatOffset   = (int) (Math.sin(game.state.floatPhase + distance * 0.05) * 8);
            int spriteScreenY = horizon - spriteHalf - 12 + floatOffset;
            int spriteTop     = spriteScreenY - spriteHalf;
            int spriteBottom  = spriteScreenY + spriteHalf;
            int spriteLeft    = spriteScreenX - spriteHalf;
            int spriteRight   = spriteScreenX + spriteHalf;

            if (castRayDistance(game.player.playerAngle + angleDiff) < distance - 1) continue;

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

    private void drawHUD(Graphics g) {
        int    width       = game.getWidth();
        int    timeSeconds = (int) (game.state.remainingTimeMillis / 1000);
        String timeStr     = String.format("%02d:%02d", timeSeconds / 60, timeSeconds % 60);
        String levelStr    = "Level " + game.state.config.level;
        String sprintStr   = game.player.sprinting ? "  [SPRINT]" : "";

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(width - 290, 10, 280, 36);

        g.setFont(g.getFont().deriveFont(16f));
        g.setColor(Color.WHITE);
        g.drawString(levelStr + "   " + timeStr, width - 278, 32);

        if (game.player.sprinting) {
            g.setColor(new Color(255, 220, 50)); 
            int tw = g.getFontMetrics().stringWidth(levelStr + "   " + timeStr);
            g.drawString(sprintStr, width - 278 + tw, 32);
        }
    }

    private void drawStatus(Graphics g) {
        // FIXED: Count only objective items (Diamonds/Balls) to prevent HUD bugs
        int remainingDiamonds = 0;
        for (Item item : game.state.items) {
            if (item instanceof Ball) {
                remainingDiamonds++;
            }
        }

        int collected = game.state.config.objectiveCount - remainingDiamonds;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(10, 10, 250, 90);
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(16f));
        g.drawString("Diamonds: " + collected + " / " + game.state.config.objectiveCount, 18, 32);
        
        // FIXED: Display exit message only when Diamonds reach 0
        g.drawString(remainingDiamonds == 0 ? "Now go to the exit." : "Collect all diamonds.", 18, 52);
        drawStaminaBar(g, 18, 62, 230, 14);
    }

    private void drawStaminaBar(Graphics g, int x, int y, int w, int h) {
        g.setColor(new Color(50, 50, 50, 220));
        g.fillRoundRect(x, y, w, h, 6, 6);

        double pct   = game.player.stamina / PlayerController.MAX_STAMINA;
        int    fillW = (int) (w * pct);
        Color  fill;
        if (game.player.exhausted) {
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

        g.setColor(new Color(180, 180, 180, 180));
        g.drawRoundRect(x, y, w, h, 6, 6);

        g.setFont(g.getFont().deriveFont(10f));
        String label = game.player.exhausted ? "RECOVERING" : "STAMINA";
        g.setColor(game.player.exhausted ? new Color(220, 80, 80) : Color.WHITE);
        g.drawString(label, x + 3, y + h - 2);
    }

    private void drawMinimap(Graphics g) {
        int mapSize = game.state.config.mapSize;
        int tilePx  = MINIMAP_PX / mapSize;
        int mapPx   = mapSize * tilePx;
        int offsetX = 10;
        int offsetY = 110;

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(offsetX - 4, offsetY - 4, mapPx + 8, mapPx + 8);

        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                if (x == game.state.exitTileX && y == game.state.exitTileY) {
                    drawMinimapDoor(g, offsetX, offsetY, x, y, tilePx);
                    continue;
                }
                g.setColor(game.state.map[y][x] > 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.fillRect(offsetX + x * tilePx, offsetY + y * tilePx, tilePx, tilePx);
            }
        }

        int px = (int) (game.player.playerX / TILE_SIZE * tilePx);
        int py = (int) (game.player.playerY / TILE_SIZE * tilePx);
        g.setColor(Color.RED);
        g.fillOval(offsetX + px - 4, offsetY + py - 4, 8, 8);
        g.drawLine(offsetX + px, offsetY + py,
                   offsetX + px + (int) (Math.cos(game.player.playerAngle) * 10),
                   offsetY + py + (int) (Math.sin(game.player.playerAngle) * 10));

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

        g.setColor(Color.BLUE);
        g.fillOval(offsetX + GameState.START_TILE_X * tilePx + 2, offsetY + GameState.START_TILE_Y * tilePx + 2, 6, 6);

        // FIXED: Implemented polymorphism! No more instanceof checks for minimap item rendering
        for (Item item : game.state.items) {
            int itemPx = offsetX + (int) (item.getX() / TILE_SIZE * tilePx);
            int itemPy = offsetY + (int) (item.getY() / TILE_SIZE * tilePx);
            
            item.drawOnMinimap(g, itemPx, itemPy);
        }

        for (MonsterEntity m : game.state.monsters) {
            MonsterRenderer renderer = new MonsterRenderer(m);
            renderer.drawOnMinimap(g, offsetX, offsetY, tilePx);
        }
    }

    private void drawMinimapDoor(Graphics g, int offsetX, int offsetY, int x, int y, int tilePx) {
        if (game.state.isExitOpen()) {
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

    private void drawPauseMenu(Graphics g) {
        int width  = game.getWidth();
        int height = game.getHeight();

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, width, height);

        int numOpts       = game.state.pauseMenuOptions.length;
        int boxWidth      = 460;
        int titleAreaH    = 130;
        int optionSpacing = 58;
        int bottomPadding = 50;
        int boxHeight = titleAreaH + numOpts * optionSpacing + bottomPadding;
        int boxX = width  / 2 - boxWidth  / 2;
        int boxY = height / 2 - boxHeight / 2;

        g.setColor(new Color(18, 20, 45, 245));
        g.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 18, 18);
        g.setColor(new Color(70, 110, 210));
        g.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 18, 18);

        String title = "PAUSED";
        g.setFont(g.getFont().deriveFont(Font.BOLD, 40f));
        g.setColor(Color.WHITE);
        int tw = g.getFontMetrics().stringWidth(title);
        g.drawString(title, width / 2 - tw / 2, boxY + 60);

        g.setFont(g.getFont().deriveFont(26f));
        for (int i = 0; i < numOpts; i++) {
            String opt = game.state.pauseMenuOptions[i];
            int oy = boxY + titleAreaH + i * optionSpacing;
            if (i == game.state.pauseMenuSelected) {
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

    private void drawGameOverMenu(Graphics g) {
        int width  = game.getWidth();
        int height = game.getHeight();

        boolean finalVictory = game.state.victory && game.state.config.isLast();
        boolean levelWon     = game.state.victory && !game.state.config.isLast();

        String title, subtitle;
        if (finalVictory) {
            title    = "You Win!";
            subtitle = "All 3 levels cleared!";
        } else if (levelWon) {
            title    = "Level " + game.state.config.level + " Complete!";
            subtitle = "Get ready for Level " + (game.state.config.level + 1) + "...";
        } else {
            title    = "Game Over";
            subtitle = game.state.remotePlayerLeft ? "Other player disconnected." : "You were defeated.";
        }

        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(0, 0, width, height);

        int boxWidth  = 440;
        int boxHeight = 60 + game.state.menuOptions.length * 34 + 80;
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
        for (int i = 0; i < game.state.menuOptions.length; i++) {
            String option = game.state.menuOptions[i];
            int optionY   = boxY + 115 + i * 34;
            if (i == game.state.selectedMenuOption) {
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
        int    mapSize = game.state.config.mapSize;

        int mapX = (int) (game.player.playerX / TILE_SIZE);
        int mapY = (int) (game.player.playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);
        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (game.player.playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE; }
        else             { stepX =  1; sideDistX = ((mapX + 1) * TILE_SIZE - game.player.playerX) * deltaDistX / TILE_SIZE; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (game.player.playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE; }
        else             { stepY =  1; sideDistY = ((mapY + 1) * TILE_SIZE - game.player.playerY) * deltaDistY / TILE_SIZE; }

        boolean hit = false;
        int side = 0, wallType = 1;
        while (!hit) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) break;
            int tile = game.state.getMapTile(mapX, mapY);
            if (tile > 0) { hit = true; wallType = tile; }
        }

        double dist = (side == 0)
                ? (mapX * TILE_SIZE - game.player.playerX + (1 - stepX) * TILE_SIZE / 2.0) / rayDirX
                : (mapY * TILE_SIZE - game.player.playerY + (1 - stepY) * TILE_SIZE / 2.0) / rayDirY;
        if (dist <= 0) dist = 1;

        double wallX = (side == 0) ? game.player.playerY + dist * rayDirY : game.player.playerX + dist * rayDirX;
        wallX %= TILE_SIZE;
        if (wallX < 0) wallX += TILE_SIZE;

        int textureX = (int) ((wallX / TILE_SIZE) * TEX_SIZE);
        textureX = Math.max(0, Math.min(TEX_SIZE - 1, textureX));
        if ((side == 0 && rayDirX > 0) || (side == 1 && rayDirY < 0)) textureX = TEX_SIZE - 1 - textureX;

        int[][] texture;
        if (wallType == Door.DOOR_TILE && game.state.isExitOpen()) {
             texture = TextureFactory.createOpenDoor(); // Handle specific state override
        } else {
             texture = textureRegistry.get(wallType); // Cleanly fetch by ID!
        }
        return new RayHit(dist, wallType, textureX, side, texture);
    }

    private double castRayDistance(double rayAngle) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);
        int    mapSize = game.state.config.mapSize;

        int mapX = (int) (game.player.playerX / TILE_SIZE);
        int mapY = (int) (game.player.playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);
        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (game.player.playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE; }
        else             { stepX =  1; sideDistX = ((mapX + 1) * TILE_SIZE - game.player.playerX) * deltaDistX / TILE_SIZE; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (game.player.playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE; }
        else             { stepY =  1; sideDistY = ((mapY + 1) * TILE_SIZE - game.player.playerY) * deltaDistY / TILE_SIZE; }

        boolean hit = false;
        int side = 0;
        while (!hit) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) break;
            if (game.state.getMapTile(mapX, mapY) > 0) hit = true;
        }

        double dist = (side == 0)
                ? (mapX * TILE_SIZE - game.player.playerX + (1 - stepX) * TILE_SIZE / 2.0) / (rayDirX == 0 ? 1e-6 : rayDirX)
                : (mapY * TILE_SIZE - game.player.playerY + (1 - stepY) * TILE_SIZE / 2.0) / (rayDirY == 0 ? 1e-6 : rayDirY);
        if (dist <= 0) dist = 1;
        return dist * Math.cos(rayAngle - game.player.playerAngle);
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

    private int lerpColor(int c0, int c1, double t) {
        int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >>  8) & 0xFF, b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >>  8) & 0xFF, b1 = c1 & 0xFF;
        return ((int)(r0 + (r1 - r0) * t) << 16)
             | ((int)(g0 + (g1 - g0) * t) <<  8)
             |  (int)(b0 + (b1 - b0) * t);
    }

    private double normalizeAngle(double angle) {
        while (angle >  Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}