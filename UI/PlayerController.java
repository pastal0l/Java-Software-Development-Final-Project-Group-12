package UI;

import audio.ISoundPlayer;
import static domain.GameConstants.TILE_SIZE;

import domain.GameState;
import domain.IPlayer;

public class PlayerController implements IPlayer {

    private static final double MOVE_SPEED           = 3.5;
    private static final double SPRINT_SPEED         = 6.5;
    private static final double ROTATE_SPEED         = Math.toRadians(3.5);
    private static final double MOUSE_SENSITIVITY    = 0.0025;
    private static final int    FOOTSTEP_INTERVAL_MS = 300;
    private static final int    SPRINT_FOOTSTEP_MS   = 160;

    public static final double MAX_STAMINA              = 100.0;
    private static final double STAMINA_DRAIN           = 25.0;
    private static final double STAMINA_REGEN           = 12.5; 
    private static final double STAMINA_EXHAUST_THRESHOLD = 20.0;

    public double  playerX;
    public double  playerY;
    public double  playerAngle;
    public boolean sprinting = false;
    public double  stamina   = MAX_STAMINA;
    public boolean exhausted = false;

    private final GameState state;
    private long   lastFootstepTime = 0;
    private final ISoundPlayer sound;

    @Override
    public double getX() {
        return playerX;
    }
    @Override
    public double getY() {
        return playerY;
    }

    public PlayerController(GameState state, double startX, double startY, double startAngle, ISoundPlayer sound) {
        this.state       = state;
        this.playerX     = startX;
        this.playerY     = startY;
        this.playerAngle = startAngle;
        this.sound = sound;
    }

    public void reset(double startX, double startY, double startAngle) {
        playerX     = startX;
        playerY     = startY;
        playerAngle = startAngle;
        sprinting   = false;
        stamina     = MAX_STAMINA;
        exhausted   = false;
        lastFootstepTime = 0;
    }

    public void applyMovement(long deltaTime, InputHandler input) {
        // Rotation
        playerAngle += input.consumeMouseDelta() * MOUSE_SENSITIVITY;
        if (input.turnLeft)  playerAngle -= ROTATE_SPEED;
        if (input.turnRight) playerAngle += ROTATE_SPEED;

        // Stamina — only drain when actually moving
        boolean isMoving = input.moveForward || input.moveBackward
                        || input.strafeLeft  || input.strafeRight;
        double dt = deltaTime / 1000.0;
        if (input.shiftHeld && isMoving && !exhausted && stamina > 0) {
            sprinting = true;
            stamina  -= STAMINA_DRAIN * dt;
            if (stamina <= 0) { stamina = 0; exhausted = true; sprinting = false; }
        } else {
            sprinting = false;
            stamina  += STAMINA_REGEN * dt;
            if (stamina >= MAX_STAMINA) stamina = MAX_STAMINA;
            if (exhausted && stamina >= STAMINA_EXHAUST_THRESHOLD) exhausted = false;
        }

        // Translation
        double speed = sprinting ? SPRINT_SPEED : MOVE_SPEED;
        double dx = 0, dy = 0;
        if (input.moveForward)  { dx += Math.cos(playerAngle) * speed; dy += Math.sin(playerAngle) * speed; }
        if (input.moveBackward) { dx -= Math.cos(playerAngle) * speed; dy -= Math.sin(playerAngle) * speed; }
        if (input.strafeLeft)   { dx += Math.cos(playerAngle - Math.PI / 2) * speed; dy += Math.sin(playerAngle - Math.PI / 2) * speed; }
        if (input.strafeRight)  { dx += Math.cos(playerAngle + Math.PI / 2) * speed; dy += Math.sin(playerAngle + Math.PI / 2) * speed; }

        boolean moved = false;
        if (!collides(playerX + dx, playerY)) { playerX += dx; moved = true; }
        if (!collides(playerX, playerY + dy)) { playerY += dy; moved = true; }

        if (moved && (input.moveForward || input.moveBackward || input.strafeLeft || input.strafeRight)) {
            long now      = System.currentTimeMillis();
            int  interval = sprinting ? SPRINT_FOOTSTEP_MS : FOOTSTEP_INTERVAL_MS;
            if (now - lastFootstepTime >= interval) {
                sound.playFootstep();
                lastFootstepTime = now;
            }
        }
    }

    private boolean collides(double x, double y) {
        return state.isWallTile(
            (int) x / TILE_SIZE,
            (int) y / TILE_SIZE);
    }
}