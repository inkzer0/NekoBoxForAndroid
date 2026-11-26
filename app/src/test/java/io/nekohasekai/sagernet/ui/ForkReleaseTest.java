package io.nekohasekai.sagernet.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ForkReleaseTest {
    @Test public void stableChannelOnlyOffersStrictlyNewerNumericVersions() {
        ForkRelease release = ForkRelease.INSTANCE;
        assertEquals("https://api.github.com/repos/inkzer0/NekoBoxForAndroid/releases/latest",
                ForkRelease.LATEST_API);
        assertEquals("https://github.com/inkzer0/NekoBoxForAndroid/releases/tag/v1.4.10",
                release.newerReleaseUrl("1.4.9", "v1.4.10"));
        assertEquals("https://github.com/inkzer0/NekoBoxForAndroid/releases/tag/v1.5.0",
                release.newerReleaseUrl("1.4.99", "v1.5.0"));
        for (String tag : new String[]{"v1.4.4", "1.4.4", "v1.4.3", "v1.3.99", "preview",
                "v1.5.0-beta.1", "v01.4.5", "v1.4.5/../", "", "release v1.4.5"}) {
            assertNull(tag, release.newerReleaseUrl("1.4.4", tag));
        }
    }
}
