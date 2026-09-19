package kr.geniu.navdyhud;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

import org.json.JSONObject;

/** Navdy HUD 렌더러. 콤마 텔레메트리를 받아 HudView 에 넘긴다. */
public class HudActivity extends Activity implements CommaLink.Listener {

  private HudView view;
  private CommaLink link;
  private DemoSource demo;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    view = new HudView(this);
    setContentView(view);

    // adb shell am start -n kr.geniu.navdyhud/.HudActivity --ez demo true
    //
    // 데모일 때는 콤마 링크를 아예 띄우지 않는다. 둘 다 돌면 실제 패킷이
    // 합성 데이터를 덮어써서 원하는 상태를 볼 수 없다.
    boolean wantDemo = getIntent() != null && getIntent().getBooleanExtra("demo", false);
    if (wantDemo) {
      demo = new DemoSource(view);
      demo.start();
    } else {
      link = new CommaLink(this);
      link.start();
    }
  }

  @Override
  protected void onDestroy() {
    if (link != null) {
      link.stop();
    }
    if (demo != null) {
      demo.stop();
    }
    super.onDestroy();
  }

  @Override
  public void onPacket(JSONObject packet) {
    view.update(packet);
  }

  @Override
  public void onState(String state) {
    view.setLinkState(state);
  }
}
