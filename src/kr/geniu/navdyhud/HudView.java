package kr.geniu.navdyhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 콤마 텔레메트리를 HUD 화면에 그린다.
 *
 * 콤바이너 HUD 는 검은색을 투사하지 않는다. 배경은 항상 검게 두고, 실제로
 * 보여야 하는 것만 밝게 그린다. 회색 톤은 흐릿하게 보이므로 쓰지 않는다.
 */
public class HudView extends View {

  // 노면 차선은 실제로 흰색이다. HUD 에서도 흰색이 가장 밝고 또렷하다.
  private static final int COL_LANE        = Color.WHITE;
  private static final int COL_LANE_YELLOW = Color.rgb(255, 220, 80);
  // 회색 계열은 콤바이너에서 주간에 거의 사라진다. 차선과 같은 계열을 어둡게 쓴다.
  private static final int COL_EDGE      = Color.rgb(40, 110, 150);
  private static final int COL_PATH      = Color.rgb(60, 220, 130);
  private static final int COL_EGO       = Color.rgb(80, 255, 120);
  private static final int COL_LEAD      = Color.rgb(255, 210, 90);
  private static final int COL_LEAD_BRAKE= Color.rgb(255, 90, 80);
  private static final int COL_WARN      = Color.rgb(255, 60, 60);
  private static final int COL_CLEAR     = Color.rgb(255, 210, 60);
  private static final int COL_TEXT      = Color.WHITE;

  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Projection proj = new Projection();
  private final Path scratch = new Path();

  /**
   * 이보다 오래된 데이터는 주행 상황을 반영하지 않는다고 본다.
   * 10Hz 로 오므로 1.5초면 15프레임을 놓친 것이다.
   */
  private static final long STALE_MS = 1500;

  private volatile JSONObject packet;
  private volatile String linkState = "시작중";
  private volatile long packets = 0;
  private volatile long lastPacketMs = 0;

  public HudView(Context c) {
    super(c);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeCap(Paint.Cap.ROUND);
    fill.setStyle(Paint.Style.FILL);
    text.setColor(COL_TEXT);
    text.setTextSize(34f);
    text.setFakeBoldText(true);
    small.setColor(COL_TEXT);
    small.setTextSize(20f);
  }

  public void update(JSONObject p) {
    packet = p;
    packets++;
    lastPacketMs = android.os.SystemClock.elapsedRealtime();
    postInvalidate();
  }

  private boolean stale() {
    return lastPacketMs == 0
        || android.os.SystemClock.elapsedRealtime() - lastPacketMs > STALE_MS;
  }

  public void setLinkState(String s) {
    linkState = s;
    postInvalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    super.onSizeChanged(w, h, ow, oh);
    proj.setViewport(w, h);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth(), h = getHeight();
    canvas.drawColor(Color.BLACK);

    JSONObject p = packet;
    if (p == null) {
      canvas.drawText("링크: " + linkState, 16f, proj.safeTop() + 34f, text);
      drawEgo(canvas, h);
      return;
    }

    // 링크가 끊겨도 마지막 화면이 그대로 남으면, 주행 중에 낡은 차선과 앞차를
    // 실시간인 것처럼 보게 된다. 오래된 데이터면 움직이는 것들은 전부 감춘다.
    if (!stale()) {
      drawEdges(canvas, p.optJSONArray("edges"));
      drawLanes(canvas, p.optJSONArray("lanes"), p);
      drawPath(canvas, p.optJSONArray("path"));
      drawLead(canvas, p.optJSONObject("lead2"), false);
      drawLead(canvas, p.optJSONObject("lead"), true);
      drawCamera(canvas, p, w);
    }
    drawEgo(canvas, h);
    drawStatus(canvas, p, w, h);

    if (stale()) {
      // 다음 패킷이 없으면 onDraw 가 다시 안 불리므로, 끊긴 뒤에도 표시를
      // 지우려면 스스로 한 번 더 그려야 한다.
      postInvalidateDelayed(500);
    }
  }

  // ---- 1. 차선 / 도로경계 / 경로 ----

  /** 노면 표시와 같은 파선 간격. 실선일 때는 쓰지 않는다. */
  private final android.graphics.DashPathEffect dashed =
      new android.graphics.DashPathEffect(new float[]{20f, 16f}, 0f);

