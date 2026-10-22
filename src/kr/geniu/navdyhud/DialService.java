package kr.geniu.navdyhud;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 다이얼을 길게 누르면 어느 화면에서든 HUD 와 순정 나브디 화면을 오간다.
 *
 * 순정 메뉴에 "오픈파일럿" 항목을 끼워 넣을 수는 없다. 순정 앱은 system 권한으로
 * 도는 앱이라 고쳐서 다시 서명할 수 없다. 그렇다고 HudActivity 의 키 처리만으로는
 * 한쪽 길밖에 안 된다. 안드로이드는 키를 전면 액티비티에만 주므로, 순정 화면이
 * 앞에 있으면 우리 앱은 다이얼을 볼 수 없다. 그래서 순정 화면에서 돌아올 길이
 * 3분 자동 복귀뿐이었다.
 *
 * 접근성 서비스는 전면 앱과 상관없이 키를 먼저 본다(root 불필요). 여기서 길게
 * 누르기를 잡아 지금 앞에 있는 쪽의 반대편을 띄운다.
 *
 * 키는 가로채지 않는다. 순정 메뉴의 클릭과 회전이 그대로 동작해야 한다. 다만
 * 전환을 일으킨 그 홀드의 나머지(반복 DOWN 과 마지막 UP)는 삼킨다. 그냥 흘리면
 * 막 띄운 화면에 그 UP 이 떨어져 클릭으로 먹힌다 - 순정 메뉴가 엉뚱하게 눌린다.
 *
 * 한 번 켜 두면 재부팅해도 유지된다:
 *
 *     adb shell settings put secure enabled_accessibility_services \
 *         kr.geniu.navdyhud/kr.geniu.navdyhud.DialService
 *     adb shell settings put secure accessibility_enabled 1
 */
public class DialService extends AccessibilityService {

  private static final String TAG = "CommaHUD";

  /** HudActivity 가 스스로 길게 누르기를 처리할지 정하는 데 쓴다. 같은 프로세스다. */
  static volatile boolean running;

  private final Handler handler = new Handler();
  private boolean holding;
  /** 전환을 일으킨 홀드가 끝날 때까지 이 키의 이벤트를 삼킨다. */
  private boolean swallowing;
  /** 마지막으로 창이 바뀐 패키지. 지금 앞에 있는 앱으로 본다. */
  private volatile String foreground = "";

  private final Runnable toggle = new Runnable() {
    @Override
    public void run() {
      holding = false;
      swallowing = true;
      if (getPackageName().equals(foreground)) {
        Log.i(TAG, "다이얼 길게 누름: HUD -> 순정");
        HudActivity.launchStock(DialService.this);
      } else {
        Log.i(TAG, "다이얼 길게 누름: " + foreground + " -> HUD");
        HudActivity.launchHud(DialService.this);
      }
    }
  };

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    running = true;
    Log.i(TAG, "다이얼 서비스 연결");
  }

  @Override
  public boolean onUnbind(Intent intent) {
    running = false;
    handler.removeCallbacks(toggle);
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    running = false;
    handler.removeCallbacks(toggle);
    super.onDestroy();
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && event.getPackageName() != null) {
      foreground = event.getPackageName().toString();
    }
  }

  @Override
  public void onInterrupt() {
  }

  @Override
  protected boolean onKeyEvent(KeyEvent event) {
    if (!HudActivity.isDialClick(event.getKeyCode())) {
      return false;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      if (swallowing) {
        return true;   // 전환을 일으킨 홀드의 반복 DOWN
      }
      if (event.getRepeatCount() == 0 && !holding) {
        holding = true;
        handler.postDelayed(toggle, HudActivity.HOLD_MS);
      }
      return false;
    }
    if (event.getAction() == KeyEvent.ACTION_UP) {
      if (swallowing) {
        swallowing = false;
        return true;   // 막 띄운 화면에 클릭으로 떨어지지 않게
      }
      if (holding) {
        holding = false;
        handler.removeCallbacks(toggle);
      }
    }
    return false;
  }
}
