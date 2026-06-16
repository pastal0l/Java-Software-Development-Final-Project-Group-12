package UI;

import audio.ISoundPlayer;
import domain.Ball;
import domain.GameConstants;
import entity.MonsterEntity;
import network.INetworkClient;
import network.RemotePlayer;
import rendering.IRenderer;
import rendering.Renderer;
import world.IMapGenerator;
import static domain.GameConstants.*;

import javax.swing.*;

import java.awt.*;
import java.awt.event.*;

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
        boolean[] mch = nc.getMonsterChasing();
        for (int i = 0; i < Math.min(state.monsters.size(), mx.length); i++) {
            state.monsters.get(i).setPosition(mx[i], my[i]);
            state.monsters.get(i).setChasing(mch[i]);
        }

        if (remotePlayer != null) {
            remotePlayer.x     = nc.getRemotePlayerX();
            remotePlayer.y     = nc.getRemotePlayerY();
            remotePlayer.angle = nc.getRemotePlayerAngle();
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