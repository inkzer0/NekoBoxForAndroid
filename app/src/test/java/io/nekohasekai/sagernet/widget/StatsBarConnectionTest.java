package io.nekohasekai.sagernet.widget;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.snackbar.Snackbar;
import io.nekohasekai.sagernet.R;
import io.nekohasekai.sagernet.SagerNet;
import io.nekohasekai.sagernet.ui.MainActivity;
import io.nekohasekai.sagernet.ui.ThemedActivity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE,
        instrumentedPackages = {"io.nekohasekai.sagernet.ktx", "io.nekohasekai.sagernet.ui", "io.nekohasekai.sagernet.database"},
        shadows = {StatsBarContextTest.ShadowUtils.class, StatsBarConnectionTest.ActivityBoundary.class,
                StatsBarConnectionTest.SnackbarBoundary.class, StatsBarConnectionTest.LogBoundary.class,
                StatsBarConnectionTest.SettingsBoundary.class})
public class StatsBarConnectionTest {
    private static CountDownLatch entered, release;
    private static boolean fail;
    private static String warning, feedback;
    private static FrameLayout root;
    private StatsBar bar;
    private TextView status;

    @Implements(value = MainActivity.class, isInAndroidSdk = false)
    public static class ActivityBoundary extends SnackbarBoundary {
        @Implementation protected int urlTest() throws Exception {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test never released URLTest");
            if (fail) throw new IOException("controlled URLTest failure");
            return 123;
        }
    }
    @Implements(value = ThemedActivity.class, isInAndroidSdk = false)
    public static class SnackbarBoundary extends org.robolectric.shadows.ShadowActivity {
        @Implementation protected Snackbar snackbar(CharSequence text) {
            feedback = text.toString();
            return Snackbar.make(root, text, Snackbar.LENGTH_LONG);
        }
    }
    @Implements(value = io.nekohasekai.sagernet.ktx.Logs.class, isInAndroidSdk = false)
    public static class LogBoundary {
        @Implementation protected void w(String text) { warning = text; }
    }
    @Implements(value = io.nekohasekai.sagernet.database.DataStore.class, isInAndroidSdk = false)
    public static class SettingsBoundary {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected String getConnectionTestURL() { return "https://example.com/"; }
    }

    @Before public void setup() {
        warning = feedback = null;
        SagerNet app = new SagerNet();
        ReflectionHelpers.callInstanceMethod(app, "attach", ReflectionHelpers.ClassParameter.from(
                Context.class, RuntimeEnvironment.getApplication()));
        SagerNet.Companion.setApplication(app);
        ReflectionHelpers.setStaticField(io.nekohasekai.sagernet.database.DataStore.class, "INSTANCE",
                ReflectionHelpers.callConstructor(io.nekohasekai.sagernet.database.DataStore.class));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.Theme_SagerNet_Pink_SSR);
        root = new FrameLayout(activity);
        root.setId(android.R.id.content);
        bar = new StatsBar(new ContextThemeWrapper(activity, R.style.Theme_SagerNet_Pink_SSR));
        root.addView(bar);
        for (int id : new int[]{R.id.status, R.id.tx, R.id.rx}) {
            TextView text = new TextView(bar.getContext());
            text.setId(id);
            bar.addView(text);
        }
        bar.setOnClickListener(v -> bar.testConnection());
        status = bar.findViewById(R.id.status);
        bar.updateSpeed(1024, 2048);
    }

    private void query(boolean failure) throws Exception {
        fail = failure;
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        String tx = ((TextView) bar.findViewById(R.id.tx)).getText().toString();
        String rx = ((TextView) bar.findViewById(R.id.rx)).getText().toString();
        assertTrue(bar.isEnabled());
        assertTrue(bar.performClick());
        assertFalse(bar.isEnabled());
        assertEquals(bar.getContext().getString(R.string.connection_test_testing), status.getText().toString());
        try { assertTrue("Production URLTest boundary was reached", entered.await(5, TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!bar.isEnabled() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("Production completion re-enables StatsBar", bar.isEnabled());
        assertEquals(tx, ((TextView) bar.findViewById(R.id.tx)).getText().toString());
        assertEquals(rx, ((TextView) bar.findViewById(R.id.rx)).getText().toString());
        if (failure) {
            assertEquals("java.io.IOException: controlled URLTest failure", warning);
            assertEquals(bar.getContext().getString(R.string.connection_test_error,
                    "controlled URLTest failure"), feedback);
            assertNotEquals("A finished failure must not retain testing",
                    bar.getContext().getString(R.string.connection_test_testing), status.getText().toString());
            assertEquals(bar.getContext().getString(R.string.connection_test_failed), status.getText().toString());
        } else {
            assertEquals(bar.getContext().getString(R.string.connection_test_available, 123), status.getText().toString());
        }
    }

    @Test public void successDisplaysLatency() throws Exception { query(false); }
    @Test public void failureCompletesWithErrorFeedback() throws Exception { query(true); }
    @Test public void failureCanRetrySuccessfully() throws Exception { query(true); query(false); }
    @Test public void consecutiveFailuresRemainRetryable() throws Exception { query(true); query(true); }
}
