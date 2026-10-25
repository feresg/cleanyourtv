package io.github.feresg.hue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Against the shapes the Hue Bridge's API v1 documents for groups, scenes, pairing and errors. */
public class HueTest {
    static final String GROUPS = "{"
            + "\"1\":{\"name\":\"Living room\",\"lights\":[\"1\",\"2\"],\"type\":\"Room\",\"class\":\"Living room\","
            + "\"state\":{\"all_on\":false,\"any_on\":true},\"action\":{\"on\":true,\"bri\":127,\"alert\":\"none\"}},"
            + "\"2\":{\"name\":\"bedroom\",\"lights\":[\"3\"],\"type\":\"Room\",\"state\":{\"all_on\":false,\"any_on\":false},"
            + "\"action\":{\"on\":false,\"bri\":254}},"
            + "\"3\":{\"name\":\"Downstairs\",\"lights\":[\"1\",\"2\",\"4\"],\"type\":\"Zone\",\"state\":{\"all_on\":true,\"any_on\":true},"
            + "\"action\":{\"on\":true,\"bri\":40}},"
            + "\"4\":{\"name\":\"TV area\",\"lights\":[\"1\"],\"type\":\"Entertainment\",\"state\":{\"all_on\":false,\"any_on\":false}},"
            + "\"5\":{\"name\":\"Empty room\",\"lights\":[],\"type\":\"Room\",\"state\":{\"all_on\":false,\"any_on\":false}}"
            + "}";

    static final String SCENES = "{"
            + "\"aBc\":{\"name\":\"Relax\",\"type\":\"GroupScene\",\"group\":\"1\",\"lights\":[\"1\",\"2\"],\"recycle\":false},"
            + "\"dEf\":{\"name\":\"Bright\",\"type\":\"GroupScene\",\"group\":\"2\",\"lights\":[\"3\"],\"recycle\":false},"
            + "\"gHi\":{\"name\":\"Movie\",\"type\":\"LightScene\",\"lights\":[\"1\"],\"recycle\":false},"
            + "\"jKl\":{\"name\":\"Everywhere\",\"type\":\"LightScene\",\"lights\":[\"1\",\"3\"],\"recycle\":false},"
            + "\"mNo\":{\"name\":\"Temp\",\"type\":\"GroupScene\",\"group\":\"1\",\"lights\":[\"1\"],\"recycle\":true}"
            + "}";

    @Test
    public void roomsThenZonesByNameWithoutEmptyOrEntertainmentGroups() throws Exception {
        List<Hue.Group> g = Hue.groups(GROUPS);
        assertEquals(3, g.size());
        assertEquals("bedroom", g.get(0).name);
        assertEquals("Living room", g.get(1).name);
        assertEquals("Downstairs", g.get(2).name);
        Hue.Group living = g.get(1);
        assertTrue(living.anyOn);
        assertFalse(living.allOn);
        assertEquals(127, living.bri);
        assertEquals(50, living.percent());
    }

    @Test
    public void scenesForARoomIncludeLightScenesInsideItOnly() throws Exception {
        Hue.Group living = Hue.groups(GROUPS).get(1);
        List<Hue.Scene> s = Hue.scenes(SCENES, living);
        assertEquals(2, s.size());
        assertEquals("Movie", s.get(0).name);
        assertEquals("Relax", s.get(1).name);
    }

    @Test
    public void pairingWaitsForTheButtonThenTakesTheUsername() throws Exception {
        assertNull(Hue.username("[{\"error\":{\"type\":101,\"address\":\"\",\"description\":\"link button not pressed\"}}]"));
        assertEquals("link button not pressed",
                Hue.error("[{\"error\":{\"type\":101,\"address\":\"\",\"description\":\"link button not pressed\"}}]"));
        assertEquals("83b7780291a6ceffbe0bd049104df", Hue.username("[{\"success\":{\"username\":\"83b7780291a6ceffbe0bd049104df\"}}]"));
        assertEquals("{\"devicetype\":\"cleanyourtv#tv\"}", Hue.pairBody());
    }

    @Test
    public void aDeletedUsernameIsSeen() {
        assertTrue(Hue.unauthorized("[{\"error\":{\"type\":1,\"address\":\"/groups\",\"description\":\"unauthorized user\"}}]"));
        assertFalse(Hue.unauthorized(GROUPS));
        assertNull(Hue.error(GROUPS));
    }

    @Test
    public void bodies() {
        assertEquals("{\"on\":false}", Hue.on(false));
        assertEquals("{\"on\":true,\"bri\":200}", Hue.bri(200));
        assertEquals("{\"on\":false}", Hue.bri(0));
        assertEquals("{\"scene\":\"aBc\"}", Hue.scene("aBc"));
    }

    @Test
    public void dimmingSteps() {
        assertEquals(Hue.BRI_STEP, Hue.step(false, 254, 1));
        assertEquals(0, Hue.step(false, 254, -1));
        assertEquals(254, Hue.step(true, 240, 1));
        assertEquals(1, Hue.step(true, 10, -1));
        assertEquals(0, Hue.step(true, 1, -1));
        assertEquals(152, Hue.step(true, 127, 1));
    }

    @Test
    public void discoveryAnswerAndHosts() throws Exception {
        assertEquals(Arrays.asList("192.168.1.20"), Hue.discovered(
                "[{\"id\":\"001788fffe100491\",\"internalipaddress\":\"192.168.1.20\",\"port\":443},"
                        + "{\"id\":\"x\",\"internalipaddress\":\"192.168.1.20\"},{\"id\":\"y\",\"internalipaddress\":\"bad host/\"}]"));
        assertTrue(Hue.validHost("philips-hue.local"));
        assertFalse(Hue.validHost("http://192.168.1.20"));
        assertFalse(Hue.validHost(""));
    }
}
