package kr.geniu.navdyhud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 부팅이 끝나면 HUD 를 자동으로 띄운다.
 *
 * 이 앱은 런처도 홈도 아니라서, 나브디 전원을 껐다 켜면 순정 UI
 * (com.navdy.hud.app)만 올라오고 HUD 는 뜨지 않았다. 그때마다
 * adb 로 am start 를 해 줘야 했다.
 *
 * 홈 앱으로 등록하면 확실하지만 순정 UI 를 대체해 버리고, 우리 앱이
 * 죽으면 화면에 아무것도 남지 않는다. 그래서 순정 UI 는 그대로 두고
 * 그 위로 올라오는 쪽을 택했다.
 */
public class BootReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    // 액티비티를 여기서 바로 띄우지 않는다. BOOT_COMPLETED 시점에는 순정
    // UI 가 아직 올라오는 중이라 곧바로 덮인다. 지연 실행은 서비스에 맡긴다.
    context.startService(new Intent(context, BootService.class));
  }
}
