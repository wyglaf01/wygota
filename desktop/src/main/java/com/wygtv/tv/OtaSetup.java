package com.wygtv.tv;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Adding or changing the tuner: the address starts as hdhomerun.local (the name every HDHomeRun answers
 * to on a home network); "Find tuners" searches the network, and any IP address can be typed instead.
 */
final class OtaSetup extends JDialog {
    private final JTextField host = new JTextField(28);
    private final JLabel status = new JLabel(" ");
    private final JButton find = new JButton("Find tuners on my network"), connect = new JButton("Connect");
    private final BiConsumer<Hdhr.Device, List<OtaLineup.Channel>> done;

    OtaSetup(JFrame owner, String address, String problem, BiConsumer<Hdhr.Device, List<OtaLineup.Channel>> done) {
        super(owner, "Set up your HDHomeRun or Tablo", true);
        this.done = done;
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        JTextArea note = new JTextArea("Channels come from the tuner's lineup (the same list as http://hdhomerun.local/lineup.html), "
                + "grouped by channel number. The TV guide comes from SiliconDust's free HDHomeRun guide.\n\n"
                + "If hdhomerun.local isn't found, press Find tuners or type the tuner's IP address "
                + "(shown in your router, or at my.hdhomerun.com).\n\n"
                + "A black Tablo (DUAL, QUAD, LITE) works too: press Find tuners or type its IP address. Its channels and TV guide come from the Tablo. "
                + "The white 4th Gen Tablo only works in Tablo's own app." + (problem == null ? "" : "\n\n" + problem));
        note.setLineWrap(true); note.setWrapStyleWord(true); note.setEditable(false); note.setOpaque(false); note.setColumns(44);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(note);
        p.add(Box.createVerticalStrut(12));
        JLabel l = new JLabel("Tuner address (hdhomerun.local or an IP like 192.168.1.50):");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
        host.setText(address == null || address.isEmpty() ? Hdhr.DEFAULT_HOST : address);
        host.setAlignmentX(Component.LEFT_ALIGNMENT);
        host.addActionListener(e -> connect());
        p.add(host);
        p.add(Box.createVerticalStrut(8));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(status);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        buttons.add(find); buttons.add(cancel); buttons.add(connect);
        find.addActionListener(e -> find());
        connect.addActionListener(e -> connect());
        cancel.addActionListener(e -> dispose());
        getContentPane().add(p, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(connect);
        pack();
        setLocationRelativeTo(owner);
    }

    private void busy(boolean b, String text) {
        find.setEnabled(!b); connect.setEnabled(!b); host.setEnabled(!b);
        status.setText(text == null || text.isEmpty() ? " " : text);
    }

    private void find() {
        busy(true, "Looking for HDHomeRun tuners…");
        new SwingWorker<List<Hdhr.Device>, Void>() {
            @Override protected List<Hdhr.Device> doInBackground() {
                List<Hdhr.Device> all = new java.util.ArrayList<>(Hdhr.discoverAll());
                all.addAll(OtaTablo.discoverAll()); // black Tablos, through Tablo's cloud lookup
                return all;
            }

            @Override protected void done() {
                List<Hdhr.Device> found;
                try { found = get(); } catch (Exception e) { found = java.util.Collections.emptyList(); }
                busy(false, "");
                if (found.isEmpty()) {
                    status.setText("<html>No tuner answered. Check it's on and on the same network, then type its IP address.</html>");
                    pack();
                    return;
                }
                Hdhr.Device pick = found.get(0);
                if (found.size() > 1) {
                    String[] names = new String[found.size()];
                    for (int i = 0; i < names.length; i++) names[i] = found.get(i).summary();
                    Object chosen = JOptionPane.showInputDialog(OtaSetup.this, "Found " + found.size() + " tuners:", "Choose a tuner",
                            JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
                    if (chosen == null) return;
                    for (int i = 0; i < names.length; i++) if (names[i].equals(chosen)) pick = found.get(i);
                }
                host.setText(Hdhr.hostOf(pick.base));
                status.setText("Found " + pick.summary());
            }
        }.execute();
    }

    private void connect() {
        String base = Hdhr.baseFromInput(host.getText());
        if (base.isEmpty()) { status.setText("Enter the tuner's address, like hdhomerun.local or 192.168.1.50."); return; }
        final String typed = host.getText();
        busy(true, "Connecting to " + Hdhr.hostOf(base) + "…");
        new SwingWorker<Object[], Void>() {
            String problem;

            @Override protected Object[] doInBackground() {
                Hdhr.Device dev;
                try { dev = Hdhr.describe(base); } catch (Exception e) {
                    // Not an HDHomeRun: maybe a black Tablo at that address.
                    try { dev = OtaTablo.describe(typed); }
                    catch (Tablo.LockedException locked) { problem = Tablo.LOCKED; return null; }
                    catch (Exception notTablo) {
                        problem = "No HDHomeRun or Tablo answered at " + Hdhr.hostOf(base) + ". Check the address, or press Find tuners."
                                + (base.contains(".local") ? " If this computer can't look up hdhomerun.local, use the tuner's IP address." : "");
                        return null;
                    }
                }
                List<OtaLineup.Channel> chans;
                try { chans = OtaLineup.load(dev.lineupUrl); } catch (Exception e) { problem = "The tuner answered but its channel list couldn't be read."; return null; }
                if (chans.isEmpty()) { problem = "The tuner has no channels yet. Run a channel scan at " + Hdhr.hostOf(dev.base) + ", then try again."; return null; }
                return new Object[]{dev, chans};
            }

            @SuppressWarnings("unchecked")
            @Override protected void done() {
                Object[] r = null;
                try { r = get(); } catch (Exception ignored) { }
                busy(false, "");
                if (r == null) { status.setText("<html>" + OtaGuide.escape(problem == null ? "Couldn't connect." : problem) + "</html>"); pack(); return; }
                dispose();
                done.accept((Hdhr.Device) r[0], (List<OtaLineup.Channel>) r[1]);
            }
        }.execute();
    }
}
