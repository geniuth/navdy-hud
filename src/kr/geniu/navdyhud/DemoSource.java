package kr.geniu.navdyhud;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 콤마 없이 모든 화면 상태를 순회하는 데모.
 *
 * 사각지대 빨간 표시는 깜빡이를 켠 채 옆 차선에 차가 있어야 하고, 과속카메라
 * 표지는 단속 구간에 들어가야 뜬다. 그런 상태를 실차에서 우연히 만나길
 * 기다리면 디자인 작업이 몇 주 늘어진다. 여기서 합성 데이터로 돌려본다.
 *
 * 중요한 것은 콤마가 보내는 것과 **같은 모양의 패킷**을 만들어 같은 렌더
 * 경로로 넣는 점이다. 데모 전용 그리기를 따로 두면 여기서 예쁜 것이 실차에서
 * 다르게 나온다.
 *
 * 실행:  adb shell am start -n kr.geniu.navdyhud/.HudActivity --ez demo true
 */
public class DemoSource {

  /** 장면 하나의 길이(ms). 한 바퀴 약 40초. */
  private static final int SCENE_MS = 5000;
  private static final int TICK_MS = 100;          // 콤마와 같은 10Hz
  private static final int SCENES = 8;

  private final HudView view;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean running;
  private int tick;

  public DemoSource(HudView view) {
    this.view = view;
  }

  public void start() {
    running = true;
    view.setLinkState("데모");
    handler.post(loop);
  }

  public void stop() {
    running = false;
    handler.removeCallbacks(loop);
  }

  private final Runnable loop = new Runnable() {
    @Override public void run() {
      if (!running) {
        return;
      }
      int scene = (tick * TICK_MS / SCENE_MS) % SCENES;
      float t = (tick * TICK_MS % SCENE_MS) / (float) SCENE_MS;   // 장면 내 0~1
      // 8번 장면은 "신호 끊김"이라 일부러 아무것도 보내지 않는다.
      // 그러면 HudView 의 stale 처리가 동작하는 것까지 확인된다.
      if (scene != 7) {
        view.update(build(scene, t));
      }
      tick++;
      handler.postDelayed(this, TICK_MS);
    }
  };

  // ---- 장면 ----

  private JSONObject build(int scene, float t) {
    JSONObject p = new JSONObject();
    try {
      int speed = 62;
      float curve = 0f;
      p.put("lanes", lanes(0.97, curve));
      p.put("edges", edges(curve));
      p.put("path", path(curve));

      switch (scene) {
        case 0:   // 정속 주행, 앞차가 서서히 가까워진다
          p.put("lead", lead(60f - 25f * t, 0.05f, -4f, -0.1f));
          break;
        case 1:   // 앞차 급감속 -> 후미등 빨강
          p.put("lead", lead(35f - 12f * t, 0f, -22f, -2.4f));
          break;
        case 2:   // 좌측 깜빡이, 사각지대 비어있음 -> 노랑
          p.put("leftBlinker", true);
          p.put("leftBsd", false);
          p.put("lead", lead(48f, 0f, 0f, 0f));
          break;
        case 3:   // 좌측 깜빡이, 사각지대 점유 -> 빨강
          p.put("leftBlinker", true);
          p.put("leftBsd", true);
          p.put("lead", lead(48f, 0f, 0f, 0f));
          break;
        case 4:   // 과속카메라 접근 200m -> 40m
          p.put("camera", 50);
          p.put("cameraDist", Math.round(200 - 160 * t));
          speed = 74;
          break;
        case 5:   // 구간단속
          p.put("camera", 80);
          p.put("cameraDist", Math.round(1200 - 400 * t));
          p.put("cameraSection", true);
          speed = 83;
          break;
        case 6:   // 과속방지턱 + 우측 깜빡이/사각지대 점유
          p.put("bumpDist", Math.round(60 - 45 * t));
          p.put("rightBlinker", true);
          p.put("rightBsd", true);
          speed = 34;
          break;
        default:
          break;
      }
      p.put("speed", speed);
      p.put("limit", scene == 5 ? 80 : 60);
    } catch (Exception ignored) {
      // JSONObject.put 은 값이 NaN 일 때만 던진다. 여기서는 나올 수 없다.
    }
    return p;
  }

  // ---- 합성 기하 ----

  /** 콤마와 같은 거리 배열. 가까운 쪽을 촘촘히 둔다. */
  private static final float[] XS = {
      3f, 4.7f, 6.8f, 9.2f, 12f, 15.2f, 18.8f, 22.7f, 27f, 31.7f, 36.8f, 50f, 70f};

  private JSONArray lanes(double conf, float curve) throws Exception {
    JSONArray out = new JSONArray();
    float[] offsets = {-5.4f, -1.8f, 1.8f, 5.4f};
    double[] confs = {conf * 0.6, conf, conf, conf * 0.5};
    for (int i = 0; i < offsets.length; i++) {
      JSONObject lane = new JSONObject();
      lane.put("p", line(offsets[i], curve));
      lane.put("c", Math.round(confs[i] * 100) / 100.0);
      out.put(lane);
    }
    return out;
  }

  private JSONArray edges(float curve) throws Exception {
    JSONArray out = new JSONArray();
    for (float y : new float[]{-7.2f, 7.2f}) {
      JSONObject e = new JSONObject();
      e.put("p", line(y, curve));
      out.put(e);
    }
    return out;
  }

  private JSONArray path(float curve) throws Exception {
    JSONArray out = new JSONArray();
    for (float x : XS) {
      JSONArray pt = new JSONArray();
      pt.put(x);
      pt.put(curve * x * x * 0.002f);
      pt.put(0);
      out.put(pt);
    }
    return out;
  }

  /** 거리에 따라 2차로 휘는 선. curve=0 이면 직선. */
  private JSONArray line(float offset, float curve) throws Exception {
    JSONArray pts = new JSONArray();
    for (float x : XS) {
      JSONArray pt = new JSONArray();
      pt.put(Math.round(x * 100) / 100.0);
      pt.put(Math.round((offset + curve * x * x * 0.002f) * 100) / 100.0);
      pts.put(pt);
    }
    return pts;
  }

  private JSONObject lead(float d, float y, float v, float a) throws Exception {
    JSONObject o = new JSONObject();
    o.put("d", Math.round(d * 10) / 10.0);
    o.put("y", y);
    o.put("v", v);
    o.put("a", a);
    o.put("src", "R");
    o.put("p", 1.0);
    return o;
  }
}
