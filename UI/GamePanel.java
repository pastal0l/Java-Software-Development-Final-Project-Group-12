package UI;

import audio.SoundPlayer;
import entity.MonsterEntity;
import network.NetworkClient;
import network.RemotePlayer;
import rendering.Renderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class GamePanel extends JPanel implements ActionListener {

    public static final int WIDTH     = 800;
    public static final int HEIGHT    = 600;
    public static final int TILE_SIZE = 64;
    public static final int TEX_SIZE  = 64;

    private static final int FPS = 60;

    // Owned objects
    public  GameState        state;
    public  PlayerController player;
    public  InputHandler     input;
    public  NetworkClient    networkClient = null;
    public  RemotePlayer     remotePlayer  = null;

    private final Renderer renderer;
    private final Timer    timer;
    private long   lastUpdateTime = System.currentTimeMillis();
    private Runnable returnToMenuAction;

    // ── Constructors ─────────────────────────────────────────────────────

    public GamePanel() { this(null); }

    public GamePanel(NetworkClient client) {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);

        networkClient = client;
        input = new InputHandler(this);

        if (client == null) {
            state = new GameState(0);
        } else {
            state = new GameState(client, 0);   // multiplayer constructor
            remotePlayer = new RemotePlayer("P" + (client.myPlayerId == 0 ? 2 : 1));
        }

        double startX = GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0;
        double startY = GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0;
        player = new PlayerController(state, startX, startY, Math.toRadians(45));

        addKeyListener(new InputKeyAdapter());
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (!state.gameOverMenu) input.enableMouseCapture();
            }
            @Override public void focusLost(FocusEvent e) { input.disableMouseCapture(); }
        });

        renderer = new Renderer(this);
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
        renderer.render(g);
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
                SoundPlayer.stopMonsterSound();
                triggerGameOver(false);
                return;
            }
        }

        state.collectBalls(player.playerX, player.playerY);
        if (state.checkExit(player.playerX, player.playerY)) {
            SoundPlayer.playVictoryMusic();
            triggerGameOver(true);
        }
    }

    private void updateMultiplayer(long dt) {
        NetworkClient nc = networkClient;
        state.remainingTimeMillis = nc.serverTimeMs;

        int[] dc;
        while ((dc = nc.diamondsTaken.poll()) != null) {
            final int bx = dc[0], by = dc[1];
            state.balls.removeIf(b -> (int) b.x == bx && (int) b.y == by);
            SoundPlayer.playDing();
            if (state.balls.isEmpty()) state.door.open();
        }

        if (nc.serverDoorOpen && !state.door.isOpen()) state.door.open();

        double[] mx = nc.monsterX, my = nc.monsterY;
        boolean[] mch = nc.monsterChasing;
        for (int i = 0; i < Math.min(state.monsters.size(), mx.length); i++) {
            state.monsters.get(i).setPosition(mx[i], my[i]);
            state.monsters.get(i).setChasing(mch[i]);
        }

        if (remotePlayer != null) {
            remotePlayer.x     = nc.remotePlayerX;
            remotePlayer.y     = nc.remotePlayerY;
            remotePlayer.angle = nc.remotePlayerAngle;
        }

        if (nc.gameOver && !state.levelComplete) {
            if (nc.remotePlayerLeft) state.remotePlayerLeft = true;
            SoundPlayer.stopMonsterSound();
            triggerGameOver(nc.gameWon);
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
        if (state.monsters.isEmpty()) { SoundPlayer.stopMonsterSound(); return; }
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
        if (vol <= 0.02 || state.levelComplete) SoundPlayer.stopMonsterSound();
        else SoundPlayer.updateMonsterSound(pan, vol);
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

    void returnToMenu() {
        timer.stop();
        state.paused = false;
        SoundPlayer.stopMonsterSound();
        if (networkClient != null) networkClient.disconnect();
        if (returnToMenuAction != null) SwingUtilities.invokeLater(returnToMenuAction);
        else System.exit(0);
    }

    private void togglePauseMenu() {
        state.paused = !state.paused;
        state.pauseMenuSelected = 0;
        if (state.paused) {
            input.disableMouseCapture();
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
                case KeyEvent.VK_UP    -> { if (state.paused) navigatePause(-1); else if (state.gameOverMenu) navigateMenu(-1); }
                case KeyEvent.VK_DOWN  -> { if (state.paused) navigatePause(+1); else if (state.gameOverMenu) navigateMenu(+1); }
                case KeyEvent.VK_ENTER -> { if (state.paused) selectPause();     else if (state.gameOverMenu) selectMenu(); }
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

    private void navigateMenu(int d) {
        state.selectedMenuOption = (state.selectedMenuOption + state.menuOptions.length + d) % state.menuOptions.length;
        repaint();
    }

    private void selectMenu() {
        switch (state.menuOptions[state.selectedMenuOption]) {
            case "Next Level"  -> nextLevel();
            case "Restart"     -> restartGame();
            case "Play Again"  -> { state.loadLevel(0); player.reset(GameState.START_TILE_X * TILE_SIZE + TILE_SIZE / 2.0, GameState.START_TILE_Y * TILE_SIZE + TILE_SIZE / 2.0, Math.toRadians(45)); timer.start(); input.enableMouseCapture(); }
            case "Quit"        -> System.exit(0);
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