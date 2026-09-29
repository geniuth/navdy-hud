package kr.geniu.navdyhud;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

  /**
   * 이미 한 번 물어본 APK 를 기억한다.
   *
   * 안 그러면 설치를 취소하거나 놓쳤을 때 전원을 넣을 때마다 설치 화면이 뜬다.
   * 실제로 그렇게 됐다 - 차에 탈 때마다 HUD 대신 설치 화면이 먼저 나왔다.
   *
   * 버전만으로 기억하면 같은 파일을 다시 복사해도 다시 물어보지 않아 되돌릴
   * 방법이 없다. 파일 수정시각을 같이 넣어, 다시 복사하면(=시각이 바뀌면)
   * 한 번 더 물어보게 한다.
   */
  private static final String PREFS = "updater";
  private static final String KEY_ASKED = "asked";

  private static String stamp(int versionCode, File f) {
    return versionCode + ":" + f.lastModified();
  }

  private Updater() {
  }

  /**
   * /maps 에 아직 안 물어본 새 APK 가 있으면 그 파일, 없으면 null.
   *
   * 설치된 것보다 versionCode 가 높고, 그 파일로 아직 설치 화면을 띄운 적이
   * 없어야 한다. 이미 설치했다면 버전이 같아져서, 취소했다면 물어본 기록이
   * 남아서 각각 걸러진다.
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
    SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String now = stamp(incoming.versionCode, APK);
    if (now.equals(prefs.getString(KEY_ASKED, ""))) {
      Log.i(TAG, "USB 업데이트는 이미 물어봤다: " + now);
      return null;
    }
    Log.i(TAG, "USB 업데이트 발견: " + mine + " -> " + incoming.versionCode);
    return APK;
  }

  /** 물어본 것으로 표시한다. 설치 화면을 띄우기 직전에 부른다. */
  public static void markAsked(Context ctx) {
    PackageInfo incoming = ctx.getPackageManager()
        .getPackageArchiveInfo(APK.getAbsolutePath(), 0);
    if (incoming == null) {
      return;
    }
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_ASKED, stamp(incoming.versionCode, APK)).commit();
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

  /**
   * 표준 설치 화면을 띄운다. 실제 설치 여부는 사용자가 정한다.
   *
   * 띄우는 순간 '물어봤다' 로 기록한다. 결과는 알 수 없지만, 설치를 마쳤다면
   * 다음 부팅 때 버전이 같아져 어차피 안 뜬다.
   */
  public static boolean install(Context ctx, File apk) {
    markAsked(ctx);
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
