import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Main: entry point for the Pseudo-3D Raycaster game.
 *
 * <p>Shows a lobby screen where the player can choose single-player,
 * host an online co-op game, or join one via IP address.</p>
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("3D Maze Raycaster");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(true);

            LobbyPanel lobby = new LobbyPanel(frame);
            frame.add(lobby);
            frame.pack();
            java.awt.Dimension screenSize =
                java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            frame.setSize(screenSize);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
