package io.nekohasekai.sagernet.widget;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import io.nekohasekai.sagernet.R;
import io.nekohasekai.sagernet.SagerNet;
import io.nekohasekai.sagernet.bg.BaseService;
import io.nekohasekai.sagernet.ui.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 28, 35}, application = Application.class, manifest = Config.NONE,
        instrumentedPackages = "io.nekohasekai.sagernet.ktx",
        shadows = StatsBarContextTest.ShadowUtils.class)
public class StatsBarContextTest {
    @Implements(value = io.nekohasekai.sagernet.ktx.UtilsKt.class, isInAndroidSdk = false)
    public static class ShadowUtils {
        // The JVM lacks Android's Socket.getFileDescriptor$/FileDescriptor.getInt$.
        // Suppress only that unrelated static initialization; getApp and callbacks remain real.
        @Implementation protected static void __staticInitializer__() {}
    }
    @Test public void wrappedContextPreservesStateAndLifecycleCallbacks() {
        // Keep native Core/service startup out of this widget contract; real startup is device-tested.
        SagerNet app = new SagerNet();
        ReflectionHelpers.callInstanceMethod(app, "attach",
                ReflectionHelpers.ClassParameter.from(Context.class, RuntimeEnvironment.getApplication()));
        SagerNet.Companion.setApplication(app);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.Theme_SagerNet_Pink_SSR);
        androidx.savedstate.SavedStateRegistryController savedState =
                ReflectionHelpers.getField(activity, "savedStateRegistryController");
        savedState.performRestore(null);
        LifecycleRegistry lifecycle = (LifecycleRegistry) activity.getLifecycle();
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        Context wrapped = new ContextThemeWrapper(
                new ContextThemeWrapper(activity, R.style.Theme_SagerNet_Pink_SSR),
                R.style.Theme_SagerNet_Pink_SSR);
        StatsBar bar = new StatsBar(wrapped);
        assertFalse(bar.getContext() instanceof MainActivity);
        for (int id : new int[]{R.id.status, R.id.tx, R.id.rx}) {
            TextView text = new TextView(bar.getContext());
            text.setId(id);
            bar.addView(text);
        }
        bar.setOnClickListener(v -> {});
        TextView status = bar.findViewById(R.id.status);
        for (BaseService.State state : new BaseService.State[]{BaseService.State.Idle,
                BaseService.State.Connecting, BaseService.State.Stopping}) {
            bar.changeState(state);
            int expected = state == BaseService.State.Connecting ? R.string.connecting :
                    state == BaseService.State.Stopping ? R.string.stopping : R.string.not_connected;
            assertEquals(bar.getContext().getText(expected).toString(), status.getText().toString());
            assertFalse(bar.getHideOnScroll());
            assertTrue(((TextView) bar.findViewById(R.id.tx)).getText().toString().startsWith("▲"));
        }
        bar.changeState(BaseService.State.Connected);
        assertTrue(bar.getHideOnScroll());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        assertEquals(bar.getContext().getText(R.string.stopping).toString(), status.getText().toString());
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        assertEquals(app.getText(R.string.vpn_connected).toString(), status.getText().toString());
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        status.setText("unchanged after destroy");
        bar.changeState(BaseService.State.Connected);
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        assertEquals("unchanged after destroy", status.getText().toString());
    }
}
