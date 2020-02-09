/*
    Copyright 2020 Hendrik Iben

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.package com.github.hiben;
*/
package com.github.hiben;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Base64;
import java.util.Locale;

/**
 * A small application to take captures of the screen.<br>
 * The goal of writing this software was to only use a single source file and
 * to have no dependencies apart from the JRE.<br>
 * The main motivation was to try out different parts of the JDK - there are better
 * screen capture utilities out there :-)<br>
 * <br>
 * Lessons learned:<br>
 * - Use window with opacity to show on-screen preview<br>
 * - Capture screen area with Robot class<br>
 * - Copy image to clipboard<br>
 * - Draggable non-decorated window<br>
 * - Embed image in source to use as application icon<br>
 * - Default values from system properties with safe parsing<br>
 * - Dialog for application info with HTML content, and<br>
 * - Scrollable HTML content with preferred initial size<br>
 * - Lookup of default dialog icons with magic constants<br>
 *
 *  Changelog:
 *  09.02.2020 - removed unneeded exception handling
 *  08.02.2020 - initial version
 */
public class QuickCap implements Runnable {
    private JFrame frame;
    private JPopupMenu menu;
    private JDialog infoDialog;

    private JButton controlButton;

    // these two points define the screen selection
    private Point p1 = new Point();
    private Point p2 = new Point();

    // this timer updates the preview area
    private Timer timer;

    // these flags control the selection process
    private boolean fetchTL = false;
    private boolean fetchBR = false;

    // Nobody will ever change these defaults - but they could...
    private Color previewColor = Color.decode(System.getProperty("QuickCap.color", "#0000FF"));
    private Color previewBorder = Color.decode(System.getProperty("QuickCap.border", "#000000"));
    // Exception-safe wrapper for US format float parsing
    private float previewOpacity = parseFloat(System.getProperty("QuickCap.opacity","0.3"), 0.3f);

    // this window serves as the selection preview
    private JFrame previewFrame;

    public static void main(String...args) {
        SwingUtilities.invokeLater(new QuickCap());
    }

