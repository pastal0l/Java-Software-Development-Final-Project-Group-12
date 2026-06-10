import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Start-screen shown when the game launches.
 *
 * <ul>
 *   <li><b>Single Player</b> — creates a {@link GamePanel} with no network client.</li>
 *   <li><b>Host Game</b>   — starts a {@link GameServer} on a daemon thread,
 *       then connects to {@code localhost} as player&nbsp;0.</li>
 *   <li><b>Join Game</b>   — connects to the IP entered in the text field as
 *       player&nbsp;1 (or whichever slot the server assigns).</li>
 * </ul>
 *
 * Connection happens on a background thread so the EDT stays responsive.
 * Once the server sends {@code START} the lobby hands off to a
 * {@link GamePanel} built with the received map data.
 */
class LobbyPanel extends JPanel {

    private final JFrame frame;
    private JTextField   ipField;
    private JLabel       statusLabel;

    LobbyPanel(JFrame frame) {
        this.frame = frame;
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(GamePanel.WIDTH, GamePanel.HEIGHT));
        buildUI();
    }

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------

    private void buildUI() {
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets    = new Insets(14, 12, 14, 12);
        c.gridx     = 0;
        c.gridwidth = 2;
        c.fill      = GridBagConstraints.NONE;
        c.anchor    = GridBagConstraints.CENTER;

        // Title
        c.gridy = 0;
        JLabel title = styled(new JLabel("3D MAZE RAYCASTER"), Color.WHITE, Font.BOLD, 40f);
        add(title, c);

        c.gridy = 1;
        JLabel sub = styled(new JLabel("Choose a mode to begin"), new Color(140, 180, 240), Font.PLAIN, 17f);
        add(sub, c);

        // Single player
        c.gridy = 2;
        JButton spBtn = makeButton("Single Player", new Color(30, 110, 60));
        spBtn.addActionListener(e -> startSinglePlayer());
        add(spBtn, c);

        // Separator label
        c.gridy = 3;
        add(styled(new JLabel("─────  Online Co-op  ─────"), new Color(100, 100, 100), Font.PLAIN, 13f), c);

        // Host game
        c.gridy = 4;
        JButton hostBtn = makeButton("Host Game  (port " + GameServer.PORT + ")", new Color(60, 60, 140));
        hostBtn.addActionListener(e -> hostGame());
        add(hostBtn, c);

        // Join game row
        c.gridy     = 5;
        c.gridwidth = 1;
        ipField = new JTextField("localhost", 18);
        ipField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        ipField.setBackground(new Color(30, 30, 50));
        ipField.setForeground(Color.WHITE);
        ipField.setCaretColor(Color.WHITE);
        ipField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 80, 120), 1),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        add(ipField, c);

        c.gridx = 1;
        JButton joinBtn = makeButton("Join Game", new Color(80, 60, 130));
        joinBtn.addActionListener(e -> connectToServer(ipField.getText().trim()));
        add(joinBtn, c);

        // Status
        c.gridx     = 0;
        c.gridy     = 6;
        c.gridwidth = 2;
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(220, 200, 80));
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 14));
        add(statusLabel, c);
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private void startSinglePlayer() {
        GamePanel panel = new GamePanel();
        switchToGame(panel);
    }

    private void hostGame() {
        setStatus("Starting server on port " + GameServer.PORT + "...");

        GameServer server = new GameServer();
        Thread serverThread = new Thread(server, "GameServer");
        serverThread.setDaemon(true);
        serverThread.start();

        // Small delay to let the ServerSocket bind before we connect
        Timer delay = new Timer(450, e -> connectToServer("localhost"));
        delay.setRepeats(false);
        delay.start();
    }

    private void connectToServer(String ip) {
        setStatus("Connecting to " + ip + "…");
        disableButtons();

        new Thread(() -> {
            try {
                NetworkClient client = new NetworkClient();
                client.connect(ip, GameServer.PORT,
                    () -> SwingUtilities.invokeLater(() ->
                        setStatus("Connected — waiting for second player…")));

                // Got START — build the game on the EDT
                SwingUtilities.invokeLater(() -> {
                    GamePanel panel = new GamePanel(client);
                    switchToGame(panel);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Connection failed: " + ex.getMessage());
                    enableButtons();
                });
            }
        }, "lobby-connect").start();
    }

    // -----------------------------------------------------------------------
    // Panel swap
    // -----------------------------------------------------------------------

    private void switchToGame(GamePanel panel) {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(panel);
        frame.revalidate();
        frame.repaint();
        panel.requestFocusInWindow();
        panel.startGame();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setStatus(String msg) { statusLabel.setText(msg); }

    private void disableButtons() {
        for (Component c : getComponents()) {
            if (c instanceof JButton) c.setEnabled(false);
        }
    }

    private void enableButtons() {
        for (Component c : getComponents()) {
            if (c instanceof JButton) c.setEnabled(true);
        }
    }

    private static JButton makeButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        b.setPreferredSize(new Dimension(320, 50));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static <T extends JLabel> T styled(T lbl, Color color, int style, float size) {
        lbl.setForeground(color);
        lbl.setFont(new Font(Font.SANS_SERIF, style, (int) size));
        return lbl;
    }
}
