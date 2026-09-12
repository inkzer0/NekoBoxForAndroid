package io.nekohasekai.sagernet.database;

import android.app.Application;
import androidx.room.Room;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21,28}, application = Application.class, manifest = Config.NONE)
public class LegacyGeoRuleDatabaseTest {
    @Test public void disabledMixedRuleIsExcludedFromConfigBuilderQuery() {
        SagerDatabase db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase.class)
                .allowMainThreadQueries().build();
        try {
            RuleEntity rule = new RuleEntity(); rule.setId(99); rule.setDomains("geosite:github");
            rule.setIp("geoip:us"); rule.setEnabled(false); rule.setOutbound(-2);
            db.rulesDao().createRule(rule);
            assertTrue(db.rulesDao().enabledRules(true).isEmpty());
            assertEquals(1, db.rulesDao().allRules().size());
            rule.setEnabled(true); db.rulesDao().updateRule(rule);
            RuleEntity selected = db.rulesDao().enabledRules(true).get(0);
            assertEquals("geosite:github", selected.getDomains());
            assertEquals("geoip:us", selected.getIp()); assertEquals(-2,selected.getOutbound());
        } finally { db.close(); }
    }
}
