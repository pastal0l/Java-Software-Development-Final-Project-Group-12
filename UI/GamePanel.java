package UI;

import audio.ISoundPlayer;
import domain.Ball;
import domain.GameConstants;
import domain.GameState;
import domain.MonsterEntity;
import network.INetworkClient;
import network.RemotePlayer;
import rendering.IRenderer;
import rendering.Renderer;
import world.IMapGenerator;
import static domain.GameConstants.*;

import javax.swing.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;

public class GamePanel extends JPanel implements ActionListener {

    private static final int FPS = 60;

    // Owned objects
    public  GameState        state;
    public  PlayerController player;
    public  InputHandler     input;
    public  INetworkClient    networkClient = null;
    public  RemotePlayer     remotePlayer  = null;

    private final IRenderer renderer;
    private final Timer    timer;
    private final ISoundPlayer sound;
    private long   lastUpdateTime = System.currentTimeMillis();
    private Runnable returnToMenuAction;

    // Multiplayer between-level intermission state
    private boolean mpIntermission = false;   // showing the Continue/Quit screen
    private boolean mpReadySent    = false;   // this player clicked Continue
    private int     mpMenuSelected = 0;       // 0 = Continue, 1 = Quit

    // ── Constructors ─────────────────────────────────────────────────────

    public GamePanel(ISoundPlayer sound, IMapGenerator mapGen) { this(null, sound, mapGen); }

