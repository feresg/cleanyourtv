package io.github.feresg.vpnstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

/** Against the shape api.nordvpn.com/v1/helpers/ips/insights answers with. */
public class InsightsTest {
    @Test
    public void readsAProtectedConnection() throws Exception {
        Insights i = Insights.parse("{\"ip\":\"185.0.2.10\",\"country\":\"France\",\"country_code\":\"FR\","
                + "\"city\":\"Paris\",\"state_code\":\"IDF\",\"zip_code\":\"75001\",\"longitude\":2.35,\"latitude\":48.85,"
                + "\"isp\":\"Datacamp Limited\",\"isp_asn\":60068,\"gdpr\":true,\"protected\":true}");
        assertEquals("185.0.2.10", i.ip);
        assertEquals("FR", i.countryCode);
        assertEquals("Paris, France", i.place());
        assertEquals("Datacamp Limited", i.isp);
        assertTrue(i.isProtected);
    }

    @Test
    public void missingFieldsAreEmptyAndNotProtected() throws Exception {
        Insights i = Insights.parse("{\"ip\":\"203.0.113.7\",\"country\":\"United Kingdom\",\"city\":null}");
        assertEquals("United Kingdom", i.place());
        assertEquals("", i.isp);
        assertFalse(i.isProtected);
    }

    @Test(expected = JSONException.class)
    public void anAnswerWithoutAnIpIsRefused() throws Exception {
        Insights.parse("{\"message\":\"Too many requests\"}");
    }
}
