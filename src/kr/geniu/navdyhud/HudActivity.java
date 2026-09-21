package kr.geniu.navdyhud;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.json.JSONObject;

/** Navdy HUD 렌더러. 콤마 텔레메트리를 받아 HudView 에 넘긴다. */
public class HudActivity extends Activity implements CommaLink.Listener {

  private static final String TAG = "CommaHUD";

  /**
   * 다이얼을 이만큼 누르고 있으면 순정 UI 로 빠진다.
   *
   * 안드로이드 기본 롱프레스(약 0.5초)로는 짧다. 다이얼은 돌리다가 눌리기도
   * 하는데 그때마다 화면이 넘어가면 주행 중에 곤란하다.
   */
  private static final long HOLD_MS = 1200;

  /**
   * 순정 UI 로 빠진 뒤 이만큼 지나면 HUD 로 저절로 돌아온다.
   *
   * 리모컨만으로는 돌아올 방법이 없어서 넣었다. 나브디 런처(com.navdy.launcher)
   * 는 검은 화면만 띄우고 앱 목록이 없다. 순정 UI 의 Back 은 그 런처로 빠진다.
   *
   * 주행 중에 HUD 가 영영 사라지는 쪽이 더 위험하다고 보고 자동 복귀를 택했다.
   * 지도를 더 오래 보고 싶으면 이 값을 늘리면 된다.
   */
  private static final long RETURN_MS = 3 * 60 * 1000;

  private HudView view;
  private CommaLink link;
  private DemoSource demo;

  private final Handler handler = new Handler();
  private boolean holding;
  private final Runnable switchToStock = new Runnable() {
    @Override
    public void run() {
      holding = false;
      Log.i(TAG, "다이얼 길게 누름 - 순정 UI 로 전환");
      scheduleReturn();
      // 액티비티를 끝내지 않고 뒤로만 보낸다. 콤마 RFCOMM 링크가 살아 있어
      // 돌아왔을 때 다시 붙기를 기다리지 않아도 된다.
      moveTaskToBack(true);
    }
  };

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

  /** HUD 로 돌아오는 알람. 프로세스가 죽어도 살아남도록 AlarmManager 를 쓴다. */
  private PendingIntent returnIntent() {
    Intent i = new Intent(this, HudActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private void scheduleReturn() {
    AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
    if (am == null) {
      return;
    }
    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + RETURN_MS, returnIntent());
  }

  private void cancelReturn() {
    AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
    if (am != null) {
      am.cancel(returnIntent());
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    // 어떤 경로로 돌아왔든 예약된 복귀는 필요 없다.
    cancelReturn();
  }

  /**
   * 나브디 다이얼 클릭. HID 로는 KEY_ENTER 가 온다(실측).
   * 일부 펌웨어/런처가 DPAD_CENTER 로 주기도 하므로 둘 다 받는다.
   */
  private static boolean isDialClick(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_ENTER
        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (isDialClick(keyCode)) {
      // 키 반복은 무시한다. 누르기 시작한 첫 이벤트에서만 타이머를 건다.
      if (event.getRepeatCount() == 0 && !holding) {
        holding = true;
        handler.postDelayed(switchToStock, HOLD_MS);
      }
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (isDialClick(keyCode)) {
      if (holding) {
        holding = false;
        handler.removeCallbacks(switchToStock);
        // 짧게 누른 것은 아무 일도 하지 않는다. 다만 길게 누르면 된다는 걸
        // 알 방법이 화면에 없으므로 한 줄 띄워 준다.
        //
        // Toast 를 쓰면 안 된다. 이 펌웨어에는 NotificationManager 서비스가
        // 없어서 Toast.show() 가 NPE 로 앱을 죽인다(실제로 죽였다).
        view.showHint("길게 누르면 나브디 화면 (3분 뒤 자동 복귀)", 1800);
      }
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override
  protected void onPause() {
    // 뒤로 보내는 중이든 화면이 꺼지든, 눌린 채로 남은 타이머는 정리한다.
    holding = false;
    handler.removeCallbacks(switchToStock);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (link != null) {
      link.stop();
    }
    if (demo != null) {
      demo.stop();
    }
    handler.removeCallbacks(switchToStock);
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
