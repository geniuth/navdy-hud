package kr.geniu.navdyhud;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

/**
 * USB 로 넣어 둔 APK 를 부팅 때 찾아 설치 화면을 띄운다.
 *
 * 집에서는 12V 를 물릴 수 없어 나브디가 부팅하지 않고, 그러면 adb 도 붙지
 * 않는다. 대신 USB 만 꽂으면 나브디가 대용량저장소로 잡히고, 그 드라이브가
 * 기기 안에서는 /maps 로 보인다(vfat, 누구나 쓰기 가능). 거기에 APK 를
 * 복사해 두면 차에서 전원이 들어올 때 이 코드가 집어 든다.
 *
 * 설치 자체는 우리가 못 한다. /sbin/su 가 root:shell 이라 앱(u0_aNN)은 쓸 수
 * 없고, pm install 은 root 나 시스템 권한이 필요하다. 그래서 표준 설치 화면
 * (com.android.packageinstaller)을 띄우고 확인만 받는다. 나브디 다이얼로
 * 조작된다: 아래로 버튼 줄까지 내려가 오른쪽이 INSTALL, 클릭이 확정이다.
 *
 * 전제 조건이 하나 있다. '알 수 없는 소스' 가 켜져 있어야 한다:
 *
 *     adb shell settings put secure install_non_market_apps 1
 *
 * secure 설정이라 재부팅해도 남는다. 초기화하면 다시 켜야 한다.
 */
public final class Updater {

  private static final String TAG = "CommaHUD";

  /** 나브디를 USB 로 꽂았을 때 PC 에 보이는 드라이브가 기기에서는 여기다. */
  private static final File APK = new File("/maps/CommaHUD.apk");

  private Updater() {
  }

  /**
   * /maps 에 지금 설치된 것보다 새 APK 가 있으면 그 파일, 없으면 null.
   *
   * versionCode 로만 판단한다. 같은 값이면 이미 설치한 파일이 그대로 남아
   * 있는 것이므로 부팅할 때마다 설치 화면이 뜨지 않는다.
   */
  public static File pending(Context ctx) {
    if (!APK.isFile() || APK.length() == 0) {
      return null;
    }
    PackageManager pm = ctx.getPackageManager();
    PackageInfo incoming = pm.getPackageArchiveInfo(APK.getAbsolutePath(), 0);
    if (incoming == null || !ctx.getPackageName().equals(incoming.packageName)) {
      // 깨진 파일이거나 다른 앱이다. 남의 APK 를 설치하자고 띄우지 않는다.
      return null;
    }
    int mine;
    try {
      mine = pm.getPackageInfo(ctx.getPackageName(), 0).versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      return null;
    }
    if (incoming.versionCode <= mine) {
      return null;
    }
    Log.i(TAG, "USB 업데이트 발견: " + mine + " -> " + incoming.versionCode);
    return APK;
  }

  /** '알 수 없는 소스' 가 꺼져 있으면 설치 화면이 떠도 진행되지 않는다. */
  public static boolean sideloadAllowed(Context ctx) {
    try {
      return Settings.Secure.getInt(ctx.getContentResolver(),
          Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** 표준 설치 화면을 띄운다. 실제 설치 여부는 사용자가 정한다. */
  public static boolean install(Context ctx, File apk) {
    Intent i = new Intent(Intent.ACTION_VIEW);
    i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      ctx.startActivity(i);
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "설치 화면 실패: " + e);
      return false;
    }
  }
}
