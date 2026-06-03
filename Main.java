import javax.swing.JFrame;

/**
 * Main: entry point for the Pseudo-3D Raycaster game.
 * Creates the window, initializes the game panel and starts the game loop.
 */
public class Main {
    /**
     * Application entry point. Creates the main window and starts the game.
     */
    public static void main(String[] args) {
        JFrame frame = new JFrame("Pseudo-3D Raycaster");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(true);

        GamePanel panel = new GamePanel();
        frame.add(panel);
        frame.pack();
        java.awt.Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(screenSize);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        panel.requestFocusForInput();
        panel.startGame();
    }
}