  private void drawLanes(Canvas canvas, JSONArray lanes, JSONObject p) {
    if (lanes == null) {
      return;
    }
    // 자차선 종류는 카메라가 읽은 실제 노면 표시다(색*10 + 0점선/1실선/2미상).
    // 모델 차선(laneLines)에는 없는 정보라 여기서만 얻을 수 있다.
    int typeL = p.optInt("laneL", -1);
    int typeR = p.optInt("laneR", -1);

    for (int i = 0; i < lanes.length(); i++) {
      JSONObject lane = lanes.optJSONObject(i);
      if (lane == null) {
        continue;
      }
      double conf = lane.optDouble("c", 0.0);
      if (conf < 0.05) {
        continue;   // 모델이 확신 못 하는 차선을 그리면 없는 차선이 생긴다
      }
      boolean inner = (i == 1 || i == 2);
      int type = inner ? (i == 1 ? typeL : typeR) : -1;

      stroke.setColor(laneColor(p, i, type));
      stroke.setAlpha((int) (90 + 165 * Math.min(1.0, conf)));
      stroke.setStrokeWidth(inner ? 5f : 3f);
      // 종류를 아는 자차선만 점선/실선을 구분한다. 바깥 차선은 정보가 없으므로
      // 실선으로 그려 없는 노면 표시를 지어내지 않는다.
      stroke.setPathEffect(type >= 0 && type % 10 == 0 ? dashed : null);
      strokePolyline(canvas, lane.optJSONArray("p"));
    }
    stroke.setPathEffect(null);
  }

  /**
   * 차선 색. 기본은 흰색이고, 사각지대에 차가 있을 때만 그 쪽 차선이 경고색이 된다.
   *
   * 깜빡이를 안 켰으면 노랑(옆에 차가 있다), 그 방향으로 깜빡이를 켰으면
   * 빨강(지금 들어가면 위험하다). 화살표 같은 별도 기호 없이 차선 자체를
   * 물들이면 시선이 도로에서 떨어지지 않는다.
   */
  private int laneColor(JSONObject p, int index, int type) {
    boolean leftSide = index <= 1;
    boolean bsd = p.optBoolean(leftSide ? "leftBsd" : "rightBsd", false);
    if (bsd) {
      boolean blinker = p.optBoolean(leftSide ? "leftBlinker" : "rightBlinker", false);
      return blinker ? COL_WARN : COL_CLEAR;
    }
    // 노란 차선은 실제 노면이 노란색이라는 뜻이므로 그대로 살린다(색 코드 2x).
    if (type >= 20 && type < 30) {
      return COL_LANE_YELLOW;
    }
    return COL_LANE;
  }

  private void drawEdges(Canvas canvas, JSONArray edges) {
    if (edges == null) {
      return;
    }
    stroke.setColor(COL_EDGE);
    stroke.setAlpha(150);
    stroke.setStrokeWidth(2.5f);
    for (int i = 0; i < edges.length(); i++) {
      JSONObject e = edges.optJSONObject(i);
      if (e != null) {
        strokePolyline(canvas, e.optJSONArray("p"));
      }
    }
  }

  /**
   * openpilot 이 가려는 경로.
   *
   * 선 한 줄로 그으면 굵기가 일정해서 수직 막대처럼 보이고 길로 읽히지 않는다.
   * 실제 폭(약 1.4m)을 거리마다 투영해 좌우 가장자리를 만들고 그 사이를 채우면
   * 가까울수록 넓어지는 띠가 되어 원근이 생긴다.
   */
  // 넓게 채우면 HUD 에서 뒤의 실제 도로를 가린다. 자차 폭이 아니라
  // 진행 방향만 알려주는 가는 띠로 둔다.
  private static final float PATH_WIDTH_M = 0.7f;
  private static final float PATH_MAX_X = 35f;   // 너무 멀리 가면 실처럼 가늘어진다

  private void drawPath(Canvas canvas, JSONArray path) {
    if (path == null || path.length() < 2) {
      return;
    }
    // 좌측 가장자리는 가까운 쪽부터, 우측은 먼 쪽부터 넣어 닫힌 다각형을 만든다.
    float[] us = new float[path.length()];
    float[] vs = new float[path.length()];
    float[] hw = new float[path.length()];
    int n = 0;
    for (int i = 0; i < path.length(); i++) {
      JSONArray pt = path.optJSONArray(i);
      if (pt == null || pt.length() < 2) {
        continue;
      }
      float x = (float) pt.optDouble(0, 0), y = (float) pt.optDouble(1, 0);
      if (!proj.visible(x) || x > PATH_MAX_X) {
        continue;
      }
      us[n] = proj.screenX(x, y);
      vs[n] = proj.screenY(x);
      hw[n] = proj.scale(x, PATH_WIDTH_M) * 0.5f;
      n++;
    }
    if (n < 2) {
      return;
    }
    scratch.reset();
    scratch.moveTo(us[0] - hw[0], vs[0]);
    for (int i = 1; i < n; i++) {
      scratch.lineTo(us[i] - hw[i], vs[i]);
    }
    for (int i = n - 1; i >= 0; i--) {
      scratch.lineTo(us[i] + hw[i], vs[i]);
    }
    scratch.close();
    fill.setColor(COL_PATH);
    fill.setAlpha(70);
    canvas.drawPath(scratch, fill);
    // 가장자리를 또렷하게 해서 얇아져도 형태가 읽히게 한다.
    stroke.setColor(COL_PATH);
    stroke.setAlpha(200);
    stroke.setStrokeWidth(2f);
    canvas.drawPath(scratch, stroke);
  }

