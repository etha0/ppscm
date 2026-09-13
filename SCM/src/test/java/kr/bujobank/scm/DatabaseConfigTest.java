package kr.bujobank.scm;

import org.junit.Test;
import java.util.Properties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DatabaseConfigTest {
    private Properties resource(String name) throws IOException {
        Properties values = new Properties();
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/" + name), StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return values;
    }
    @Test public void loadsBuildProfileAndCommonSettings() throws IOException {
        String profile = resource("scm-build.properties").getProperty("scm.profile");
        assertTrue("dev".equals(profile) || "prod".equals(profile));
        Properties expected = resource("application.properties");
        expected.putAll(resource("application-" + profile + ".properties"));
        assertEquals(expected, Database.loadProfile());
    }
}