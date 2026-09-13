package io.nekohasekai.sagernet.widget;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.graphics.RectF;
import android.view.*;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import io.nekohasekai.sagernet.R;
import io.nekohasekai.sagernet.SagerNet;
import io.nekohasekai.sagernet.bg.BaseService;
import io.nekohasekai.sagernet.ui.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import java.util.ArrayList;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={21,28,35}, application=Application.class, manifest=Config.NONE,
 instrumentedPackages="io.nekohasekai.sagernet.ktx", shadows=StatsBarContextTest.ShadowUtils.class)
public class ServiceButtonLayoutTest {
 private RectF rect(View view) {
  float x=view.getX(), y=view.getY();
  ViewParent p=view.getParent();
  while(p instanceof View){x+=((View)p).getX();y+=((View)p).getY();p=p.getParent();}
  return new RectF(x,y,x+view.getWidth(),y+view.getHeight());
 }
 @Test public void stateTransitionsKeepServiceControlOutsideStatsContent() {
  SagerNet app=new SagerNet();
  ReflectionHelpers.callInstanceMethod(app,"attach",ReflectionHelpers.ClassParameter.from(Context.class,RuntimeEnvironment.getApplication()));
  SagerNet.Companion.setApplication(app);
  ArrayList<String> failures=new ArrayList<>();
  for(boolean night:new boolean[]{false,true}) for(boolean landscape:new boolean[]{false,true}) {
   RuntimeEnvironment.setQualifiers((landscape?"land":"port")+"-"+(night?"night":"notnight"));
   MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
   activity.setTheme(R.style.Theme_SagerNet_Pink_SSR);
   androidx.savedstate.SavedStateRegistryController saved=ReflectionHelpers.getField(activity,"savedStateRegistryController");
   saved.performRestore(null);
   LifecycleRegistry lifecycle=(LifecycleRegistry)activity.getLifecycle();
   lifecycle.setCurrentState(Lifecycle.State.STARTED);
   ViewGroup root=(ViewGroup)LayoutInflater.from(activity).inflate(R.layout.layout_main,null,false);
   root.removeView(root.findViewById(R.id.nav_view_black));
   ServiceButton fab=root.findViewById(R.id.fab);
   StatsBar bar=root.findViewById(R.id.stats);
   fab.initProgress(root.findViewById(R.id.fabProgress));
   bar.setOnClickListener(v->{});
   int[] clicks={0};fab.setOnClickListener(v->clicks[0]++);
   int w=landscape?934:425,h=landscape?425:934;
   BaseService.State previous=BaseService.State.Stopped;
   for(BaseService.State state:new BaseService.State[]{BaseService.State.Idle,BaseService.State.Stopped,BaseService.State.Connecting,BaseService.State.Connected,BaseService.State.Stopping,BaseService.State.Stopped}) {
    fab.changeState(state,previous,false);bar.changeState(state);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
    bar.updateSpeed(1024,2048);
    root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
    RectF fr=rect(fab),br=rect(bar),content=rect(bar.getChildAt(0));
    System.out.println("STATE="+state+" night="+night+" landscape="+landscape+" fab="+fr+" bar="+br+" content="+content+" fabZ="+fab.getZ()+" barZ="+bar.getZ()+" anchorMode="+bar.getFabAnchorMode());
    assertEquals(View.VISIBLE,fab.getVisibility());assertEquals(1f,fab.getAlpha(),0.001f);
    assertTrue(fab.isClickable());
    assertEquals(state.getCanStop() || state==BaseService.State.Stopped,fab.isEnabled());
    if(fab.isEnabled()){int before=clicks[0];fab.performClick();assertEquals(before+1,clicks[0]);}
    assertEquals(R.id.stats,fab.getNextFocusDownId());assertEquals(R.id.fab,bar.getNextFocusUpId());
    assertTrue(((TextView)root.findViewById(R.id.tx)).getText().length()>0);
    assertTrue(((TextView)root.findViewById(R.id.rx)).getText().length()>0);
    for(int id:new int[]{R.id.tx,R.id.rx}){
     TextView text=root.findViewById(id);
     assertEquals(0,text.getLayout().getEllipsisCount(0));
    }
    assertEquals(R.id.fab,((androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)root.findViewById(R.id.fabProgress).getLayoutParams()).getAnchorId());
    // A cradle may intersect the bar edge; its center must remain above the full-width content.
    if(state==BaseService.State.Connected && fr.centerY()>br.top) failures.add("FAB center embedded in statistics content: "+fr+" / "+content);
    previous=state;
   }
   lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
  }
  assertTrue(failures.toString(),failures.isEmpty());
 }
}
