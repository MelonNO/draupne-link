package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Step-by-step dialog shared by the setup wizards, in the calibration wizard's style. */
class WizardDialog extends JDialog {
    /** Background work for pages (shell commands, downloads); results come back on the Swing thread. */
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wizardWorker");
        t.setDaemon(true);
        return t;
    });

    abstract static class Page {
        WizardDialog wizard;
        /** Shown next to the step number, e.g. "PHONE". */
        String section = "";
        private JComponent view;

        Page in(String sectionName) {
            section = sectionName;
            return this;
        }

        abstract String title();

        abstract String hint();

        /**
         * The page's rows. Called the first time the page is shown (F-084), not when the wizard opens: a page that
         * must start something as the wizard opens does it in {@link #prepare()}.
         */
        abstract JComponent build();

        /**
         * Skipped pages: Tailscale when another connection method was chosen, and pages whose checks found nothing
         * left to do (see {@link #prepare()}).
         */
        boolean isApplicable() {
            return true;
        }

        /**
         * Called once when the wizard opens: start the page's checks, and fixes that need nobody, in the background.
         * A page that finds everything done can then report itself not applicable and is never shown.
         */
        void prepare() {
        }

        void onShow() {
        }

        void onHide() {
        }

        /** Called twice a second while the page is visible. */
        void refresh() {
        }

        boolean canContinue() {
            return true;
        }

        /** Footer text: what is missing, or that all is well. null = nothing to say. */
        String status() {
            return null;
        }

        /** A background job of this page failed: shown in the footer instead of status() until the next one succeeds. */
        volatile String error;

        final JComponent view() {
            if (view == null) view = build();
            return view;
        }

        /** The page has been built (shown once, or its view asked for). */
        final boolean isBuilt() {
            return view != null;
        }

        /** To the app log (the main window's), when the wizard has one. */
        void log(String line) {
            if (wizard != null) wizard.log(line);
        }

        <T> void background(Supplier<T> work, Consumer<T> done) {
            WizardDialog w = wizard;
            if (w != null) w.jobStarted();
            WORKERS.submit(() -> {
                T result;
                String failure = null;
                try {
                    result = work.get();
                } catch (RuntimeException e) {
                    result = null;
                    failure = e.toString();
                    log("Setup guide, " + title() + ": " + e);
                    e.printStackTrace(); // the app log keeps System.err
                }
                T r = result;
                String f = failure;
                SwingUtilities.invokeLater(() -> {
                    error = f == null ? null : "Something went wrong: " + f;
                    try {
                        done.accept(r);
                    } finally {
                        // F-073: after the result is in - counted before it, the last check's page still counted as
                        // shown ("STEP 1 OF 9", then "STEP 2 OF 8")
                        if (w != null) w.jobDone();
                    }
                });
            });
        }
    }

    private final List<Page> pages;
    private final Runnable onFinish;
    private int index = -1;
    private final StepProgress progress = new StepProgress();
    private final JLabel stepCounter = Theme.label("", Theme.headingFont(12f), Theme.MUTED);
    private final JLabel title = Theme.label("", Theme.headingFont(30f), Theme.TEXT);
    /** Wraps to the window's width (a long hint in a JLabel was cut off at the right edge). */
    private final WrappingText hint = new WrappingText("", Theme.font(Font.PLAIN, 17f), Theme.MUTED) {
        @Override
        public Dimension getPreferredSize() {
            int width = 0;
            for (java.awt.Container c = getParent(); c != null && width <= 0; c = c.getParent()) {
                width = c.getWidth() - c.getInsets().left - c.getInsets().right;
            }
            if (width <= 0) return super.getPreferredSize();
            javax.swing.text.View v = getUI().getRootView(this);
            v.setSize(width, 0);
            return new Dimension(width, (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)));
        }
    };
    private final JLabel status = Theme.label(" ", Theme.font(Font.BOLD, 13.5f), Theme.MUTED);
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    /** Which pages have their card in cardPanel: made the first time each is shown (F-084). */
    private final boolean[] carded;
    private final JButton backButton = Theme.secondaryButton("Back");
    private final JButton nextButton = Theme.primaryButton("Next");
    private final Timer timer;
    /**
     * The step count waits for the pages' prepare() checks (they decide which steps are skipped), at most a second:
     * shown at once it jumped ("OF 13", then "OF 9") as each result came in.
     */
    private boolean countShown;
    private boolean preparing;
    private int jobsPending;
    private final Timer countTimer = new Timer(1000, e -> showCount());

    WizardDialog(Window owner, String heading, List<Page> pages, Runnable onFinish) {
        super(owner, heading, ModalityType.APPLICATION_MODAL);
        this.pages = pages;
        this.onFinish = onFinish;
        carded = new boolean[pages.size()];
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // the title bar's X: closeAs, so the log says why

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));
        setContentPane(root);

        JPanel header = new JPanel(new BorderLayout(0, 10));
        header.add(Theme.label(heading.toUpperCase(), Theme.headingFont(14f), Theme.ACCENT), BorderLayout.NORTH);
        header.add(progress, BorderLayout.CENTER);
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{stepCounter, title, hint}) c.setAlignmentX(LEFT_ALIGNMENT);
        titles.add(Box.createVerticalStrut(8));
        titles.add(stepCounter);
        titles.add(Box.createVerticalStrut(4));
        titles.add(title);
        titles.add(Box.createVerticalStrut(6));
        titles.add(hint);
        header.add(titles, BorderLayout.SOUTH);
        Theme.transparent(header, titles);
        root.add(header, BorderLayout.NORTH);

        for (Page page : pages) page.wizard = this; // each page's card is made when it is first shown (showCard)
        cardPanel.setOpaque(false);
        JPanel centerCard = Theme.card(new BorderLayout());
        centerCard.add(cardPanel, BorderLayout.CENTER);
        root.add(centerCard, BorderLayout.CENTER);

        JButton closeButton = Theme.secondaryButton("Close");
        closeButton.addActionListener(e -> closeAs("CLOSE"));
        backButton.addActionListener(e -> go(-1));
        nextButton.addActionListener(e -> {
            if (isLastPage()) {
                finish();
            } else {
                go(+1);
            }
        });
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(closeButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.add(backButton);
        right.add(nextButton);
        footer.add(left, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        footer.add(right, BorderLayout.EAST);
        Theme.transparent(footer, left, right);
        root.add(footer, BorderLayout.SOUTH);
        // Esc asks first (1.0.52): the guide closing "by itself" must never be a stray key
        getRootPane().registerKeyboardAction(e -> askClose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAs("the window's X");
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (index >= 0) pages.get(index).onHide();
            }
        });

        timer = new Timer(500, e -> tick());
        ScreenFit.apply(this, 760, 560, 1020, 880);
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
        preparing = true;
        for (Page page : pages) page.prepare();
        preparing = false;
        countTimer.setRepeats(false);
        if (jobsPending == 0) showCount(); else countTimer.start();
        go(+1);
        timer.start();
    }

    /** Why the guide is closing (CLOSE, Esc, the window's X, finished); null: nothing of the pilot's asked for it. */
    private String closing;

    void closeAs(String why) {
        closing = why;
        dispose();
    }

    private void askClose() {
        int answer = Dialogs.confirm(this, "Close the setup? What's done stays done.", "Close setup",
                javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (answer == javax.swing.JOptionPane.YES_OPTION) closeAs("Esc");
    }

    @Override
    public void dispose() {
        if (isDisplayable()) {
            // 1.0.52 (the pilot: "I plugged in the drone and the setup window closed by itself", nothing in the log):
            // every close says why; one nobody asked for says from where
            if (closing == null) log(getTitle() + " closed - not by the pilot: " + caller() + ".");
            else if (!"finished".equals(closing)) log(getTitle() + " closed (" + closing + ").");
        }
        timer.stop();
        countTimer.stop();
        super.dispose();
    }

    /** The first frames outside this class and Swing/AWT that asked for dispose(): where a close came from. */
    static String caller() {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            String c = f.getClassName();
            if (c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("sun.") || c.startsWith("jdk.")
                    || c.equals(WizardDialog.class.getName())) continue;
            if (sb.length() > 0) sb.append(" < ");
            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
            if (sb.length() > 200) break;
        }
        return sb.length() == 0 ? "Swing" : sb.toString();
    }

    /** To the app log, when the wizard belongs to the main window. */
    void log(String line) {
        if (getOwner() instanceof MainWindow m) m.logLine(line);
        else System.out.println(line);
    }

    private void jobStarted() {
        if (preparing) jobsPending++;
    }

    private void jobDone() {
        if (jobsPending > 0 && --jobsPending == 0) {
            if (!countShown) showCount();
            else if (index >= 0) showStep(pages.get(index)); // the last check came after the second: now its OF
        }
    }

    private void showCount() {
        countTimer.stop();
        countShown = true;
        if (index >= 0) showStep(pages.get(index));
    }

    /**
     * "STEP 2 OF 8" - and only "STEP 2" while a check that decides which steps are skipped is still out (F-043: a
     * check longer than the second's wait made "STEP 1 OF 9", then "STEP 2 OF 8").
     */
    static String counterText(int shownIndex, int shownTotal, boolean totalKnown, String section) {
        return "STEP " + (shownIndex + 1) + (totalKnown ? " OF " + shownTotal : "") + (section.isEmpty() ? "" : "   ·   " + section);
    }

    /** What the step count says now (for the tests). */
    String stepCounterText() {
        return stepCounter.getText();
    }

    private void showStep(Page page) {
        int shownTotal = (int) pages.stream().filter(Page::isApplicable).count();
        int shownIndex = (int) pages.subList(0, index).stream().filter(Page::isApplicable).count();
        progress.setProgress(shownIndex, shownTotal);
        stepCounter.setText(counterText(shownIndex, shownTotal, jobsPending == 0, page.section));
    }

    /** Re-evaluates navigation now (pages call this after their state changes). */
    void update() {
        tick();
    }

    private void tick() {
        if (index < 0) return;
        Page page = pages.get(index);
        page.refresh();
        backButton.setEnabled(previousApplicable(index) >= 0);
        nextButton.setEnabled(page.canContinue());
        nextButton.setText(isLastPage() ? "FINISH" : "NEXT");
        status.setForeground(page.canContinue() ? Theme.MUTED : Theme.WARN); // first: status() may choose its own colour
        String text = page.error != null ? page.error : page.status();
        status.setText(text == null ? " " : text);
        if (page.error != null) status.setForeground(Theme.BAD);
    }

    void setStatusColor(Color color) {
        status.setForeground(color);
    }

    private boolean isLastPage() {
        return nextApplicable(index) < 0;
    }

    private int nextApplicable(int from) {
        for (int i = from + 1; i < pages.size(); i++) if (pages.get(i).isApplicable()) return i;
        return -1;
    }

    private int previousApplicable(int from) {
        for (int i = from - 1; i >= 0; i--) if (pages.get(i).isApplicable()) return i;
        return -1;
    }

    private void go(int direction) {
        int target = direction > 0 ? nextApplicable(index) : previousApplicable(index);
        if (target < 0) return;
        if (index >= 0) pages.get(index).onHide();
        index = target;
        Page page = pages.get(index);
        if (countShown) showStep(page);
        else stepCounter.setText(page.section.isEmpty() ? " " : page.section);
        title.setText(page.title().toUpperCase());
        hint.setHtml(page.hint());
        showCard(index);
        WindowReveal.fadeIn(cardPanel); // Next and Back: the page fades in
        page.onShow();
        tick();
    }

    /**
     * F-084 (the bench, 2026-11-23): SETUP &gt; Set up a new drone held the window 0.46 to 0.57 s as it opened - all
     * seven pages were built and laid out at once, the 3D drone's model read with them. A page is now built the first
     * time it is shown, and only its own card is laid out.
     */
    private void showCard(int i) {
        String name = Integer.toString(i);
        if (!carded[i]) {
            carded[i] = true;
            JScrollPane scroll = new JScrollPane(new WidthTrackingPanel(pages.get(i).view()));
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getViewport().setOpaque(false);
            scroll.setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            cardPanel.add(scroll, name);
        }
        cards.show(cardPanel, name);
    }

    private void finish() {
        if (onFinish != null) onFinish.run();
        closeAs("finished");
    }

    // ------------------------------------------------------------------ page building helpers

    static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    /** Adds a full-width row that keeps its preferred height (BoxLayout would otherwise stretch it). */
    static void addRow(JPanel column, JComponent row) {
        JPanel holder = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        holder.setOpaque(false);
        holder.setAlignmentX(LEFT_ALIGNMENT);
        holder.add(row, BorderLayout.CENTER);
        column.add(holder);
        column.add(Box.createVerticalStrut(10));
    }

    static WrappingText paragraph(String html) {
        return WrappingText.fitting(html, Theme.font(Font.PLAIN, 16f), Theme.TEXT); // wraps to the width it gets (1.0.52: cut off on a small screen)
    }

    static JLabel subheading(String text) {
        return Theme.sectionTitle(text);
    }
}
