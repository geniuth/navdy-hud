package kr.geniu.navdyhud;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

/**
 * 부팅 직후 HUD 액티비티를 띄운다.
 *
 * 한 번만 시도하면 안 된다. BOOT_COMPLETED 가 오는 시점에도 나브디 순정
 * UI 는 아직 시작 중이라, 먼저 띄워 봐야 뒤이어 올라오는 순정 UI 에 덮인다.
 * 그래서 시간을 벌린 뒤 두 번 시도한다. HudActivity 는 singleTask 라
 * 두 번째 시도가 인스턴스를 새로 만들지는 않는다.
 *
 * 마지막 시도 이후로는 아무것도 하지 않는다. 사용자가 일부러 순정 UI 로
 * 돌아갔는데 계속 끌어오면 곤란하다.
 */
public class BootService extends Service {

  private static final String TAG = "CommaHUD";

  /** 순정 UI 가 자리를 잡는 데 걸리는 시간을 넘겨 잡았다. */
  private static final long[] DELAYS_MS = {10000L, 30000L};

  private final Handler handler = new Handler();
  private int pending;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (pending > 0) {
      // 이미 예약돼 있다. 중복 요청은 무시한다.
      return START_NOT_STICKY;
    }
    pending = DELAYS_MS.length;
    for (long delay : DELAYS_MS) {
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          launch();
          pending--;
          if (pending <= 0) {
            stopSelf();
          }
        }
      }, delay);
    }
    return START_NOT_STICKY;
  }

  private void launch() {
    Intent i = new Intent(this, HudActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(i);
      Log.i(TAG, "boot: HUD 시작");
    } catch (RuntimeException e) {
      Log.w(TAG, "boot: " + e);
    }
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
