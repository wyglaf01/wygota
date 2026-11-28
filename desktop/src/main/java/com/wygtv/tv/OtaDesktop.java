package com.wygtv.tv;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

/**
 * Minimal desktop entry point. The full player UI is still being built;
 * this exists so the Ubuntu/Windows distributions can be produced.
 */
public class OtaDesktop {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("WygOTA");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(640, 360);
            frame.setLocationRelativeTo(null);
            JLabel label = new JLabel("<html><center>WygOTA desktop<br>HDHomeRun antenna TV<br><br>Full player UI coming soon.<br>Requires VLC installed.</center></html>", JLabel.CENTER);
            frame.add(label, BorderLayout.CENTER);
            frame.setVisible(true);
        });
    }
}
