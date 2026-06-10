import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * GamePanel owns all game state and drives the update loop.
 * Rendering is delegated to {@link Renderer}.
 *
 * <p>Turning uses mouse look: horizontal mouse movement rotates the player,
 * and the cursor is captured (hidden + re-centered via {@link Robot}) during
 * active gameplay.  The cursor reappears on the game-over menu and when the
 * window loses focus.</p>
 *
 * Fields use package-private access so Renderer can read them each frame.
 */
public class GamePanel extends JPanel implements ActionListener {

    // -----------------------------------------------------------------------
    // Constants — never change across levels
    // -----------------------------------------------------------------------
    static final int    WIDTH     = 800;
    static final int    HEIGHT    = 600;
    static final int    TILE_SIZE = 64;
    static final int    TEX_SIZE  = 64;

    private static final int    FPS                  = 60;
    private static final double MOVE_SPEED           = 3.5;
    private static final double SPRINT_SPEED         = 6.5;
    private static final double ROTATE_SPEED         = Math.toRadians(3.5); // arrow-key fallback
    private static final double MOUSE_SENSITIVITY    = 0.0025;              // radians per pixel
    private static final int    FOOTSTEP_INTERVAL_MS = 300;
    private static final int    SPRINT_FOOTSTEP_MS   = 160;

    // Stamina — all rates are per second
    static final double  MAX_STAMINA               = 100.0;
    private static final double STAMINA_DRAIN      = 25.0;  // depleted per second while sprinting
    private static final double STAMINA_REGEN      = 12.5;  // restored per second while resting
    private static final double STAMINA_EXHAUST_THRESHOLD = 20.0; // stamina needed to sprint again after exhaustion

    // -----------------------------------------------------------------------
    // Fixed spawn positions
    // -----------------------------------------------------------------------
    final int startTileX = 1;
    final int startTileY = 1;

    // -----------------------------------------------------------------------
    // Per-level config (package-private — read by Renderer)
    // -----------------------------------------------------------------------
    LevelConfig config;
    int         exitTileX;
    int         exitTileY;

    // -----------------------------------------------------------------------
    // Game state (package-private — read by Renderer each frame)
    // -----------------------------------------------------------------------
    int[][]             map;

    double              playerX;
    double              playerY;
    double              playerAngle;
    /** Effective sprint state — true only when SHIFT held, not exhausted, and stamina > 0. */
    boolean             sprinting = false;
    /** Current stamina in [0, MAX_STAMINA]. */
    double              stamina   = MAX_STAMINA;
    /** True while stamina is recovering after full exhaustion. Sprint is locked until
     *  stamina reaches {@code STAMINA_EXHAUST_THRESHOLD}. */
    boolean             exhausted = false;

    final List<Monster> monsters = new ArrayList<>();
    Door                door;
    final List<Ball>    balls    = new ArrayList<>();

    boolean             gameOverMenu       = false;
    boolean             victory            = false;
    boolean             levelComplete      = false;
    int                 selectedMenuOption = 0;
    String[]            menuOptions        = {"Restart", "Quit"};

    long                remainingTimeMillis;
    double              floatPhase = 0;

    // -----------------------------------------------------------------------
    // Private — movement flags
    // -----------------------------------------------------------------------
    private boolean moveForward, moveBackward, strafeLeft, strafeRight;
    private boolean turnLeft, turnRight;     // keyboard fallback (arrow keys)
    private boolean shiftHeld = false;       // raw SHIFT key; sprinting = derived from this

    // -----------------------------------------------------------------------
    // Private — mouse look
    // -----------------------------------------------------------------------
    /**
     * Horizontal mouse delta accumulated each frame (radians).
     * Written by the mouse listener; consumed and reset in applyMovement().
     */
    private double  mouseDeltaX  = 0;
    /** True while Robot is re-centering the cursor — ignore that one event. */
    private boolean recentering  = false;
    private Robot   robot;           // null if AWTException during init
    private Cursor  blankCursor;

    // -----------------------------------------------------------------------
    // Private — misc
    // -----------------------------------------------------------------------
    private boolean victoryMusicPlayed = false;
    private long    lastUpdateTime     = System.currentTimeMillis();
    private long    lastFootstepTime   = 0;
    private int     currentLevelIndex  = 0;

