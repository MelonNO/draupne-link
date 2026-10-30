package de.droiddrone.pc.gui;

import de.droiddrone.pc.CalibrationWizard;
import de.droiddrone.pc.CalibrationWizard.Direction;
import de.droiddrone.pc.CalibrationWizard.Step;
import de.droiddrone.pc.CalibrationWizard.StepKind;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * Full-screen-style controller calibration in the spirit of FPV sim setup screens: one big instruction per step,
 * live visuals, automatic detection when the pilot moves the right control.
 * Stick illustrations assume Mode 2 (throttle/yaw left, pitch/roll right).
 */
class CalibrationDialog extends JDialog {
    private static final int SUMMARY_CHANNELS = PcConfig.THROTTLE_INDEX + 1 + CalibrationWizard.SWITCH_STEPS;

    private final JoystickService service;
    private final int joystickId;
    private final CalibrationWizard wizard;
    private Map<String, String> result;
    private int shownStep = -1;

    private final StepProgress progress = new StepProgress();
    private final JLabel stepCounter = Theme.label("", Theme.headingFont(12f), Theme.MUTED);
    private final JLabel title = Theme.label("", Theme.headingFont(34f), Theme.TEXT);
    private final JLabel hint = Theme.label("", Theme.font(Font.PLAIN, 16f), Theme.MUTED);
    private final JLabel status = Theme.label(" ", Theme.font(Font.BOLD, 14f), Theme.MUTED);
    private final GimbalView leftGimbal = new GimbalView("Throttle / Yaw", "YAW", "THR");
    private final GimbalView rightGimbal = new GimbalView("Pitch / Roll", "ROLL", "PIT");
    private final JPanel gimbalRow = new JPanel(new GridLayout(1, 2, 40, 0));
    private final RawAxesView rawAxes = new RawAxesView();
    private final ChannelBars channelBars = new ChannelBars();
    private final JCheckBox[] invertBoxes = new JCheckBox[SUMMARY_CHANNELS];
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JButton backButton = Theme.secondaryButton("Back");
    private final JButton skipButton = Theme.secondaryButton("Skip");
    private final JButton nextButton = Theme.primaryButton("Next");
    private final Timer timer;

    /** @return settings to save, or null if the pilot cancelled */
    static Map<String, String> run(Window owner, JoystickService service, Joystick.Info joystick, Joystick.State state) {
        CalibrationDialog dialog = new CalibrationDialog(owner, service, joystick, state);
        dialog.setVisible(true); // modal: returns when closed
        return dialog.result;
    }

    private CalibrationDialog(Window owner, JoystickService service, Joystick.Info joystick, Joystick.State state) {
        super(owner, "Controller calibration", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.joystickId = joystick.id();
        this.wizard = new CalibrationWizard(state.axes().length, state.buttons().length);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));
        setContentPane(root);

