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
                    if (w != null) w.jobDone();
                    done.accept(r);
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
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

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

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            page.wizard = this;
            JScrollPane scroll = new JScrollPane(new WidthTrackingPanel(page.view()));
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getViewport().setOpaque(false);
            scroll.setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            cardPanel.add(scroll, Integer.toString(i));
        }
        cardPanel.setOpaque(false);
        JPanel centerCard = Theme.card(new BorderLayout());
        centerCard.add(cardPanel, BorderLayout.CENTER);
        root.add(centerCard, BorderLayout.CENTER);

        JButton closeButton = Theme.secondaryButton("Close");
        closeButton.addActionListener(e -> dispose());
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
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
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

    @Override
    public void dispose() {
        timer.stop();
        countTimer.stop();
        super.dispose();
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
        if (jobsPending > 0 && --jobsPending == 0 && !countShown) showCount();
    }

    private void showCount() {
        countTimer.stop();
        countShown = true;
        if (index >= 0) showStep(pages.get(index));
    }

    private void showStep(Page page) {
        int shownTotal = (int) pages.stream().filter(Page::isApplicable).count();
        int shownIndex = (int) pages.subList(0, index).stream().filter(Page::isApplicable).count();
        progress.setProgress(shownIndex, shownTotal);
        stepCounter.setText("STEP " + (shownIndex + 1) + " OF " + shownTotal + (page.section.isEmpty() ? "" : "   //   " + page.section));
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
        cards.show(cardPanel, Integer.toString(index));
        WindowReveal.fadeIn(cardPanel); // Next and Back: the page fades in
        page.onShow();
        tick();
    }

    private void finish() {
        if (onFinish != null) onFinish.run();
        dispose();
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
