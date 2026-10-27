package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RcMapperTest {

    @Test
    void axisRangeMapsTo1000to2000() {
        assertEquals(1000, RcMapper.axisToChannel(-1f));
        assertEquals(1500, RcMapper.axisToChannel(0f));
        assertEquals(2000, RcMapper.axisToChannel(1f));
        assertEquals(2000, RcMapper.axisToChannel(1.2f), "clamped");
    }

    @Test
    void defaultMappingReordersRadioAetrToDroidDroneAetr() throws Exception {
        RcMapper mapper = new RcMapper(PcConfig.defaults().getChannelSpecs());
        // radio CH1..CH8 on axis0..7; CH3 is throttle on the radio, CH4 rudder
        float[] axes = {0.1f, 0.2f, -1f, 0.4f, 1f, -1f, 0f, 0f};
        short[] ch = mapper.map(axes, new boolean[0]);
        assertArrayEquals(new short[]{1550, 1600, 1700, 1000, 2000, 1000, 1500, 1500}, ch);
    }

    @Test
    void buttonsInversionAndFixedValues() {
        RcMapper mapper = new RcMapper(new String[]{"axis0", "-axis1", "axis2", "axis3", "button0", "-button0", "1800"});
        short[] ch = mapper.map(new float[]{0, 1, 0, -1}, new boolean[]{true});
        assertArrayEquals(new short[]{1500, 1000, 1500, 1000, 2000, 1000, 1800}, ch);
    }

    @Test
    void gapsUseUpstreamIdleValues() {
        RcMapper mapper = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3", null, "button0"});
        short[] ch = mapper.map(new float[]{0, 0, 0, 0}, new boolean[]{false});
        assertEquals(1000, ch[4]);
        assertEquals(6, ch.length);
    }

    @Test
    void missingInputFailsClosed() {
        RcMapper mapper = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis7"});
        assertNull(mapper.map(new float[]{0, 0, 0, 0}, new boolean[0]));
    }

    @Test
    void calibrationMapsMeasuredRange() {
        AxisCalibration cal = new AxisCalibration(-0.8f, 0.1f, 0.9f);
        assertEquals(1000, cal.toCentered(-0.8f));
        assertEquals(1500, cal.toCentered(0.1f));
        assertEquals(2000, cal.toCentered(0.9f));
        assertEquals(1250, cal.toCentered(-0.35f), "halfway down the shorter side");
        assertEquals(1000, cal.toLinear(-0.8f));
        assertEquals(2000, cal.toLinear(0.9f));
        assertEquals(2000, cal.toLinear(1.0f), "clamped");

        RcMapper mapper = new RcMapper(new String[]{"axis0", "-axis1", "axis2", "axis3"},
                java.util.Map.of(1, cal, 3, new AxisCalibration(-0.9f, -0.9f, 0.9f)));
        short[] ch = mapper.map(new float[]{0f, 0.9f, 0f, -0.9f}, new boolean[0]);
        assertEquals(1000, ch[1], "inverted calibrated stick");
        assertEquals(1000, ch[3], "throttle maps linearly from its measured bottom");
    }

    @Test
    void sticksMustBeMapped() {
        assertThrows(IllegalArgumentException.class, () -> new RcMapper(new String[]{"axis0", "axis1", null, "axis3"}));
        assertThrows(IllegalArgumentException.class, () -> new RcMapper(new String[]{"axis0", "axis1", "axis2"}));
        assertThrows(IllegalArgumentException.class, () -> RcMapper.Source.parse("knob3"));
        assertThrows(IllegalArgumentException.class, () -> RcMapper.Source.parse("2500"));
    }
}