  private void strokePolyline(Canvas canvas, JSONArray pts) {
    if (pts == null || pts.length() < 2) {
      return;
    }
    scratch.reset();
    boolean started = false;
    for (int i = 0; i < pts.length(); i++) {
      JSONArray pt = pts.optJSONArray(i);
      if (pt == null || pt.length() < 2) {
        continue;
      }
      float x = (float) pt.optDouble(0, 0), y = (float) pt.optDouble(1, 0);
      if (!proj.visible(x) || x > Projection.MAX_X) {
        continue;
      }
      float u = proj.screenX(x, y), v = proj.screenY(x);
      if (!started) {
        scratch.moveTo(u, v);
        started = true;
      } else {
        scratch.lineTo(u, v);
      }
    }
    if (started) {
      canvas.drawPath(scratch, stroke);
    }
  }

  // ---- 1. 인식한 차량 ----

  private void drawLead(Canvas canvas, JSONObject lead, boolean primary) {
    if (lead == null) {
      return;
    }
    float d = (float) lead.optDouble("d", 0);
    float y = (float) lead.optDouble("y", 0);
    if (!proj.visible(d)) {
      return;
    }
    float u = proj.screenX(d, y);
    float v = proj.screenY(d);
    // 실제 차폭 1.8m 를 그 거리에서의 픽셀로 환산한다. 멀수록 작아진다.
    // 멀 때 점 하나로 줄어들면 차인지 알 수 없다. 최소 폭을 둔다.
    float wpx = Math.max(34f, Math.min(200f, proj.scale(d, 1.8f)));
    float hpx = wpx * 0.62f;

    // 앞차가 실제로 감속 중이면(aLeadK 가 충분히 음수) 붉게 바꿔 후미등처럼 쓴다.
    boolean braking = lead.optDouble("a", 0) < -0.5;
    fill.setColor(braking ? COL_LEAD_BRAKE : COL_LEAD);
    fill.setAlpha(primary ? 235 : 130);

    scratch.reset();
    float half = wpx * 0.5f;
    float top = v - hpx;
    // 뒤에서 본 차 모양: 아래가 넓고 위가 살짝 좁은 사다리꼴 + 지붕
    scratch.moveTo(u - half, v);
    scratch.lineTo(u + half, v);
    scratch.lineTo(u + half * 0.86f, top);
    scratch.lineTo(u - half * 0.86f, top);
    scratch.close();
    canvas.drawPath(scratch, fill);

    if (primary) {
      small.setColor(braking ? COL_LEAD_BRAKE : COL_LEAD);
      String label = String.format("%.0fm", d);
      float tw = small.measureText(label);
      canvas.drawText(label, u - tw * 0.5f, top - 6f, small);
      small.setColor(COL_TEXT);
    }
  }

  // ---- 4. 자차 ID.4 아이콘 ----

