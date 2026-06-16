package rendering;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import domain.GameConstants;
import domain.GameState;
import domain.Ball;
import domain.Door;
import domain.Item;
import domain.MonsterEntity;
import domain.RayHit;
import network.RemotePlayer;

/**
 * Renderer handles all visual output: 3-D raycasted scene, sprite drawing,
 * minimap, HUD, status bar, and game-over / level-complete overlay.
 *
 * It is completely decoupled from the UI layer and only requires raw primitives
 * and domain objects to be passed into the render() method.
 */
public class Renderer implements IRenderer {
    private final TextureRegistry textureRegistry;
    private static final int TILE_SIZE  = GameConstants.TILE_SIZE;
    private static final int TEX_SIZE   = GameConstants.TEX_SIZE;
    /** Minimap display size in pixels; tile pixel-size adapts per level. */
    private static final int MINIMAP_PX = 200;

    private BufferedImage   screenBuffer;
    private int[]           screenPixels;
    private String          localIP = null;

    public Renderer() {
        this.textureRegistry = new TextureRegistry();
    }

    // -----------------------------------------------------------------------
    // Public entry-point
    // -----------------------------------------------------------------------

    /**
     * Renders the game. Note that all required data is passed in as parameters.
     */
    public void render(Graphics g, int width, int height, GameState state, 
                       double playerX, double playerY, double playerAngle, 
                       boolean sprinting, double staminaPct, boolean exhausted, 
                       RemotePlayer remotePlayer, boolean isMultiplayer) {
        
        drawScene(g, width, height, state, playerX, playerY, playerAngle, remotePlayer);
        drawMinimap(g, state, playerX, playerY, playerAngle, remotePlayer);
        drawHUD(g, width, state, sprinting);
        drawStatus(g, state, staminaPct, exhausted);
        drawIPLabel(g, width, height);
        
        if (state.paused) {
            drawPauseMenu(g, width, height, state, isMultiplayer);
        } else if (state.gameOverMenu) {
            drawGameOverMenu(g, width, height, state);
        }
    }

    // -----------------------------------------------------------------------
    // 3-D scene
    // -----------------------------------------------------------------------

    private void drawScene(Graphics g, int width, int height, GameState state, 
                           double playerX, double playerY, double playerAngle, 
                           RemotePlayer remotePlayer) {
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
        int starShift  = (int) (playerAngle * 300.0);
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
        drawMoon(screenPixels, width, height, horizon, skyColor, playerAngle);

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
        int    rayCount   = width;
        double rayStep    = fov / rayCount;
        double startAngle = playerAngle - fov / 2;
        double[] rayDistances = new double[rayCount];

        for (int ray = 0; ray < rayCount; ray++) {
            double rayAngle      = startAngle + ray * rayStep;
            RayHit hit           = castRay(rayAngle, state, playerX, playerY);
            double correctedDist = hit.distance * Math.cos(rayAngle - playerAngle);
            rayDistances[ray]    = correctedDist;

            int lineHeight = Math.max(1, Math.min((int) ((TILE_SIZE * height) / correctedDist), height));
            int lineOffset = horizon - lineHeight / 2;
            int drawStart  = Math.max(0, lineOffset);
            int drawEnd    = Math.min(height - 1, lineOffset + lineHeight);
            int px = ray;
            if (px < 0 || px >= width) continue;

            for (int y = drawStart; y <= drawEnd; y++) {
                int texY  = Math.max(0, Math.min(TEX_SIZE - 1,
                        (int) (((y - lineOffset) * TEX_SIZE) / (double) lineHeight)));
                int color = hit.texture[hit.textureX][texY];
                if (!(hit.wallType == Door.DOOR_TILE && state.isExitOpen())) {
                    if (hit.side == 1) color = shadeColor(color, 0.70);
                    color = shadeColor(color,
                            Math.max(0.20, 1.0 / (1.0 + correctedDist * correctedDist * 0.00005)));
                }
                screenPixels[y * width + px] = color;
            }
        }

        drawBallSprites(screenPixels, width, height, horizon,
                startAngle, rayStep, rayCount, rayDistances, state, playerX, playerY, playerAngle);

        g.drawImage(screenBuffer, 0, 0, null);
        drawAllMonsterSprites(g, width, height, state, playerX, playerY, playerAngle);
        drawRemotePlayerSprite(g, width, height, remotePlayer, state, playerX, playerY, playerAngle);
    }