    private final Random   random;
    private final Timer    timer;
    private final Renderer renderer;

    // -----------------------------------------------------------------------
    // Constructor — boots into Level 1
    // -----------------------------------------------------------------------
    public GamePanel() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setOpaque(true);
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(new InputAdapter());

        random = new Random();

        config    = LevelConfig.ALL[0];
        exitTileX = config.mapSize - 1;
        exitTileY = config.mapSize - 1;

        generateRandomMap();
        door = new Door(exitTileX, exitTileY);
        spawnMonsters(config.monsterCount);
        spawnBalls(config.objectiveCount);

        playerX     = startTileX * TILE_SIZE + TILE_SIZE / 2.0;
        playerY     = startTileY * TILE_SIZE + TILE_SIZE / 2.0;
        playerAngle = Math.toRadians(45);

        remainingTimeMillis = config.timeLimitMillis;
        renderer = new Renderer(this);
        timer    = new Timer(1000 / FPS, this);

        initMouseCapture();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void startGame() {
        lastUpdateTime = System.currentTimeMillis();
        timer.start();
        enableMouseCapture();
    }

    public void requestFocusForInput() {
        requestFocusInWindow();
    }

    // -----------------------------------------------------------------------
    // Mouse capture setup (called once in constructor)
    // -----------------------------------------------------------------------

