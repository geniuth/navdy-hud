package kr.geniu.navdyhud;

/**
 * 콤마 좌표계(미터) → HUD 화면 좌표(픽셀) 투영.
 *
 * 콤마가 보내는 값은 차량 기준이다: x 는 전방 거리, y 는 횡방향이고 음수가 왼쪽이다
 * (실측 확인: lanes[0]=-4.89 바깥좌, lanes[3]=+4.51 바깥우).
 *
 * 노면 위 높이 camH 에 카메라가 있고 정면을 본다고 두면 핀홀 투영이 된다:
 *     u = cx + f * y / x
 *     v = horizon + f * camH / x
 * x 가 커질수록 두 값이 소실점(cx, horizon)으로 모인다.
 */
public class Projection {

  /**
   * 이보다 가까운 점은 그리지 않는다.
   *
   * 5m 로 올렸더니 화면 아래 절반이 비고 차선이 공중에 뜬 것처럼 보였다.
   * 2m 면 x=3m 지점이 화면 중하단에 오고 자차선이 좌우 폭을 거의 채운다.
   * 더 가까운 점은 화면 밖으로 나가지만 Canvas 가 알아서 잘라내고,
   * 실제로도 차선은 시야 아래 모서리로 빠져나가는 게 맞다.
   */
  private static final float MIN_X = 2.0f;

  /**
   * 가상 카메라 높이(m). 실제 카메라 높이가 아니라 화면 구도를 정하는 값이다.
   *
   * 1.2m 로 뒀더니 3~90m 가 화면 y=168~323 사이에만 몰리고 아래 30% 가 비어
   * 도로가 공중에 뜬 것처럼 보였다. 높이를 올리면 같은 거리들이 세로로 더
   * 넓게 퍼진다. 2.2m 면 3m 가 화면 하단(y≈437), 50m 가 지평선 근처(y≈162)에
   * 놓여 화면을 고르게 채운다.
   */
  private static final float CAM_H = 2.2f;

  /** 이보다 멀면 지평선에 뭉쳐 한 점이 된다. 멀리 그릴수록 근거리가 눌린다. */
  public static final float MAX_X = 50.0f;

  private float cx, horizon, focal, camH = CAM_H;

  public void setViewport(int w, int h) {
    cx = w * 0.5f;
    horizon = h * 0.30f;
    // 640 폭 기준 400px 초점거리가 실차에서 차선 간격이 자연스럽게 보이는 값.
    focal = w * 0.625f;
  }

  public void setCameraHeight(float meters) {
    camH = meters;
  }

  public float horizon() {
    return horizon;
  }

  public float centerX() {
    return cx;
  }

  public boolean visible(float x) {
    return x >= MIN_X;
  }

  public float screenX(float x, float y) {
    return cx + focal * y / Math.max(MIN_X, x);
  }

  public float screenY(float x) {
    return horizon + focal * camH / Math.max(MIN_X, x);
  }

  /** 거리 x 에서 폭 meters 가 화면에서 차지하는 픽셀. 아이콘 크기 조절용. */
  public float scale(float x, float meters) {
    return focal * meters / Math.max(MIN_X, x);
  }
}