    private void drawMoon(int[] pixels, int width, int height, int horizon, int skyColor, double playerAngle) {
        double moonWorldAngle = Math.toRadians(120);
        double skySpan        = Math.toRadians(180);
        double relAngle       = normalizeAngle(moonWorldAngle - playerAngle);
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

    private void drawAllMonsterSprites(Graphics g, int width, int height, GameState state, 
                                       double playerX, double playerY, double playerAngle) {
        state.monsters.stream()
            .sorted((a, b) -> {
                double da = Math.hypot(a.getX() - playerX, a.getY() - playerY);
                double db = Math.hypot(b.getX() - playerX, b.getY() - playerY);
                return Double.compare(db, da);
            })
            .forEach(m -> drawMonsterSprite(g, width, height, m, state, playerX, playerY, playerAngle));
    }

    private void drawMonsterSprite(Graphics g, int width, int height, MonsterEntity m, GameState state, 
                                   double playerX, double playerY, double playerAngle) {
        double dx = m.getX() - playerX;
        double dy = m.getY() - playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance <= 0) return;

        double angleToMonster = Math.atan2(dy, dx);
        double relativeAngle  = normalizeAngle(angleToMonster - playerAngle);
        double fov = Math.toRadians(60);
        if (Math.abs(relativeAngle) > fov / 2) return;

        double spritePerpDist = distance * Math.cos(relativeAngle);
        if (castRayDistance(playerAngle + relativeAngle, state, playerX, playerY, playerAngle) + 1.0 < spritePerpDist) return;

        int screenX  = (int) (((relativeAngle / (fov / 2)) * 0.5 + 0.5) * width);
        int baseSize = Math.max(20, Math.min((int) ((TILE_SIZE * height) / distance * 0.7), 150));

        // Ground-align: feet stick to the projected floor line
        int horizonY   = height / 2;
        int floorLineH = Math.max(1, Math.min((int) ((TILE_SIZE * height) / Math.max(1.0, spritePerpDist)), height));
        int groundY    = horizonY + floorLineH / 2;

        // Squish/breathing animation
        double squishPhase = state.floatPhase * 1.5 + (m.getX() + m.getY()) * 0.01;
        double squish      = Math.sin(squishPhase) * 0.12;
        int spriteW = (int) Math.round(baseSize * (1.0 + squish));
        int spriteH = (int) Math.round(baseSize * (1.0 - squish));
        int spriteX = screenX - spriteW / 2;
        int spriteY = groundY  - spriteH;

        MonsterRenderer renderer = new MonsterRenderer(m);
        BufferedImage sprite = renderer.getSprite(playerX, playerY);
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

    private void drawRemotePlayerSprite(Graphics g, int width, int height, RemotePlayer rp, GameState state,
                                        double playerX, double playerY, double playerAngle) {
        if (rp == null) return;

        double dx       = rp.x - playerX;
        double dy       = rp.y - playerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 1.0) return;

        double angleTo  = Math.atan2(dy, dx);
        double relAngle = normalizeAngle(angleTo - playerAngle);
        double fov      = Math.toRadians(60);
        if (Math.abs(relAngle) > fov / 2) return;

        if (castRayDistance(playerAngle + relAngle, state, playerX, playerY, playerAngle) + 1.0 < distance) return;

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
                                 int rayCount, double[] rayDistances, GameState state, 
                                 double playerX, double playerY, double playerAngle) {
        double fov             = Math.toRadians(60);
        double projectionPlane = (width / 2.0) / Math.tan(fov / 2.0);

        for (Item item : state.items) {
            if (!(item instanceof Ball)) continue;
            Ball ball = (Ball) item;
            double dx = ball.getX() - playerX;
            double dy = ball.getY() - playerY;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < 0.1) continue;

            double angleToBall = Math.atan2(dy, dx);
            double angleDiff   = normalizeAngle(angleToBall - playerAngle);
            if (Math.abs(angleDiff) > fov / 2.0) continue;

            int spriteScreenX = (int) ((width / 2.0) + projectionPlane * Math.tan(angleDiff));
            int spriteSize    = Math.max(18, Math.min((int) ((TILE_SIZE * height) / distance * 0.20), 90));
            int spriteHalf    = spriteSize / 2;

            int floatOffset   = (int) (Math.sin(state.floatPhase + distance * 0.05) * 8);
            int spriteScreenY = horizon - spriteHalf - 12 + floatOffset;
            int spriteTop     = spriteScreenY - spriteHalf;
            int spriteBottom  = spriteScreenY + spriteHalf;
            int spriteLeft    = spriteScreenX - spriteHalf;
            int spriteRight   = spriteScreenX + spriteHalf;

            if (castRayDistance(playerAngle + angleDiff, state, playerX, playerY, playerAngle) < distance - 1) continue;

            int baseColor = 0x7ED6FF;
            int glow      = 0xD8F2FF;

            for (int sx = spriteLeft; sx < spriteRight; sx++) {
                if (sx < 0 || sx >= width) continue;
                int rayIndex = sx;
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

    private void drawHUD(Graphics g, int width, GameState state, boolean sprinting) {
        int    timeSeconds = (int) (state.remainingTimeMillis / 1000);
        String timeStr     = String.format("%02d:%02d", timeSeconds / 60, timeSeconds % 60);
        String levelStr    = "Level " + state.config.level;
        String sprintStr   = sprinting ? "  [SPRINT]" : "";

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(width - 290, 10, 280, 36);

        g.setFont(g.getFont().deriveFont(16f));
        g.setColor(Color.WHITE);
        g.drawString(levelStr + "   " + timeStr, width - 278, 32);

        if (sprinting) {
            g.setColor(new Color(255, 220, 50)); 
            int tw = g.getFontMetrics().stringWidth(levelStr + "   " + timeStr);
            g.drawString(sprintStr, width - 278 + tw, 32);
        }
    }

    private void drawStatus(Graphics g, GameState state, double staminaPct, boolean exhausted) {
        int remainingDiamonds = 0;
        for (Item item : state.items) {
            if (item instanceof Ball) {
                remainingDiamonds++;
            }
        }

        int collected = state.config.objectiveCount - remainingDiamonds;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(10, 10, 250, 90);
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(16f));
        g.drawString("Diamonds: " + collected + " / " + state.config.objectiveCount, 18, 32);
        
        g.drawString(remainingDiamonds == 0 ? "Now go to the exit." : "Collect all diamonds.", 18, 52);
        drawStaminaBar(g, 18, 62, 230, 14, staminaPct, exhausted);
    }

    private void drawStaminaBar(Graphics g, int x, int y, int w, int h, double staminaPct, boolean exhausted) {
        g.setColor(new Color(50, 50, 50, 220));
        g.fillRoundRect(x, y, w, h, 6, 6);

        int    fillW = (int) (w * staminaPct);
        Color  fill;
        if (exhausted) {
            fill = new Color(100, 100, 100);
        } else if (staminaPct > 0.50) {
            fill = new Color(50, 200, 80);
        } else if (staminaPct > 0.25) {
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
        String label = exhausted ? "RECOVERING" : "STAMINA";
        g.setColor(exhausted ? new Color(220, 80, 80) : Color.WHITE);
        g.drawString(label, x + 3, y + h - 2);
    }

    /** World-pixel hearing radius shown on minimap (half the full audio range). */
    private static final double HEAR_RADIUS_WORLD = 640.0 * 0.98 / 2.0; // ≈ 314

    private void drawMinimap(Graphics g, GameState state, double playerX, double playerY, double playerAngle, RemotePlayer rp) {
        int mapSize = state.config.mapSize;
        int tilePx  = MINIMAP_PX / mapSize;
        int mapPx   = mapSize * tilePx;
        int offsetX = 10;
        int offsetY = 110;

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(offsetX - 4, offsetY - 4, mapPx + 8, mapPx + 8);

        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                if (x == state.exitTileX && y == state.exitTileY) {
                    drawMinimapDoor(g, offsetX, offsetY, x, y, tilePx, state.isExitOpen());
                    continue;
                }
                g.setColor(state.map[y][x] > 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.fillRect(offsetX + x * tilePx, offsetY + y * tilePx, tilePx, tilePx);
            }
        }

        // ── Proximity fog-of-war ─────────────────────────────────────────────
        int px = (int) (playerX / TILE_SIZE * tilePx);
        int py = (int) (playerY / TILE_SIZE * tilePx);
        // Hearing radius converted to minimap pixels
        int hearPx = (int) (HEAR_RADIUS_WORLD / TILE_SIZE * tilePx);

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Dark overlay everywhere outside the hear circle, clipped to minimap bounds
        Area fog = new Area(new java.awt.Rectangle(offsetX, offsetY, mapPx, mapPx));
        fog.subtract(new Area(new Ellipse2D.Double(
                offsetX + px - hearPx, offsetY + py - hearPx,
                hearPx * 2, hearPx * 2)));
        g2.setColor(new Color(0, 0, 0, 165));
        g2.fill(fog);

        // Subtle circle edge (hearing boundary)
        g2.setColor(new Color(220, 200, 100, 110));
        g2.drawOval(offsetX + px - hearPx, offsetY + py - hearPx, hearPx * 2, hearPx * 2);

        // ── Player dot + direction line ──────────────────────────────────────
        g.setColor(Color.RED);
        g.fillOval(offsetX + px - 4, offsetY + py - 4, 8, 8);
        g.drawLine(offsetX + px, offsetY + py,
                   offsetX + px + (int) (Math.cos(playerAngle) * 10),
                   offsetY + py + (int) (Math.sin(playerAngle) * 10));

        // ── Remote player dot ────────────────────────────────────────────────
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
        g.fillOval(offsetX + tilePx + 2, offsetY + tilePx + 2, 6, 6);

        for (Item item : state.items) {
            int itemPx = offsetX + (int) (item.getX() / TILE_SIZE * tilePx);
            int itemPy = offsetY + (int) (item.getY() / TILE_SIZE * tilePx);
            item.drawOnMinimap(g, itemPx, itemPy);
        }

        // ── Monsters: only show within hearing range ─────────────────────────
        for (MonsterEntity m : state.monsters) {
            double distWorld = Math.hypot(m.getX() - playerX, m.getY() - playerY);
            if (distWorld > HEAR_RADIUS_WORLD) continue; // outside hearing range — hidden
            MonsterRenderer renderer = new MonsterRenderer(m);
            renderer.drawOnMinimap(g, offsetX, offsetY, tilePx);
        }
    }

    private void drawMinimapDoor(Graphics g, int offsetX, int offsetY, int x, int y, int tilePx, boolean isExitOpen) {
        if (isExitOpen) {
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

    private void drawIPLabel(Graphics g, int width, int height) {
        if (localIP == null) {
            try { localIP = java.net.InetAddress.getLocalHost().getHostAddress(); }
            catch (Exception e) { localIP = "?.?.?.?"; }
        }
        String text = "IP: " + localIP;
        g.setFont(g.getFont().deriveFont(12f));
        int sw = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(width - sw - 20, height - 24, sw + 14, 18);
        g.setColor(new Color(170, 215, 255, 210));
        g.drawString(text, width - sw - 13, height - 10);
    }

    private void drawPauseMenu(Graphics g, int width, int height, GameState state, boolean isMultiplayer) {
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, width, height);

        int numOpts       = state.pauseMenuOptions.length;
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
            String opt = state.pauseMenuOptions[i];
            int oy = boxY + titleAreaH + i * optionSpacing;
            if (i == state.pauseMenuSelected) {
                g.setColor(new Color(50, 100, 220));
                g.fillRoundRect(width / 2 - 160, oy - 32, 320, 44, 12, 12);
                g.setColor(Color.WHITE);
            } else {
                g.setColor(new Color(170, 200, 240));
            }
            int ow = g.getFontMetrics().stringWidth(opt);
            g.drawString(opt, width / 2 - ow / 2, oy);
        }

        if (isMultiplayer) {
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

    private void drawGameOverMenu(Graphics g, int width, int height, GameState state) {
        boolean finalVictory = state.victory && state.config.isLast();
        boolean levelWon     = state.victory && !state.config.isLast();

        String title, subtitle;
        if (finalVictory) {
            title    = "You Win!";
            subtitle = "All 3 levels cleared!";
        } else if (levelWon) {
            title    = "Level " + state.config.level + " Complete!";
            subtitle = "Get ready for Level " + (state.config.level + 1) + "...";
        } else {
            title    = "Game Over";
            subtitle = state.remotePlayerLeft ? "Other player disconnected." : "You were defeated.";
        }

        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(0, 0, width, height);

        int boxWidth  = 440;
        int boxHeight = 60 + state.menuOptions.length * 34 + 80;
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
        for (int i = 0; i < state.menuOptions.length; i++) {
            String option = state.menuOptions[i];
            int optionY   = boxY + 115 + i * 34;
            if (i == state.selectedMenuOption) {
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

    private RayHit castRay(double rayAngle, GameState state, double playerX, double playerY) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);
        int    mapSize = state.config.mapSize;

        int mapX = (int) (playerX / TILE_SIZE);
        int mapY = (int) (playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);
        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE; }
        else             { stepX =  1; sideDistX = ((mapX + 1) * TILE_SIZE - playerX) * deltaDistX / TILE_SIZE; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE; }
        else             { stepY =  1; sideDistY = ((mapY + 1) * TILE_SIZE - playerY) * deltaDistY / TILE_SIZE; }

        boolean hit = false;
        int side = 0, wallType = 1;
        while (!hit) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) break;
            int tile = state.getMapTile(mapX, mapY);
            if (tile > 0) { hit = true; wallType = tile; }
        }

        double dist = (side == 0)
                ? (mapX * TILE_SIZE - playerX + (1 - stepX) * TILE_SIZE / 2.0) / rayDirX
                : (mapY * TILE_SIZE - playerY + (1 - stepY) * TILE_SIZE / 2.0) / rayDirY;
        if (dist <= 0) dist = 1;

        double wallX = (side == 0) ? playerY + dist * rayDirY : playerX + dist * rayDirX;
        wallX %= TILE_SIZE;
        if (wallX < 0) wallX += TILE_SIZE;

        int textureX = (int) ((wallX / TILE_SIZE) * TEX_SIZE);
        textureX = Math.max(0, Math.min(TEX_SIZE - 1, textureX));
        if ((side == 0 && rayDirX > 0) || (side == 1 && rayDirY < 0)) textureX = TEX_SIZE - 1 - textureX;

        int[][] texture;
        if (wallType == Door.DOOR_TILE && state.isExitOpen()) {
             texture = TextureFactory.createOpenDoor(); // Handle specific state override
        } else {
             texture = textureRegistry.get(wallType); // Cleanly fetch by ID!
        }
        return new RayHit(dist, wallType, textureX, side, texture);
    }

    private double castRayDistance(double rayAngle, GameState state, double playerX, double playerY, double playerAngle) {
        double rayDirX = Math.cos(rayAngle);
        double rayDirY = Math.sin(rayAngle);
        int    mapSize = state.config.mapSize;

        int mapX = (int) (playerX / TILE_SIZE);
        int mapY = (int) (playerY / TILE_SIZE);

        double deltaDistX = rayDirX == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirX);
        double deltaDistY = rayDirY == 0 ? 1e30 : Math.abs(TILE_SIZE / rayDirY);
        int    stepX, stepY;
        double sideDistX, sideDistY;

        if (rayDirX < 0) { stepX = -1; sideDistX = (playerX - mapX * TILE_SIZE) * deltaDistX / TILE_SIZE; }
        else             { stepX =  1; sideDistX = ((mapX + 1) * TILE_SIZE - playerX) * deltaDistX / TILE_SIZE; }
        if (rayDirY < 0) { stepY = -1; sideDistY = (playerY - mapY * TILE_SIZE) * deltaDistY / TILE_SIZE; }
        else             { stepY =  1; sideDistY = ((mapY + 1) * TILE_SIZE - playerY) * deltaDistY / TILE_SIZE; }

        boolean hit = false;
        int side = 0;
        while (!hit) {
            if (sideDistX < sideDistY) { sideDistX += deltaDistX; mapX += stepX; side = 0; }
            else                       { sideDistY += deltaDistY; mapY += stepY; side = 1; }
            if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) break;
            if (state.getMapTile(mapX, mapY) > 0) hit = true;
        }

        double dist = (side == 0)
                ? (mapX * TILE_SIZE - playerX + (1 - stepX) * TILE_SIZE / 2.0) / (rayDirX == 0 ? 1e-6 : rayDirX)
                : (mapY * TILE_SIZE - playerY + (1 - stepY) * TILE_SIZE / 2.0) / (rayDirY == 0 ? 1e-6 : rayDirY);
        if (dist <= 0) dist = 1;
        return dist * Math.cos(rayAngle - playerAngle);
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