    /**
     * Creates the {@link Robot}, blank cursor, and installs mouse/focus
     * listeners.  Safe to call before the window is visible.
     */
    private void initMouseCapture() {
        try {
            robot = new Robot();
        } catch (AWTException e) {
            // Robot unavailable — arrow keys remain as the only turning method.
            robot = null;
        }

        // Transparent 1×1 cursor used to hide the OS pointer during gameplay
        BufferedImage cursorImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        blankCursor = Toolkit.getDefaultToolkit()
                             .createCustomCursor(cursorImg, new Point(0, 0), "blank");

        // Track horizontal mouse movement for rotation
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { onMouseMoved(e.getX(), e.getY()); }
            @Override public void mouseDragged(MouseEvent e) { onMouseMoved(e.getX(), e.getY()); }
        });

        // Hide/show cursor when window focus changes
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (!gameOverMenu && !levelComplete) enableMouseCapture();
            }
            @Override
            public void focusLost(FocusEvent e) {
                disableMouseCapture();
            }
        });

        // Click inside the panel to recapture focus (e.g. after alt-tabbing back)
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }

    /** Hide cursor and start re-centering on each move. */
    private void enableMouseCapture() {
        if (!isShowing()) return;
        setCursor(blankCursor);
        recenterMouse();
    }

    /** Restore the default system cursor. */
    private void disableMouseCapture() {
        setCursor(Cursor.getDefaultCursor());
    }

    /**
     * Move the OS cursor to the panel's centre so it never hits the edge.
     * Sets {@link #recentering} so the resulting {@code mouseMoved} event
     * is ignored.
     */
    private void recenterMouse() {
        if (robot == null || !isShowing()) return;
        try {
            Point loc = getLocationOnScreen();
            recentering = true;
            robot.mouseMove(loc.x + getWidth() / 2, loc.y + getHeight() / 2);
        } catch (Exception ignored) {}
    }

    /**
     * Handle a raw mouse-position event.  Computes the delta from the panel
     * centre, adds it to {@link #mouseDeltaX}, then re-centres.
     */
    private void onMouseMoved(int mouseX, int mouseY) {
        // Skip the synthetic event that Robot fires after re-centering
        if (recentering) { recentering = false; return; }
        if (gameOverMenu || levelComplete) return;

        int dx = mouseX - getWidth()  / 2;
        mouseDeltaX += dx * MOUSE_SENSITIVITY;
        recenterMouse();
    }

    // -----------------------------------------------------------------------
    // Game loop
    // -----------------------------------------------------------------------

    @Override
    public void actionPerformed(ActionEvent e) {
        long now       = System.currentTimeMillis();
        long deltaTime = Math.max(1, now - lastUpdateTime);
        lastUpdateTime = now;
        updatePlayer(deltaTime);
        floatPhase += 0.08;
        repaint();
    }

    // -----------------------------------------------------------------------
    // Rendering (delegated)
    // -----------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        renderer.render(g);
    }

    // -----------------------------------------------------------------------
    // Update logic
    // -----------------------------------------------------------------------

    private void updatePlayer(long deltaTime) {
        if (levelComplete) return;

        remainingTimeMillis -= deltaTime;
        if (remainingTimeMillis <= 0) {
            remainingTimeMillis = 0;
            triggerGameOver(false);
            return;
        }

        applyMovement(deltaTime);

        for (Monster m : monsters) m.update(playerX, playerY, map, TILE_SIZE);
        updateMonsterAudio();

        for (Monster m : monsters) {
            if (m.collidesWithPlayer(playerX, playerY, TILE_SIZE)) {
                SoundPlayer.stopMonsterSound();
                triggerGameOver(false);
                return;
            }
        }

        collectBalls();
        checkExit();
    }

    private void applyMovement(long deltaTime) {
        // --- Rotation: mouse delta takes priority; arrow keys as fallback ---
        playerAngle += mouseDeltaX;
        mouseDeltaX  = 0;
        if (turnLeft)  playerAngle -= ROTATE_SPEED;
        if (turnRight) playerAngle += ROTATE_SPEED;

        // --- Stamina / exhaustion ---
        double dt = deltaTime / 1000.0;   // convert ms → seconds
        if (shiftHeld && !exhausted && stamina > 0) {
            sprinting = true;
            stamina  -= STAMINA_DRAIN * dt;
            if (stamina <= 0) {
                stamina   = 0;
                exhausted = true;
                sprinting = false;
            }
        } else {
            sprinting = false;
            stamina  += STAMINA_REGEN * dt;
            if (stamina >= MAX_STAMINA) stamina = MAX_STAMINA;
            if (exhausted && stamina >= STAMINA_EXHAUST_THRESHOLD) exhausted = false;
        }

        // --- Translation ---
        double speed = sprinting ? SPRINT_SPEED : MOVE_SPEED;
        double dx = 0, dy = 0;

        if (moveForward)  { dx += Math.cos(playerAngle) * speed;               dy += Math.sin(playerAngle) * speed; }
        if (moveBackward) { dx -= Math.cos(playerAngle) * speed;               dy -= Math.sin(playerAngle) * speed; }
        if (strafeLeft)   { dx += Math.cos(playerAngle - Math.PI / 2) * speed; dy += Math.sin(playerAngle - Math.PI / 2) * speed; }
        if (strafeRight)  { dx += Math.cos(playerAngle + Math.PI / 2) * speed; dy += Math.sin(playerAngle + Math.PI / 2) * speed; }

        boolean moved = false;
        if (!collides(playerX + dx, playerY)) { playerX += dx; moved = true; }
        if (!collides(playerX, playerY + dy)) { playerY += dy; moved = true; }

        if (moved && (moveForward || moveBackward || strafeLeft || strafeRight)) {
            long now      = System.currentTimeMillis();
            int  interval = sprinting ? SPRINT_FOOTSTEP_MS : FOOTSTEP_INTERVAL_MS;
            if (now - lastFootstepTime >= interval) {
                SoundPlayer.playFootstep();
                lastFootstepTime = now;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Level progression
    // -----------------------------------------------------------------------

    private void loadLevel(int index) {
        currentLevelIndex  = index;
        config             = LevelConfig.ALL[index];
        exitTileX          = config.mapSize - 1;
        exitTileY          = config.mapSize - 1;

        levelComplete      = false;
        gameOverMenu       = false;
        victory            = false;
        selectedMenuOption = 0;
        victoryMusicPlayed = false;
        sprinting          = false;
        shiftHeld          = false;
        stamina            = MAX_STAMINA;
        exhausted          = false;
        mouseDeltaX        = 0;

        generateRandomMap();
        door = new Door(exitTileX, exitTileY);
        spawnMonsters(config.monsterCount);
        spawnBalls(config.objectiveCount);

        playerX     = startTileX * TILE_SIZE + TILE_SIZE / 2.0;
        playerY     = startTileY * TILE_SIZE + TILE_SIZE / 2.0;
        playerAngle = Math.toRadians(45);

        SoundPlayer.stopMonsterSound();
        remainingTimeMillis = config.timeLimitMillis;
        lastFootstepTime    = 0;
        lastUpdateTime      = System.currentTimeMillis();
    }

    private void restartGame() {
        loadLevel(currentLevelIndex);
        timer.start();
        enableMouseCapture();
    }

    private void nextLevel() {
        loadLevel(currentLevelIndex + 1);
        timer.start();
        enableMouseCapture();
    }

    // -----------------------------------------------------------------------
    // Game-state triggers
    // -----------------------------------------------------------------------

    private void triggerGameOver(boolean won) {
        levelComplete      = true;
        gameOverMenu       = true;
        victory            = won;
        selectedMenuOption = 0;
        mouseDeltaX        = 0;

        if (won && !config.isLast()) {
            menuOptions = new String[]{"Next Level", "Restart", "Quit"};
        } else if (won) {
            menuOptions = new String[]{"Play Again", "Quit"};
        } else {
            menuOptions = new String[]{"Restart", "Quit"};
        }

        disableMouseCapture();
        timer.stop();
        requestFocusInWindow();
        repaint();
    }

    private void updateMonsterAudio() {
        if (monsters.isEmpty()) { SoundPlayer.stopMonsterSound(); return; }

        Monster closest = null;
        double  minDist = Double.MAX_VALUE;
        for (Monster m : monsters) {
            double d = Math.hypot(m.getX() - playerX, m.getY() - playerY);
            if (d < minDist) { minDist = d; closest = m; }
        }

        double dx     = closest.getX() - playerX;
        double dy     = closest.getY() - playerY;
        double volume = 1.0 - Math.min(minDist / 640.0, 1.0);
        double pan    = Math.sin(normalizeAngle(Math.atan2(dy, dx) - playerAngle));

        if (volume <= 0.02 || levelComplete || gameOverMenu) {
            SoundPlayer.stopMonsterSound();
        } else {
            SoundPlayer.updateMonsterSound(pan, volume);
        }
    }

    // -----------------------------------------------------------------------
    // Game-object logic
    // -----------------------------------------------------------------------

    private void spawnMonsters(int count) {
        monsters.clear();
        List<int[]> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] pos = findEmptyMonsterSpawn(taken);
            taken.add(pos);
            monsters.add(new Monster(pos[0], pos[1], TILE_SIZE));
        }
    }

    private void spawnBalls(int count) {
        int mapSize = config.mapSize;
        balls.clear();
        while (balls.size() < count) {
            int tileX = 1 + random.nextInt(mapSize - 2);
            int tileY = 1 + random.nextInt(mapSize - 2);
            if (map[tileY][tileX] != 0) continue;
            if ((tileX == startTileX && tileY == startTileY)
                    || (tileX == exitTileX && tileY == exitTileY)) continue;
            boolean occupied = balls.stream().anyMatch(
                    b -> (int) (b.x / TILE_SIZE) == tileX && (int) (b.y / TILE_SIZE) == tileY);
            if (occupied) continue;
            balls.add(new Ball(tileX * TILE_SIZE + TILE_SIZE / 2.0,
                               tileY * TILE_SIZE + TILE_SIZE / 2.0));
        }
    }

    private void collectBalls() {
        double  pickupRadius = 18;
        boolean collected    = false;
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b  = balls.get(i);
            double dx = playerX - b.x, dy = playerY - b.y;
            if (dx * dx + dy * dy <= pickupRadius * pickupRadius) {
                balls.remove(i);
                collected = true;
            }
        }
        if (collected) {
            SoundPlayer.playDing();
            if (balls.isEmpty()) door.open();
        }
    }

    private void checkExit() {
        if (door.isOpen() && isPlayerTouchingDoor()) {
            if (!victoryMusicPlayed) {
                victoryMusicPlayed = true;
                SoundPlayer.playVictoryMusic();
            }
            triggerGameOver(true);
        }
    }

    // -----------------------------------------------------------------------
    // Map / collision helpers (package-private — used by Renderer)
    // -----------------------------------------------------------------------

    int getMapTile(int mapX, int mapY) {
        if (door.isAt(mapX, mapY)) return door.getMapValue();
        return map[mapY][mapX];
    }

    boolean isExitOpen() {
        return door.isOpen();
    }

    // -----------------------------------------------------------------------
    // Private map helpers
    // -----------------------------------------------------------------------

    private boolean isWallTile(int mapX, int mapY) {
        int mapSize = config.mapSize;
        if (mapX < 0 || mapX >= mapSize || mapY < 0 || mapY >= mapSize) return true;
        if (door.isAt(mapX, mapY)) return !door.isOpen();
        return getMapTile(mapX, mapY) != 0;
    }

    private boolean collides(double x, double y) {
        return isWallTile((int) x / TILE_SIZE, (int) y / TILE_SIZE);
    }

    private boolean isPlayerTouchingDoor() {
        return (int) playerX / TILE_SIZE == exitTileX - 1
            && (int) playerY / TILE_SIZE == exitTileY;
    }

    private boolean isEmptyTile(int x, int y) {
        int mapSize = config.mapSize;
        if (x < 0 || x >= mapSize || y < 0 || y >= mapSize) return false;
        return map[y][x] == 0;
    }

    private int[] findEmptyMonsterSpawn(List<int[]> taken) {
        int mapSize = config.mapSize;
        int spawnX, spawnY;
        do {
            spawnX = 1 + random.nextInt(mapSize - 2);
            spawnY = 1 + random.nextInt(mapSize - 2);
            final int fx = spawnX, fy = spawnY;
            if (!isEmptyTile(fx, fy)) continue;
            if (fx == startTileX && fy == startTileY) continue;
            if (fx == exitTileX  && fy == exitTileY)  continue;
            if (taken.stream().anyMatch(p -> p[0] == fx && p[1] == fy)) continue;
            break;
        } while (true);
        return new int[]{spawnX, spawnY};
    }

    private void generateRandomMap() {
        map = MazeGenerator.generateMaze(config.mapSize, startTileX, startTileY,
                                         exitTileX - 1, exitTileY);
        map[startTileY][startTileX]     = 0;
        map[startTileY][startTileX + 1] = 0;
        map[startTileY + 1][startTileX] = 0;
        map[exitTileY][exitTileX - 1]   = 0;
    }

    private double normalizeAngle(double angle) {
        while (angle >  Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    // -----------------------------------------------------------------------
    // Keyboard input
    // -----------------------------------------------------------------------

    private class InputAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_W      -> moveForward  = true;
                case KeyEvent.VK_S      -> moveBackward = true;
                case KeyEvent.VK_A      -> strafeLeft   = true;
                case KeyEvent.VK_D      -> strafeRight  = true;
                case KeyEvent.VK_LEFT   -> turnLeft     = true;   // fallback if no mouse
                case KeyEvent.VK_RIGHT  -> turnRight    = true;
                case KeyEvent.VK_SHIFT  -> shiftHeld    = true;
                case KeyEvent.VK_UP    -> { if (gameOverMenu) navigateMenu(-1); }
                case KeyEvent.VK_DOWN  -> { if (gameOverMenu) navigateMenu(+1); }
                case KeyEvent.VK_ENTER -> { if (gameOverMenu) selectMenuOption(); }
                case KeyEvent.VK_R     -> restartGame();
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_W      -> moveForward  = false;
                case KeyEvent.VK_S      -> moveBackward = false;
                case KeyEvent.VK_A      -> strafeLeft   = false;
                case KeyEvent.VK_D      -> strafeRight  = false;
                case KeyEvent.VK_LEFT   -> turnLeft     = false;
                case KeyEvent.VK_RIGHT  -> turnRight    = false;
                case KeyEvent.VK_SHIFT  -> shiftHeld    = false;
            }
            if (gameOverMenu) repaint();
        }
    }

    private void navigateMenu(int delta) {
        selectedMenuOption = (selectedMenuOption + menuOptions.length + delta) % menuOptions.length;
        requestFocusInWindow();
        repaint();
    }

    private void selectMenuOption() {
        switch (menuOptions[selectedMenuOption]) {
            case "Next Level"  -> nextLevel();
            case "Restart"     -> restartGame();
            case "Play Again"  -> { loadLevel(0); timer.start(); enableMouseCapture(); }
            case "Quit"        -> System.exit(0);
        }
    }
}
