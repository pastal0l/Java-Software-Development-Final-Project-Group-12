package UI;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

public class InputHandler {

    // Movement flags — read by PlayerController each frame
    public boolean moveForward, moveBackward, strafeLeft, strafeRight;
    public boolean turnLeft, turnRight;
    public boolean shiftHeld = false;

    private double  mouseDeltaX = 0;
    private boolean recentering = false;
    private Robot   robot;
    private Cursor  blankCursor;
    private final JPanel panel;

    public InputHandler(JPanel panel) {
        this.panel = panel;
        initRobot();
        installListeners();
    }

    /** Called by PlayerController — returns accumulated delta and resets it. */
    public double consumeMouseDelta() {
        double d = mouseDeltaX;
        mouseDeltaX = 0;
        return d;
    }

    public void reset() {
        moveForward = moveBackward = strafeLeft = strafeRight = false;
        turnLeft = turnRight = shiftHeld = false;
        mouseDeltaX = 0;
    }

    public void enableMouseCapture() {
        if (!panel.isShowing()) return;
        panel.setCursor(blankCursor);
        recenterMouse();
    }

    public void disableMouseCapture() {
        panel.setCursor(Cursor.getDefaultCursor());
    }

    // ── private ──────────────────────────────────────────────────────────

    private void initRobot() {
        try { robot = new Robot(); } catch (AWTException e) { robot = null; }
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        blankCursor = Toolkit.getDefaultToolkit()
                             .createCustomCursor(img, new Point(0, 0), "blank");
    }

    private void installListeners() {
        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { onMouseMoved(e.getX(), e.getY()); }
            @Override public void mouseDragged(MouseEvent e) { onMouseMoved(e.getX(), e.getY()); }
        });
        panel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { panel.requestFocusInWindow(); }
        });
    }

    private void onMouseMoved(int mouseX, int mouseY) {
        if (recentering) { recentering = false; return; }
        int dx = mouseX - panel.getWidth() / 2;
        mouseDeltaX += dx;
        recenterMouse();
    }

    private void recenterMouse() {
        if (robot == null || !panel.isShowing()) return;
        try {
            Point loc = panel.getLocationOnScreen();
            recentering = true;
            robot.mouseMove(loc.x + panel.getWidth() / 2, loc.y + panel.getHeight() / 2);
        } catch (Exception ignored) {}
    }
}