    @Override
    public void run() {
        frame = new JFrame(MouseInfo.getPointerInfo().getDevice().getDefaultConfiguration());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setTitle("QuickCap");

        byte [] iconData = Base64.getMimeDecoder().decode(iconB64);
        try {
            BufferedImage bimg = ImageIO.read(new ByteArrayInputStream(iconData));
            if(bimg != null) {
                frame.setIconImage(bimg);
            }
        } catch (IOException e) {
            System.err.println("Unable to create icon...");
        }

        JPanel panel = new JPanel();

        panel.setLayout(new BorderLayout());

        panel.add(controlButton = new JButton(new AbstractAction("Selection") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(fetchTL) {
                    controlButton.setText("From");
                    fetchTL = false;
                    fetchBR = true;
                } else {
                    if(fetchBR) {
                        fetchBR = false;
                        controlButton.setText("Ready!");
                    } else {
                        if(p2.x == 0 && p2.y == 0) {
                            Point p = MouseInfo.getPointerInfo().getLocation();
                            p2.setLocation(p);
                        }
                        controlButton.setText("Extend");
                        fetchTL = true;
                    }
                }
            }
        }), BorderLayout.CENTER);

        panel.setBorder(BorderFactory.createTitledBorder("QuickCap"));

        frame.add(panel);

        controlButton.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if(e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if(!previewFrame.isVisible()) {
                        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                    }
                    cancel();
                }
                if(e.getKeyCode() == KeyEvent.VK_ENTER) {
                    capture();
                }
                if(e.getKeyCode() == KeyEvent.VK_F1) {
                    prepareInfoDialog();
                }
            }
        });

        MouseAdapter dragMove = new MouseAdapter() {
            private Point dragStart = null;

            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                if(e.isPopupTrigger()) {
                    menu.show(frame, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if(e.isPopupTrigger()) {
                    menu.show(frame, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point dp = e.getLocationOnScreen();
                frame.setLocation(dp.x - dragStart.x, dp.y - dragStart.y);
            }
        };

        frame.addMouseListener(dragMove);
        frame.addMouseMotionListener(dragMove);

        menu = new JPopupMenu();
        menu.add(new AbstractAction("About...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                prepareInfoDialog();
            }
        });

        menu.addSeparator();

        menu.add(new AbstractAction("Exit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            }
        });

        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);

        frame.pack();

        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        timer = new Timer(40, timerListener);
        timer.start();

        previewFrame = new JFrame();
        previewFrame.setTitle("Selection");
        previewFrame.setUndecorated(true);
        JPanel pp = new JPanel();
        pp.setBackground(previewColor);
        pp.setBorder(BorderFactory.createLineBorder(previewBorder, 1));
        previewFrame.add(pp);
        previewFrame.setOpacity(previewOpacity);
        previewFrame.setFocusable(false);
        previewFrame.setAlwaysOnTop(true);

        previewFrame.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if(e.getButton() == MouseEvent.BUTTON1) {
                    capture();
                } else {
                    cancel();
                }
            }
        });
    }

    private void cancel() {
        fetchTL = false;
        fetchBR = false;
        previewFrame.setVisible(false);
        p2.x = 0;
        p2.y = 0;
        controlButton.setText("Selection");
    }

    private void capture() {
        if(previewFrame.isVisible()) {
            fetchTL = false;
            fetchBR = false;
            Rectangle fetchR = new Rectangle(previewFrame.getLocation(), previewFrame.getSize());
            previewFrame.setVisible(false);
            p2.x = 0;
            p2.y = 0;

            try {
                Robot r = new Robot();
                final BufferedImage bimg = r.createScreenCapture(fetchR);

                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[] { DataFlavor.imageFlavor };
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return DataFlavor.imageFlavor.equals(flavor);
                    }

                    @Override
                    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                        return bimg;
                    }
                }, new ClipboardOwner() {
                    @Override
                    public void lostOwnership(Clipboard clipboard, Transferable contents) {

                    }
                });

                controlButton.setText(String.format("Copied!"));
            } catch (AWTException e) {
                controlButton.setText(String.format("Not Copied!"));
            }

        }
    }

    private ActionListener timerListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            if(!(fetchTL || fetchBR)) return;

            PointerInfo pi = MouseInfo.getPointerInfo();
            Point p = pi.getLocation();

            Point px = fetchTL ? p1 : p2;

            px.setLocation(p.x, p.y);

            updatePreview();
        }
    };

    private void updatePreview() {
        previewFrame.setBounds(
                Math.min(p1.x, p2.x), Math.min(p1.y, p2.y),
                Math.max(Math.abs(p1.x - p2.x), 1), Math.max(Math.abs(p1.y - p2.y), 1)
        );
        if(!previewFrame.isVisible()) {
            previewFrame.setVisible(true);
            controlButton.requestFocus();
        }
    }

    private static float parseFloat(String s, float defaultValue) {
        NumberFormat usFormat = NumberFormat.getCurrencyInstance(Locale.US);
        try {
            return usFormat.parse(s).floatValue();
        } catch (ParseException e) {
            System.err.println("Invalid float: " + s);
            return defaultValue;
        }
    }

    private void prepareInfoDialog() {
        if(infoDialog == null) {
            JEditorPane i = new JEditorPane("text/html", info);
            i.setEditable(false);
            i.setPreferredSize(new Dimension(480, 400));
            i.setCaretPosition(0);

            JScrollPane jsp = new JScrollPane(i);
            jsp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

            infoDialog = new JDialog();
            infoDialog.setContentPane(jsp);
            infoDialog.pack();
            infoDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);

            // magic constant - you can find these by debugging the call inside the JRE...
            Icon infoIcon = UIManager.getIcon("OptionPane.informationIcon");
            Image img = null;

            // this should always be the case but icon could be replaced
            if(infoIcon instanceof ImageIcon) {
                img = ((ImageIcon) infoIcon).getImage();
            } else {
                if(infoIcon != null) {
                    BufferedImage bimg = new BufferedImage(infoIcon.getIconWidth(), infoIcon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                    infoIcon.paintIcon(new JLabel(), bimg.createGraphics(), 0, 0);
                    img = bimg;
                }
            }
            if(img != null) {
                infoDialog.setIconImage(img);
            }

            KeyListener closeListener = new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        infoDialog.setVisible(false);
                    }
                }
            };

            i.addKeyListener(closeListener);
            infoDialog.addKeyListener(closeListener);
        }

        infoDialog.setVisible(true);
    }

    private static final String info =
            "<html><h1>QuickCap</h1>" +
            "<em>A tool for quick screen selection captures</em><br><br>" +
            "<b>Usage:</b><br>" +
            "Keep the application focused and press <em>Space</em> to trigger the control button. " +
            "This will activate the preview area and you can move the mouse to define one of its corners. " +
            "Press <em>Space</em> again to switch to the other corner. " +
            "A third press will fix the area size. If you trigger the button again the process starts from the " +
            "beginning.<br>" +
            "At every moment you can press the <em>Enter</em> key to capture the current selection.<br>" +
            "The most efficient way to capture is to position the mouse at the top-left of the desired area and " +
            "to press <em>Space</em>. Move the mouse to the bottom-right position and press <em>Enter</em>.<br>" +
            "<br>" +
            "The area is copied to the clipboard.<br>" +
            "You can also <em>left click</em> on the area to copy or <em>right click</em> to cancel.<br><br>" +
            "Press <em>Escape</em> to cancel the selection process. Press <em>Escape</em> to quit the application. " +
            "Press <em>Escape</em> to close this information...<br>" +
            "<br>" +
            "<b>Configuration properties and defaults:</b><br>" +
            "QuickCap.color=#0000FF (color of selection area)<br>" +
            "QuickCap.border=#000000 (border color for selection area)<br>" +
            "QuickCap.opacity=0.3 (opacity of selection area, 1.0 is fully opaque)<br>" +
            "<br>" +
            "<em>Set with '<b>java -Dprop=value</b>'.</em>" +
            "</html>";

    // iconmonstr.com copy-10 240px white
    private static final String iconB64 =
            "iVBORw0KGgoAAAANSUhEUgAAAPAAAADwCAYAAAA+VemSAAAACXBIWXMAAA7EAAAO\n" +
            "xAGVKw4bAAADGUlEQVR42u3cwW2FQAxAwWxE/y07HeTwswJedqYAZBBPvnnNzHzB\n" +
            "P7HWWk/PcKfvpwcAPidgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxh\n" +
            "AoYwAUOYgCFMwBAmYAgTMIRdux942k0i/sZNtr+xgSFMwBAmYAgTMIQJGMIEDGEC\n" +
            "hjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYw\n" +
            "AUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGsOvp\n" +
            "Ae42M/P0DL9Za62T3vft3v79bGAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJ\n" +
            "GMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwo67ibXb7htWp833dm+/UWYDQ5iAIUzA\n" +
            "ECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBC2\n" +
            "dt/ocYMJ7mMDQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjC\n" +
            "BAxhAoYwAUOYgCFMwBB2PT3A3dwA40m7/z8bGMIEDGEChjABQ5iAIUzAECZgCBMw\n" +
            "hAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJ\n" +
            "GMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIu3Y/cGbm\n" +
            "6ZeCU9jAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOY\n" +
            "gCFMwBAmYAgTMAAAAAAAAAAAAAAAAAAAAAAAAAAfW08PcLeZmZ3PW2sd9w353O7/\n" +
            "z1VKCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAm\n" +
            "YAgTMIQJGMKOu+e0+ybRbrtvbL39fXc77fvZwBAmYAgTMIQJGMIEDGEChjABQ5iA\n" +
            "IUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCE/QANQj2vveOjowAAAABJ\n" +
            "RU5ErkJggg==";
}