    public GamePanel(INetworkClient client, ISoundPlayer sound, IMapGenerator mapGen) {
        setPreferredSize(new Dimension(GameConstants.WIDTH, GameConstants.HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);

        networkClient = client;
        input = new InputHandler(this);
        this.sound = sound;

        if (client == null) {
            state = new GameState(0, mapGen, sound);
        } else {
            state = new GameState(client, 0, mapGen, sound);   // multiplayer constructor
            remotePlayer = new RemotePlayer("P" + (client.getPlayerId() == 0 ? 2 : 1));
        }

        double startX = GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0;
        double startY = GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0;
        player = new PlayerController(state, startX, startY, Math.toRadians(45), sound);

        addKeyListener(new InputKeyAdapter());
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (!state.gameOverMenu) input.enableMouseCapture();
            }
            @Override public void focusLost(FocusEvent e) { input.disableMouseCapture(); }
        });

        renderer = new Renderer();
        timer    = new Timer(1000 / FPS, this);
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void startGame() {
        lastUpdateTime = System.currentTimeMillis();
        timer.start();
        input.enableMouseCapture();
    }

    public void setReturnToMenuAction(Runnable action) { returnToMenuAction = action; }

    // ── Game loop ─────────────────────────────────────────────────────────

    @Override
    public void actionPerformed(ActionEvent e) {
        long now  = System.currentTimeMillis();
        long dt   = Math.max(1, now - lastUpdateTime);
        lastUpdateTime = now;
        update(dt);
        state.floatPhase += 0.08;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // Full-screen intermission overlay — draw instead of game view
        if (mpIntermission) {
            drawIntermissionOverlay(g, getWidth(), getHeight());
            return;
        }

        // We pass primitives and state objects downward so Renderer remains decoupled!
        double staminaPct = player.stamina / PlayerController.MAX_STAMINA;
        boolean isMultiplayer = (networkClient != null);

        renderer.render(g, getWidth(), getHeight(), state,
                        player.playerX, player.playerY, player.playerAngle,
                        player.sprinting, staminaPct, player.exhausted,
                        remotePlayer, isMultiplayer);
    }

    // ── Intermission overlay ──────────────────────────────────────────────

    private void drawIntermissionOverlay(Graphics g, int w, int h) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Dark full-screen background
        g2.setColor(new Color(5, 10, 25));
        g2.fillRect(0, 0, w, h);

        // Glowing card
        int cw = 480, ch = 300;
        int cx = w / 2 - cw / 2, cy = h / 2 - ch / 2;

        // Card shadow
        g2.setColor(new Color(0, 200, 80, 30));
        g2.fillRoundRect(cx - 6, cy - 6, cw + 12, ch + 12, 28, 28);

        // Card body
        g2.setColor(new Color(12, 28, 55));
        g2.fillRoundRect(cx, cy, cw, ch, 20, 20);

        // Card border
        g2.setColor(new Color(60, 200, 90));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cx, cy, cw, ch, 20, 20);

        int mx = w / 2;   // horizontal center

        // ── Title ────────────────────────────────────────────────────────
        g2.setFont(new Font("Segoe UI", Font.BOLD, 32));
        g2.setColor(new Color(80, 230, 110));
        drawCentered(g2, "✓  LEVEL COMPLETE!", mx, cy + 62);

        // Thin divider
        g2.setColor(new Color(60, 200, 90, 80));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(cx + 30, cy + 80, cx + cw - 30, cy + 80);

        if (mpReadySent) {
            // ── Waiting for partner ───────────────────────────────────────
            g2.setFont(new Font("Segoe UI", Font.BOLD, 20));
            g2.setColor(new Color(230, 210, 70));
            drawCentered(g2, "Waiting for partner…", mx, cy + 145);

            g2.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            g2.setColor(new Color(120, 120, 120));
            drawCentered(g2, "The next level will begin once both players are ready.", mx, cy + 178);
        } else {
            // ── Menu options ──────────────────────────────────────────────
            String[] opts  = {"Continue  →", "Quit"};
            Color[]  cols  = {new Color(80, 220, 100), new Color(200, 90, 90)};
            int      optY  = cy + 130;

            for (int i = 0; i < opts.length; i++) {
                boolean sel = (i == mpMenuSelected);
                int     oy  = optY + i * 58;

                if (sel) {
                    // Highlight pill
                    g2.setColor(new Color(cols[i].getRed(), cols[i].getGreen(), cols[i].getBlue(), 35));
                    g2.fillRoundRect(cx + 60, oy - 26, cw - 120, 38, 12, 12);
                    g2.setColor(cols[i]);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(cx + 60, oy - 26, cw - 120, 38, 12, 12);
                }

                g2.setFont(new Font("Segoe UI", Font.BOLD, sel ? 22 : 19));
                g2.setColor(sel ? cols[i] : new Color(140, 140, 140));
                drawCentered(g2, opts[i], mx, oy);

                if (sel) {
                    // Arrow indicator
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
                    g2.setColor(cols[i]);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString("▶", cx + 70, oy);
                }
            }
        }

        // ── Hint bar ─────────────────────────────────────────────────────
        g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g2.setColor(new Color(80, 80, 80));
        drawCentered(g2, "↑ ↓  Navigate    Enter  Select", mx, cy + ch - 14);
    }

    /** Draw a string horizontally centred at (cx, y). */
    private void drawCentered(Graphics2D g2, String text, int cx, int y) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, cx - fm.stringWidth(text) / 2, y);
    }

    // ── Update ────────────────────────────────────────────────────────────

    private void update(long dt) {
        if (state.levelComplete) return;
        if (networkClient != null) updateMultiplayer(dt);
        else                       updateSinglePlayer(dt);
    }

    private void updateSinglePlayer(long dt) {
        state.remainingTimeMillis -= dt;
        if (state.remainingTimeMillis <= 0) { triggerGameOver(false); return; }

        player.applyMovement(dt, input);

        for (MonsterEntity m : state.monsters) m.update(player.playerX, player.playerY, state.map, TILE_SIZE);
        updateMonsterAudio();

        for (MonsterEntity m : state.monsters) {
            if (m.collidesWithPlayer(player.playerX, player.playerY)) {
                sound.stopMonsterSound();
                triggerGameOver(false);
                return;
            }
        }

        state.collectItems(player);
        if (state.checkExit(player.playerX, player.playerY)) {
            sound.playVictoryMusic();
            triggerGameOver(true);
        }
    }

    private void updateMultiplayer(long dt) {
        INetworkClient nc = networkClient;

        // ── Intermission: already showing screen — wait for START_NEXT ────
        if (mpIntermission) {
            if (nc.isNextLevelReady()) {
                mpIntermission = false;
                mpReadySent    = false;
                mpMenuSelected = 0;
                advanceMultiplayerLevel();
            }
            return;
        }

        // ── Server just finished a level — show the intermission UI ───────
        if (nc.isLevelCompleteScreen()) {
            mpIntermission = true;
            mpReadySent    = false;
            mpMenuSelected = 0;
            sound.stopMonsterSound();
            input.disableMouseCapture();
            input.reset();
            repaint();
            return;
        }

        state.remainingTimeMillis = nc.getServerTimeMs();

        int[] dc;
        while ((dc = nc.getDiamondsTaken().poll()) != null) {
            final int bx = dc[0], by = dc[1];
            state.items.removeIf(item -> (int) item.getX() == bx && (int) item.getY() == by);
            sound.playDing();
            
            // FIXED BUG: Check the polymorphic generic items list specifically for Balls
            boolean ballsRemaining = state.items.stream().anyMatch(item -> item instanceof Ball);
            if (!ballsRemaining && !state.door.isOpen()) {
                state.door.open();
            }
        }

        if (nc.isServerDoorOpen() && !state.door.isOpen()) state.door.open();

        double[] mx = nc.getMonsterX(), my = nc.getMonsterY();
        boolean[] mch  = nc.getMonsterChasing();
        double[]  mang = nc.getMonsterAngle();
        for (int i = 0; i < Math.min(state.monsters.size(), mx.length); i++) {
            state.monsters.get(i).setPosition(mx[i], my[i]);
            state.monsters.get(i).setChasing(mch[i]);
            if (mang != null && i < mang.length) {
                double fa = mang[i];
                state.monsters.get(i).setDirection(Math.cos(fa), Math.sin(fa));
            }
        }

        if (remotePlayer != null) {
            remotePlayer.x     = nc.getRemotePlayerX();
            remotePlayer.y     = nc.getRemotePlayerY();
            remotePlayer.angle = nc.getRemotePlayerAngle();
        }

        // Level-advance signal arrives before GAME_OVER — handle it first
        if (nc.isNextLevelReady()) {
            advanceMultiplayerLevel();
            return;
        }

        if (nc.isGameOver() && !state.levelComplete) {
            if (nc.isRemotePlayerLeft()) state.remotePlayerLeft = true;
            sound.stopMonsterSound();
            triggerGameOver(nc.isGameWon());
            return;
        }

        if (!state.paused) player.applyMovement(dt, input);
        updateMonsterAudio();
        nc.sendPosition(player.playerX, player.playerY, player.playerAngle);
    }

    // ── Game-state triggers ───────────────────────────────────────────────

    private void triggerGameOver(boolean won) {
        state.levelComplete = true;
        state.gameOverMenu  = true;
        state.victory       = won;
        state.selectedMenuOption = 0;

        if (networkClient != null) {
            state.menuOptions = new String[]{"Quit"};
        } else if (won && !state.config.isLast()) {
            state.menuOptions = new String[]{"Next Level", "Restart", "Quit"};
        } else if (won) {
            state.menuOptions = new String[]{"Play Again", "Quit"};
        } else {
            state.menuOptions = new String[]{"Restart", "Quit"};
        }

        input.disableMouseCapture();
        input.reset();
        timer.stop();
        requestFocusInWindow();
        repaint();
    }

    private void updateMonsterAudio() {
        if (state.monsters.isEmpty()) { sound.stopMonsterSound(); return; }
        MonsterEntity closest = null;
        double minDist = Double.MAX_VALUE;
        for (MonsterEntity m : state.monsters) {
            double d = Math.hypot(m.getX() - player.playerX, m.getY() - player.playerY);
            if (d < minDist) { minDist = d; closest = m; }
        }
        double dx  = closest.getX() - player.playerX;
        double dy  = closest.getY() - player.playerY;
        double vol = 1.0 - Math.min(minDist / 640.0, 1.0);
        double pan = Math.sin(normalizeAngle(Math.atan2(dy, dx) - player.playerAngle));
        if (vol <= 0.02 || state.levelComplete) sound.stopMonsterSound();
        else sound.updateMonsterSound(pan, vol);
    }

    private void restartGame() {
        state.loadLevel(state.getCurrentLevelIndex());
        double sx = GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0;
        double sy = GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0;
        player.reset(sx, sy, Math.toRadians(45));
        input.reset();
        timer.start();
        input.enableMouseCapture();
    }

    private void nextLevel() {
        state.loadLevel(state.getCurrentLevelIndex() + 1);
        double sx = GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0;
        double sy = GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0;
        player.reset(sx, sy, Math.toRadians(45));
        input.reset();
        timer.start();
        input.enableMouseCapture();
    }

    /** Seamlessly transition to the next level in multiplayer (server-driven). */
    private void advanceMultiplayerLevel() {
        state.reloadFromClient(networkClient);
        networkClient.clearNextLevel();
        double sx = GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0;
        double sy = GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0;
        player.reset(sx, sy, Math.toRadians(45));
        input.reset();
        if (!timer.isRunning()) timer.start();
        input.enableMouseCapture();
        sound.stopMonsterSound();
    }

    void returnToMenu() {
        timer.stop();
        state.paused = false;
        sound.stopMonsterSound();
        if (networkClient != null) networkClient.disconnect();
        if (returnToMenuAction != null) SwingUtilities.invokeLater(returnToMenuAction);
        else System.exit(0);
    }

    private void togglePauseMenu() {
        state.paused = !state.paused;
        state.pauseMenuSelected = 0;
        if (state.paused) {
            input.disableMouseCapture();
            sound.stopMonsterSound();
            if (networkClient == null) timer.stop();
        } else {
            lastUpdateTime = System.currentTimeMillis();
            if (networkClient == null) timer.start();
            input.enableMouseCapture();
        }
        repaint();
    }

    private double normalizeAngle(double a) {
        while (a >  Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ── Keyboard adapter ──────────────────────────────────────────────────

    private class InputKeyAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_W     -> input.moveForward  = true;
                case KeyEvent.VK_S     -> input.moveBackward = true;
                case KeyEvent.VK_A     -> input.strafeLeft   = true;
                case KeyEvent.VK_D     -> input.strafeRight  = true;
                case KeyEvent.VK_LEFT  -> input.turnLeft     = true;
                case KeyEvent.VK_RIGHT -> input.turnRight    = true;
                case KeyEvent.VK_SHIFT -> input.shiftHeld    = true;
                case KeyEvent.VK_UP    -> { if (mpIntermission) navigateIntermission(-1); else if (state.paused) navigatePause(-1); else if (state.gameOverMenu) navigateMenu(-1); }
                case KeyEvent.VK_DOWN  -> { if (mpIntermission) navigateIntermission(+1); else if (state.paused) navigatePause(+1); else if (state.gameOverMenu) navigateMenu(+1); }
                case KeyEvent.VK_ENTER -> { if (mpIntermission) selectIntermission();     else if (state.paused) selectPause();     else if (state.gameOverMenu) selectMenu(); }
                case KeyEvent.VK_ESCAPE -> { if (!state.gameOverMenu) togglePauseMenu(); }
                case KeyEvent.VK_R     -> { if (networkClient == null && !state.paused) restartGame(); }
            }
        }
        @Override
        public void keyReleased(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_W     -> input.moveForward  = false;
                case KeyEvent.VK_S     -> input.moveBackward = false;
                case KeyEvent.VK_A     -> input.strafeLeft   = false;
                case KeyEvent.VK_D     -> input.strafeRight  = false;
                case KeyEvent.VK_LEFT  -> input.turnLeft     = false;
                case KeyEvent.VK_RIGHT -> input.turnRight    = false;
                case KeyEvent.VK_SHIFT -> input.shiftHeld    = false;
            }
        }
    }

    private void navigateIntermission(int d) {
        if (mpReadySent) return;
        mpMenuSelected = (mpMenuSelected + 2 + d) % 2;
        repaint();
    }

    private void selectIntermission() {
        if (mpReadySent) return;
        if (mpMenuSelected == 0) {   // Continue
            networkClient.sendReady();
            mpReadySent = true;
            repaint();
        } else {                     // Quit
            networkClient.sendQuitLevel();
            returnToMenu();
        }
    }

    private void navigateMenu(int d) {
        state.selectedMenuOption = (state.selectedMenuOption + state.menuOptions.length + d) % state.menuOptions.length;
        repaint();
    }

    private void selectMenu() {
        switch (state.menuOptions[state.selectedMenuOption]) {
            case "Next Level"  -> nextLevel();
            case "Restart"     -> restartGame();
            case "Play Again"  -> { state.loadLevel(0); player.reset(GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0, GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0, Math.toRadians(45)); timer.start(); input.enableMouseCapture(); }
            case "Quit"        -> returnToMenu(); // FIXED BUG: Disconnect safely instead of crashing
        }
    }

    private void navigatePause(int d) {
        state.pauseMenuSelected = (state.pauseMenuSelected + state.pauseMenuOptions.length + d) % state.pauseMenuOptions.length;
        repaint();
    }

    private void selectPause() {
        switch (state.pauseMenuOptions[state.pauseMenuSelected]) {
            case "Resume"       -> togglePauseMenu();
            case "Back to Menu" -> returnToMenu();
        }
    }
}