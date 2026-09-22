package kr.geniu.navdyhud;

/**
 * 콤마 좌표계(미터) → HUD 화면 좌표(픽셀) 투영.
 *
 * 콤마가 보내는 값은 차량 기준이다: x 는 전방 거리, y 는 횡방향이고 음수가 왼쪽이다
 * (기기 실측: laneLines[0]=-3.69 바깥좌, laneLines[3]=+3.93 바깥우.
 * 화면에 올릴 때는 screenX 가 부호를 한 번 뒤집는다. 아래 설명 참고.)
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
   * 넓게 퍼진다.
   *
   * 상하 1/6 이 투사되지 않아 쓸 수 있는 높이가 480 에서 320 으로 줄었으므로
   * 그만큼 낮춘다. 1.65m 면 3m 가 안전영역 바닥, 50m 가 지평선 근처에 놓인다.
   */
  private static final float CAM_H = 1.65f;

  /** 이보다 멀면 지평선에 뭉쳐 한 점이 된다. 멀리 그릴수록 근거리가 눌린다. */
  public static final float MAX_X = 50.0f;

  /**
   * 콤바이너에 제대로 투사되지 않는 상하 여백(화면 높이 비율).
   *
   * 실차에서 위아래 1/6 이 잘려 보인다. 그 밖으로 그린 것은 운전자에게 닿지
   * 않으므로, 도로도 글자도 이 안쪽에만 배치한다.
   */
  public static final float SAFE_MARGIN = 1f / 6f;

  private float cx, horizon, focal, camH = CAM_H;
  private float safeTop, safeBottom;

  public void setViewport(int w, int h) {
    cx = w * 0.5f;
    safeTop = h * SAFE_MARGIN;
    safeBottom = h * (1f - SAFE_MARGIN);
    // 지평선은 안전영역 안에서 위쪽 30% 지점에 둔다.
    horizon = safeTop + (safeBottom - safeTop) * 0.30f;
    // 640 폭 기준 400px 초점거리가 실차에서 차선 간격이 자연스럽게 보이는 값.
    focal = w * 0.625f;
  }

  /** 안전영역 위쪽 경계. 글자를 이 아래에 둔다. */
  public float safeTop() {
    return safeTop;
  }

  /** 안전영역 아래쪽 경계. 자차 아이콘을 이 위에 둔다. */
  public float safeBottom() {
    return safeBottom;
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

  /**
   * 횡방향을 화면 가로로 옮긴다. 부호를 뒤집는다.
   *
   * 데이터만 보면 뒤집을 이유가 없다. 기기에서 modelV2.laneLines 를 재 보면
   * index 0(바깥 왼쪽)이 y=-3.69, index 3(바깥 오른쪽)이 y=+3.93 이라
   * y 음수가 왼쪽이고, 그대로 두면(cx + f*y/x) 왼쪽이 화면 왼쪽에 온다.
   *
   * 그런데 실차에서는 그 상태가 좌우 반전으로 보인다. 즉 나브디의 광학계가
   * 좌우를 한 번 더 뒤집는다. 여기서 미리 뒤집어야 운전자에게 바로 보인다.
   *
   * 주의: adb screencap 으로 받은 그림은 이 반전 이전 상태다. 스크린샷으로
   * 좌우를 판단하면 안 된다. 실제로 그렇게 판단했다가 부호를 잘못 되돌려
   * 모든 도로 정보가 뒤집힌 적이 있다. 좌우 검증은 실차에서만 한다.
   */
  public float screenX(float x, float y) {
    return cx - focal * y / Math.max(MIN_X, x);
  }

  public float screenY(float x) {
    return horizon + focal * camH / Math.max(MIN_X, x);
  }

  /** 높이 z(m)인 점의 화면 y. 차량처럼 높이가 있는 물체를 입체로 그릴 때 쓴다. */
  public float screenY(float x, float z) {
    return horizon + focal * (camH - z) / Math.max(MIN_X, x);
  }

  /** 거리 x 에서 폭 meters 가 화면에서 차지하는 픽셀. 아이콘 크기 조절용. */
  public float scale(float x, float meters) {
    return focal * meters / Math.max(MIN_X, x);
  }
}