        JPanel header = new JPanel(new BorderLayout(0, 10));
        JPanel headerTop = new JPanel(new BorderLayout());
        headerTop.add(Theme.label("CONTROLLER CALIBRATION", Theme.headingFont(14f), Theme.ACCENT), BorderLayout.WEST);
        headerTop.add(Theme.label(joystick.name(), Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.EAST);
        header.add(headerTop, BorderLayout.NORTH);
        header.add(progress, BorderLayout.CENTER);
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(Box.createVerticalStrut(8));
        titles.add(stepCounter);
        titles.add(Box.createVerticalStrut(4));
        titles.add(title);
        titles.add(Box.createVerticalStrut(6));
        titles.add(hint);
        header.add(titles, BorderLayout.SOUTH);
        Theme.transparent(header, headerTop, titles);
        root.add(header, BorderLayout.NORTH);

        Dimension gimbalSize = new Dimension(170, 212);
        leftGimbal.setPreferredSize(gimbalSize);
        rightGimbal.setPreferredSize(gimbalSize);
        gimbalRow.add(leftGimbal);
        gimbalRow.add(rightGimbal);
        gimbalRow.setBorder(BorderFactory.createEmptyBorder(0, 120, 12, 120));
        JPanel summary = new JPanel(new BorderLayout(0, 14));
        summary.add(channelBars, BorderLayout.CENTER);
        JPanel invertRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        invertRow.add(Theme.label("INVERT", Theme.headingFont(11.5f), Theme.MUTED));
        for (int i = 0; i < SUMMARY_CHANNELS; i++) {
            final int channel = i;
            JCheckBox box = new JCheckBox(PcConfig.CHANNEL_NAMES[i]);
            box.setForeground(Theme.TEXT);
            box.addActionListener(e -> wizard.setInverted(channel, box.isSelected()));
            invertBoxes[i] = box;
            invertRow.add(box);
        }
        summary.add(invertRow, BorderLayout.SOUTH);
        cardPanel.add(rawAxes, "raw");
        cardPanel.add(summary, "summary");
        JPanel center = new JPanel(new BorderLayout());
        center.add(gimbalRow, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(new WidthTrackingPanel(cardPanel));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        center.add(scroll, BorderLayout.CENTER);
        Theme.transparent(gimbalRow, summary, invertRow, cardPanel, center, scroll);
        JPanel centerCard = Theme.card(new BorderLayout());
        centerCard.add(center, BorderLayout.CENTER);
        root.add(centerCard, BorderLayout.CENTER);

        JButton cancelButton = Theme.secondaryButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        backButton.addActionListener(e -> wizard.back());
        skipButton.addActionListener(e -> wizard.skip());
        nextButton.addActionListener(e -> onNext());
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(cancelButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.add(backButton);
        right.add(skipButton);
        right.add(nextButton);
        footer.add(left, BorderLayout.WEST);
        footer.add(status, BorderLayout.CENTER);
        footer.add(right, BorderLayout.EAST);
        Theme.transparent(footer, left, right);
        root.add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(nextButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
                javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        timer = new Timer(16, e -> tick());
        timer.start();
        setMinimumSize(new Dimension(900, 700));
        int screenHeight = getGraphicsConfiguration().getBounds().height;
        setSize(1000, Math.max(700, Math.min(920, screenHeight - 80)));
        setLocationRelativeTo(owner);
        ScanReveal.onOpen(this);
    }

    @Override
    public void dispose() {
        timer.stop();
        super.dispose();
    }

    private void onNext() {
        if (wizard.getStep().kind() == StepKind.SUMMARY) {
            result = wizard.result();
            dispose();
        } else {
            wizard.next();
        }
    }

    private void tick() {
        JoystickService.Snapshot snap = service.getSnapshot();
        Joystick.State state = snap.selected() != null && snap.selected().id() == joystickId ? snap.state() : null;
        long now = System.currentTimeMillis();
        wizard.update(state, now);
        Step step = wizard.getStep();
        if (wizard.getStepIndex() != shownStep) showStep(step);

        if (state == null) {
            setStatus("Controller disconnected - plug it back in to continue.", Theme.BAD);
        } else {
            updateStatus(step, now);
            updateVisuals(step, state, now);
        }
        backButton.setEnabled(wizard.getStepIndex() > 0);
        nextButton.setEnabled(wizard.canNext());
    }

    private void showStep(Step step) {
        shownStep = wizard.getStepIndex();
        progress.setProgress(shownStep, wizard.getStepCount());
        stepCounter.setText("STEP " + (shownStep + 1) + " OF " + wizard.getStepCount());
        title.setText(step.title().toUpperCase());
        hint.setText("<html>" + step.hint() + "</html>");
        boolean gimbals = step.kind() == StepKind.ASSIGN || step.kind() == StepKind.SUMMARY;
        gimbalRow.setVisible(gimbals);
        cards.show(cardPanel, step.kind() == StepKind.SUMMARY ? "summary" : "raw");
        skipButton.setVisible(wizard.canSkip());
        nextButton.setVisible(step.kind() == StepKind.RANGE || step.kind() == StepKind.CENTER || step.kind() == StepKind.SUMMARY);
        nextButton.setText(step.kind() == StepKind.SUMMARY ? "FINISH" : "NEXT");

        Direction leftArrow = Direction.NONE;
        Direction rightArrow = Direction.NONE;
        if (step.kind() == StepKind.ASSIGN) {
            boolean leftStick = step.channel() == 2 || step.channel() == PcConfig.THROTTLE_INDEX;
            if (leftStick) leftArrow = step.direction(); else rightArrow = step.direction();
        }
        leftGimbal.setArrow(leftArrow);
        rightGimbal.setArrow(rightArrow);
        leftGimbal.setValues(null, null);
        rightGimbal.setValues(null, null);
        if (step.kind() == StepKind.SUMMARY) {
            for (int i = 0; i < SUMMARY_CHANNELS; i++) {
                invertBoxes[i].setEnabled(wizard.getSpec(i) != null);
                invertBoxes[i].setSelected(wizard.isInverted(i));
            }
        }
        revalidate();
        repaint();
    }

    private void updateStatus(Step step, long now) {
        switch (step.kind()) {
            case RANGE -> {
                int swept = wizard.getFullySweptAxisCount();
                setStatus("Full range reached on " + swept + " of " + CalibrationWizard.STICK_AXES + " stick axes",
                        swept >= CalibrationWizard.STICK_AXES ? Theme.OK : Theme.MUTED);
            }
            case CENTER -> setStatus(wizard.isCenterReady() ? "Sticks at rest - press Next."
                    : "Hands off the sticks, throttle down - waiting for them to rest...", wizard.isCenterReady() ? Theme.OK : Theme.MUTED);
            case ASSIGN, SWITCH -> {
                String candidate = wizard.getCandidate();
                if (candidate == null) {
                    setStatus("Waiting for movement...", Theme.MUTED);
                } else {
                    int pct = Math.round(wizard.getCandidateProgress(now) * 100);
                    String what = candidate.replace("-", "").replace("axis", "axis ").replace("button", "button ");
                    setStatus("Detected " + what + (candidate.startsWith("-") ? " (inverted)" : "") + " - hold... " + pct + "%", Theme.ACCENT);
                }
            }
            case SUMMARY -> {
                java.util.List<String> off = wizard.offCenterSticks();
                if (off.isEmpty()) {
                    setStatus("Check every channel, then press Finish to save.", Theme.OK);
                } else {
                    // a centre off the middle is a constant stick input at rest: the drone leans when landed and armed
                    setStatus("<html>Off centre at rest: " + String.join(", ", off) + ". Was a stick touched, or a trim off? "
                            + "Close and calibrate again, hands off at the centre step.</html>", Theme.WARN);
                }
            }
        }
    }

    private void updateVisuals(Step step, Joystick.State state, long now) {
        if (step.kind() == StepKind.SUMMARY) {
            RcMapper preview = wizard.buildMapper();
            short[] ch = preview.map(state.axes(), state.buttons());
            channelBars.setValues(ch, SUMMARY_CHANNELS);
            if (ch != null) {
                leftGimbal.setValues((int) ch[2], (int) ch[PcConfig.THROTTLE_INDEX]);
                rightGimbal.setValues((int) ch[0], (int) ch[1]);
            }
            return;
        }
        int axisCount = state.axes().length;
        int buttonCount = state.buttons().length;
        String[] axisRoles = new String[axisCount];
        for (int i = 0; i < axisCount; i++) axisRoles[i] = wizard.getAxisRole(i);
        String[] buttonRoles = new String[buttonCount];
        for (int i = 0; i < buttonCount; i++) buttonRoles[i] = wizard.getButtonRole(i);
        int highlightAxis = -1;
        int highlightButton = -1;
        String candidate = wizard.getCandidate();
        if (candidate != null) {
            String plain = candidate.replace("-", "");
            int n = Integer.parseInt(plain.replaceAll("\\D", ""));
            if (plain.startsWith("axis")) highlightAxis = n; else highlightButton = n;
        }
        rawAxes.setData(new RawAxesView.Data(state.axes(), state.buttons(), wizard.getMin(), wizard.getMax(),
                wizard.getCenter(), axisRoles, buttonRoles, highlightAxis, highlightButton,
                wizard.getCandidateProgress(now), step.kind() == StepKind.RANGE || step.kind() == StepKind.CENTER));
    }

    private void setStatus(String text, java.awt.Color color) {
        status.setText(text);
        status.setForeground(color);
    }
}