  private void drawEgo(Canvas canvas, int h) {
    float cx = proj.centerX();
    // 안전영역 바닥에서 조금 띄운다. 그 아래는 투사되지 않는다.
    float cy = proj.safeBottom() - 30f;
    float w = 62f, l = 74f;
    float hw = w * 0.5f, hl = l * 0.5f;

    // 위에서 본 ID.4. 앞이 둥글고 뒤가 각진 SUV 비율에, 사이드미러가 양옆으로
    // 튀어나온 실루엣이 한눈에 차로 읽힌다.
    fill.setColor(COL_EGO);
    fill.setAlpha(240);
    scratch.reset();
    scratch.moveTo(cx - hw * 0.80f, cy + hl);                       // 좌후
    scratch.lineTo(cx + hw * 0.80f, cy + hl);                       // 우후
    scratch.quadTo(cx + hw, cy + hl * 0.72f, cx + hw, cy + hl * 0.30f);
    scratch.lineTo(cx + hw, cy - hl * 0.28f);
    scratch.quadTo(cx + hw * 0.92f, cy - hl * 0.74f, cx + hw * 0.52f, cy - hl);
    scratch.lineTo(cx - hw * 0.52f, cy - hl);                       // 앞
    scratch.quadTo(cx - hw * 0.92f, cy - hl * 0.74f, cx - hw, cy - hl * 0.28f);
    scratch.lineTo(cx - hw, cy + hl * 0.30f);
    scratch.quadTo(cx - hw, cy + hl * 0.72f, cx - hw * 0.80f, cy + hl);
    scratch.close();
    canvas.drawPath(scratch, fill);

    // 사이드미러
    float my = cy - hl * 0.30f, mw = hw * 0.20f, mh = hl * 0.10f;
    canvas.drawRect(cx - hw - mw, my - mh, cx - hw * 0.96f, my + mh, fill);
    canvas.drawRect(cx + hw * 0.96f, my - mh, cx + hw + mw, my + mh, fill);

    // 앞유리와 뒷유리를 검게 파서 진행 방향이 드러나게 한다. HUD 에서 검은색은
    // 투사되지 않으므로 실제로는 '빈 곳'이 되어 형태가 또렷해진다.
    fill.setColor(Color.BLACK);
    fill.setAlpha(255);
    scratch.reset();
    scratch.moveTo(cx - hw * 0.62f, cy - hl * 0.10f);
    scratch.lineTo(cx + hw * 0.62f, cy - hl * 0.10f);
    scratch.lineTo(cx + hw * 0.46f, cy - hl * 0.60f);
    scratch.lineTo(cx - hw * 0.46f, cy - hl * 0.60f);
    scratch.close();
    canvas.drawPath(scratch, fill);

    scratch.reset();
    scratch.moveTo(cx - hw * 0.60f, cy + hl * 0.24f);
    scratch.lineTo(cx + hw * 0.60f, cy + hl * 0.24f);
    scratch.lineTo(cx + hw * 0.52f, cy + hl * 0.62f);
    scratch.lineTo(cx - hw * 0.52f, cy + hl * 0.62f);
    scratch.close();
    canvas.drawPath(scratch, fill);
  }

  // ---- 2. 내비 과속카메라 ----

  private void drawCamera(Canvas canvas, JSONObject p, int w) {
    int limit = p.optInt("camera", 0);
    int dist = p.optInt("cameraDist", 0);
    boolean section = p.optBoolean("cameraSection", false);
    int bump = p.optInt("bumpDist", 0);

    float cx = w - 62f, cy = proj.safeTop() + 42f;
    if (limit > 0 && dist > 0) {
      // 제한속도 원형 표지: 빨간 테두리 + 흰 숫자.
      fill.setColor(Color.WHITE);
      fill.setAlpha(255);
      canvas.drawCircle(cx, cy, 34f, fill);
      stroke.setColor(COL_WARN);
      stroke.setAlpha(255);
      stroke.setStrokeWidth(7f);
      canvas.drawCircle(cx, cy, 30f, stroke);

      Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
      num.setColor(Color.BLACK);
      num.setTextSize(30f);
      num.setFakeBoldText(true);
      String s = String.valueOf(limit);
      canvas.drawText(s, cx - num.measureText(s) * 0.5f, cy + 11f, num);

      small.setColor(COL_TEXT);
      String label = (section ? "구간 " : "") + dist + "m";
      canvas.drawText(label, cx - small.measureText(label) * 0.5f, cy + 58f, small);
    } else if (bump > 0) {
      small.setColor(COL_CLEAR);
      String label = "방지턱 " + bump + "m";
      canvas.drawText(label, w - small.measureText(label) - 16f, cy, small);
      small.setColor(COL_TEXT);
    }
  }

  // ---- 상태 표시 ----

  private void drawStatus(Canvas canvas, JSONObject p, int w, int h) {
    boolean old = stale();
    // 상하 1/6 은 투사되지 않으므로 그 안쪽에만 글자를 둔다.
    float top = proj.safeTop();
    float bottom = proj.safeBottom();
    // 속도까지 낡은 값을 그대로 띄우면 안 되므로 끊기면 흐리게 표시한다.
    text.setAlpha(old ? 90 : 255);
    canvas.drawText(p.optInt("speed", 0) + " km/h", 16f, top + 34f, text);
    text.setAlpha(255);
    int limit = p.optInt("limit", 0);
    if (limit > 0 && !old) {
      small.setColor(COL_TEXT);
      canvas.drawText("제한 " + limit, 16f, top + 58f, small);
    }
    if (old) {
      small.setColor(COL_WARN);
      long ago = lastPacketMs == 0 ? 0
          : (android.os.SystemClock.elapsedRealtime() - lastPacketMs) / 1000;
      canvas.drawText("콤마 신호 끊김 " + ago + "초", 16f, top + 58f, small);
    }
    // 정상일 때 수신 카운터는 개발용 잡음이다. 끊겼을 때만 상태를 남긴다.
    if (old) {
      small.setColor(Color.rgb(140, 160, 180));
      canvas.drawText(linkState + " #" + packets, 16f, bottom - 6f, small);
      small.setColor(COL_TEXT);
    }
  }
}
