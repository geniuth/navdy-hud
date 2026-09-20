package kr.geniu.navdyhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
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
 *
 * 색과 투명도에 대한 두 가지 원칙:
 *
 * 1. 색은 의미 하나에만 쓴다. 노란색이 앞차도 되고 사각지대도 되고 방지턱도
 *    되면 색을 봐도 성격을 알 수 없다. 아래 다섯 가지로 고정한다.
 * 2. 투명도로 정보를 표현하지 않는다. 콤바이너에서 밝기를 낮추는 것은
 *    "흐리게"가 아니라 "안 보이게"다. 확신이 덜한 것은 흐리게 그리는 게
 *    아니라 그리지 않고, 중요도 차이는 굵기나 채움/외곽선으로 낸다.
 */
public class HudView extends View {

  // ---- 색: 의미 하나에 하나씩 ----

  /** 노면 사실. 카메라가 실제로 읽은 차선과 인식된 물체. */
  private static final int COL_ROAD        = Color.WHITE;
  /** 노면이 실제로 노란색일 때만. 경고가 아니다. */
  private static final int COL_ROAD_YELLOW = Color.rgb(255, 220, 80);
  /** openpilot 의 의도. 가려는 경로와 자차. */
  private static final int COL_SYSTEM      = Color.rgb(80, 255, 120);
  /** 주의. 옆에 차가 있다, 방지턱이 온다. */
  private static final int COL_CAUTION     = Color.rgb(255, 200, 40);
  /** 위험. 지금 차선을 바꾸면 안 된다, 앞차가 급감속한다, 신호가 끊겼다. */
  private static final int COL_DANGER      = Color.rgb(255, 60, 60);
  /**
   * 검출된 차량.
   *
   * 회색이다. 다만 콤바이너에서 회색은 휘도가 낮아 주간에 먼저 사라지는
   * 색이라, 중간 회색이 아니라 밝은 회색으로 둔다. 주간 주행에서 앞차가
   * 안 보이면 이 값을 흰색까지 올리는 것으로 먼저 대응한다.
   */
  private static final int COL_OBJECT      = Color.rgb(200, 200, 200);

  // ---- 타이포: 속도가 1차 정보다 ----

  private static final float SZ_SPEED = 58f;   // 주행 속도. 힐끗 봐서 읽혀야 한다
  private static final float SZ_UNIT  = 20f;   // 단위는 한 번 익히면 안 읽는다
  private static final float SZ_LABEL = 20f;   // 거리, 상태 문구
  private static final float SZ_SIGN  = 30f;   // 표지 안 숫자
  private static final float SZ_ALERT = 38f;   // 상태 경고. 속도보다 작게 둔다

  /** 제한속도를 이만큼 넘으면 속도 숫자가 주의색이 된다. GPS 오차와 계기 오차를 뺀 값. */
  private static final int OVER_MARGIN = 2;

  /** 이보다 확신이 낮은 차선은 그리지 않는다. 흐리게 그려 봐야 콤바이너에서는 사라진다. */
  private static final double CONF_MIN = 0.25;

  /**
   * 도로 경계선.
   *
   * 주행 판단에 쓰이지 않는데 화면의 선 개수만 늘린다. 어두운 색으로 그리면
   * 주간에 사라지고, 밝게 그리면 차선과 경쟁한다. 실차에서 필요하다고
   * 판단되면 되살릴 수 있게 코드는 남겨둔다.
   */
  private static final boolean SHOW_EDGES = false;

  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint signPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
  /** 이번 프레임의 자차 속도(km/h). 검출 차량 속도 계산에 쓴다. */
  private int egoSpeed = 0;

  public HudView(Context c) {
    super(c);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeCap(Paint.Cap.ROUND);
    fill.setStyle(Paint.Style.FILL);

    speedPaint.setColor(COL_ROAD);
    speedPaint.setTextSize(SZ_SPEED);
    speedPaint.setFakeBoldText(true);

    unitPaint.setColor(COL_ROAD);
    unitPaint.setTextSize(SZ_UNIT);

    labelPaint.setColor(COL_ROAD);
    labelPaint.setTextSize(SZ_LABEL);

    // 글자 둘레를 검게 두른다. 검은색은 투사되지 않으므로 경로 띠나 차선 위에
    // 글자가 놓여도 둘레가 '빈 곳'이 되어 배경과 갈린다.
    labelHalo.setStyle(Paint.Style.STROKE);
    labelHalo.setStrokeWidth(4f);
    labelHalo.setColor(Color.BLACK);
    labelHalo.setTextSize(SZ_LABEL);

    signPaint.setColor(COL_ROAD);
    signPaint.setTextSize(SZ_SIGN);
    signPaint.setFakeBoldText(true);
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
    int w = getWidth();
    canvas.drawColor(Color.BLACK);

    JSONObject p = packet;
    if (p != null) {
      // 검출 차량의 속도를 상대속도에서 복원하는 데 쓴다.
      egoSpeed = p.optInt("speed", 0);
    }
    if (p == null) {
      labelPaint.setColor(COL_ROAD);
      canvas.drawText("링크: " + linkState, 16f, proj.safeTop() + SZ_LABEL, labelPaint);
      drawEgo(canvas);
      return;
    }

    // 링크가 끊겨도 마지막 화면이 그대로 남으면, 주행 중에 낡은 차선과 앞차를
    // 실시간인 것처럼 보게 된다. 오래된 데이터면 움직이는 것들은 전부 감춘다.
    if (!stale()) {
      if (SHOW_EDGES) {
        drawEdges(canvas, p.optJSONArray("edges"));
      }
      drawLanes(canvas, p.optJSONArray("lanes"), p);
      drawPath(canvas, p.optJSONArray("path"));
      drawVehicles(canvas, p);
      drawSign(canvas, p, w);
    }
    drawEgo(canvas);
    drawStatus(canvas, p);

    if (stale()) {
      // 다음 패킷이 없으면 onDraw 가 다시 안 불리므로, 끊긴 뒤에도 표시를
      // 지우려면 스스로 한 번 더 그려야 한다.
      postInvalidateDelayed(500);
    }
  }

  // ---- 1. 차선 / 도로경계 / 경로 ----

  /** 노면 표시와 같은 파선 간격. 실선일 때는 쓰지 않는다. */
  private final DashPathEffect dashed = new DashPathEffect(new float[]{20f, 16f}, 0f);

  private void drawLanes(Canvas canvas, JSONArray lanes, JSONObject p) {
    if (lanes == null) {
      return;
    }
    // 자차선 종류는 카메라가 읽은 실제 노면 표시다(색*10 + 0점선/1실선/2미상).
    // 모델 차선(laneLines)에는 없는 정보라 여기서만 얻을 수 있다.
    int typeL = p.optInt("laneL", -1);
    int typeR = p.optInt("laneR", -1);

    stroke.setAlpha(255);
    for (int i = 0; i < lanes.length(); i++) {
      JSONObject lane = lanes.optJSONObject(i);
      if (lane == null) {
        continue;
      }
      // 확신이 낮은 차선은 흐리게가 아니라 아예 그리지 않는다. 반투명하게
      // 그리면 콤바이너에서는 어차피 안 보이고, 보일 때는 있는 차선처럼 보인다.
      if (lane.optDouble("c", 0.0) < CONF_MIN) {
        continue;
      }
      boolean inner = (i == 1 || i == 2);
      // 슬롯 1 이 화면 오른쪽이므로 rightLaneLine 을 쓴다. 좌우를 바로잡으면서
      // 여기를 빼먹으면 점선/실선이 반대쪽 차선의 것으로 그려진다.
      int type = inner ? (i == 1 ? typeR : typeL) : -1;

      stroke.setColor(laneColor(p, i, type));
      // 자차선이 굵고 바깥 차선이 가늘다. 중요도는 굵기로 낸다.
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
   * 깜빡이를 안 켰으면 주의색(옆에 차가 있다), 그 방향으로 깜빡이를 켰으면
   * 위험색(지금 들어가면 위험하다). 화살표 같은 별도 기호 없이 차선 자체를
   * 물들이면 시선이 도로에서 떨어지지 않는다.
   */
  private int laneColor(JSONObject p, int index, int type) {
    // 슬롯 0,1 의 y 가 음수이고 이 데이터에서는 y 양수가 왼쪽이다(Projection
    // 참고). 따라서 낮은 인덱스가 화면 오른쪽이다. 투영만 뒤집고 여기를 그대로
    // 두면 차선 위치는 맞는데 사각지대 색이 반대편에 칠해진다.
    boolean leftSide = index >= 2;
    boolean bsd = p.optBoolean(leftSide ? "leftBsd" : "rightBsd", false);
    if (bsd) {
      boolean blinker = p.optBoolean(leftSide ? "leftBlinker" : "rightBlinker", false);
      return blinker ? COL_DANGER : COL_CAUTION;
    }
    // 노란 차선은 실제 노면이 노란색이라는 뜻이므로 그대로 살린다(색 코드 2x).
    if (type >= 20 && type < 30) {
      return COL_ROAD_YELLOW;
    }
    return COL_ROAD;
  }

  private void drawEdges(Canvas canvas, JSONArray edges) {
    if (edges == null) {
      return;
    }
    stroke.setColor(COL_ROAD);
    stroke.setAlpha(255);
    stroke.setStrokeWidth(2f);
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
   * 실제 폭을 거리마다 투영해 좌우 가장자리를 만들고 그 사이를 채우면
   * 가까울수록 넓어지는 띠가 되어 원근이 생긴다.
   *
   * 띠는 자차 아이콘 바로 위에서 시작한다. 아이콘과 띠가 같은 색이라 겹치면
   * 한 덩어리로 보여서 자차 위치도 경로도 읽히지 않는다.
   */
  // 넓게 채우면 HUD 에서 뒤의 실제 도로를 가린다. 자차 폭이 아니라
  // 진행 방향만 알려주는 가는 띠로 둔다.
  private static final float PATH_WIDTH_M = 0.7f;
  private static final float PATH_MAX_X = 35f;   // 너무 멀리 가면 실처럼 가늘어진다

  private void drawPath(Canvas canvas, JSONArray path) {
    if (path == null || path.length() < 2) {
      return;
    }
    float egoTop = egoTop();
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
      float v = proj.screenY(x);
      if (v > egoTop) {
        continue;   // 자차 아이콘에 겹치는 구간은 그리지 않는다
      }
      us[n] = proj.screenX(x, y);
      vs[n] = v;
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
    // 채움은 정보가 아니라 '면'을 만들기 위한 것이다. 뒤의 실제 도로가 비쳐야
    // 하므로 옅게 두고, 형태는 또렷한 외곽선이 책임진다.
    fill.setColor(COL_SYSTEM);
    fill.setAlpha(70);
    canvas.drawPath(scratch, fill);
    fill.setAlpha(255);
    stroke.setColor(COL_SYSTEM);
    stroke.setAlpha(255);
    stroke.setStrokeWidth(3f);
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

  // ---- 2. 인식한 차량 ----

  /**
   * openpilot 이 검출한 차량. 앞차, 그 앞차, 옆차선 차를 모두 같은 모양과
   * 같은 색으로 그린다.
   *
   * 종류마다 색을 바꾸면 색이 다시 의미를 잃는다. 어느 차인지는 화면 위
   * 위치가 알려주므로 색은 "검출된 물체" 하나로 족하다. 급감속할 때만
   * 위험색으로 바뀌어 후미등처럼 동작한다.
   *
   * 차체 바로 아래에 삼각형 커서를 두어 노면 위 어느 지점인지 못박고,
   * 그 아래에 속도를 적는다. 차체만 있으면 거리감이 떠 보인다.
   */
  private static final float LEAD_MIN_PX = 26f;
  private static final float OTHER_MIN_PX = 20f;
  /** 이보다 멀면 지평선에 뭉쳐 앞차와 겹친다. 그 거리에서는 알려줄 것도 없다. */
  private static final float OTHER_MAX_M = 60f;
  /**
   * 속도를 적어 주는 최대 거리.
   *
   * 멀리 있는 차들은 화면에서 몇 픽셀 안에 모이므로 숫자를 다 적으면
   * 지평선이 숫자 더미가 된다. 주된 앞차는 거리와 무관하게 적는다.
   */
  private static final float SPEED_LABEL_MAX_M = 45f;

  /**
   * 콤마가 보내는 v 가 상대속도(vRel, m/s)라고 본다. 자차 속도를 더해야
   * 그 차의 실제 속도가 된다. 송신 쪽이 절대속도를 보낸다면 false 로 바꾼다.
   */
  private static final boolean V_IS_RELATIVE = true;

  private void drawVehicles(Canvas canvas, JSONObject p) {
    // 먼 것부터 그려 가까운 차가 위에 오게 한다.
    JSONArray others = p.optJSONArray("others");
    if (others != null) {
      for (int i = 0; i < others.length(); i++) {
        drawVehicle(canvas, others.optJSONObject(i), false);
      }
    }
    drawVehicle(canvas, p.optJSONObject("lead2"), false);
    drawVehicle(canvas, p.optJSONObject("lead"), true);
  }

  /**
   * 차 한 대.
   *
   * @param primary 주된 앞차. 거리까지 적는다. 나머지는 속도만 적는다.
   */
  private void drawVehicle(Canvas canvas, JSONObject v, boolean primary) {
    if (v == null) {
      return;
    }
    float d = (float) v.optDouble("d", 0);
    float lat = (float) v.optDouble("y", 0);
    if (!proj.visible(d)) {
      return;   // 옆으로 나란히 선 차는 전방 투영으로 그릴 수 없다. 차선 색이 알린다.
    }
    if (!primary && d > OTHER_MAX_M) {
      return;
    }

    float u = proj.screenX(d, lat);
    float base = proj.screenY(d);
    // 실제 차폭 1.8m 를 그 거리에서의 픽셀로 환산한다. 멀 때 점으로 줄어들면
    // 차인지 알 수 없어 최소 폭을 두되, 너무 키우면 먼 차가 가까워 보인다.
    float wpx = Math.max(primary ? LEAD_MIN_PX : OTHER_MIN_PX,
        Math.min(200f, proj.scale(d, 1.8f)));
    float hpx = wpx * 0.62f;
    boolean braking = v.optDouble("a", 0) < -0.5;
    int color = braking ? COL_DANGER : COL_OBJECT;

    // 뒤에서 본 차: 넓은 차체 위에 좁은 지붕.
    float half = wpx * 0.5f;
    float top = base - hpx;
    float shoulder = base - hpx * 0.55f;
    scratch.reset();
    scratch.moveTo(u - half, base);
    scratch.lineTo(u + half, base);
    scratch.lineTo(u + half, shoulder);
    scratch.lineTo(u + half * 0.70f, top);
    scratch.lineTo(u - half * 0.70f, top);
    scratch.lineTo(u - half, shoulder);
    scratch.close();
    fill.setColor(color);
    fill.setAlpha(255);
    canvas.drawPath(scratch, fill);

    // 노면 위 위치를 못박는 삼각형 커서. 꼭짓점이 차를 가리킨다.
    float cw = Math.max(10f, wpx * 0.36f);
    float ch = cw * 0.80f;
    float cTop = base + 3f;
    scratch.reset();
    scratch.moveTo(u, cTop);
    scratch.lineTo(u - cw * 0.5f, cTop + ch);
    scratch.lineTo(u + cw * 0.5f, cTop + ch);
    scratch.close();
    canvas.drawPath(scratch, fill);

    // 속도는 커서 아래. 숫자만 적는다. 단위가 붙으면 화면에 km/h 가 여럿 된다.
    int kmh = vehicleSpeed(v);
    if (kmh >= 0 && (primary || d <= SPEED_LABEL_MAX_M)) {
      String s = String.valueOf(kmh);
      drawLabel(canvas, s, u - labelPaint.measureText(s) * 0.5f, cTop + ch + SZ_LABEL, color);
    }

    // 거리는 주된 앞차만. 모든 차에 붙이면 지평선이 숫자로 덮인다.
    if (primary) {
      String label = String.format("%.0fm", d);
      drawLabel(canvas, label, u - labelPaint.measureText(label) * 0.5f, top - 10f, color);
    }
  }

  /** 도로 위에 놓이는 글자. 검은 테두리를 먼저 그려 배경과 갈라놓는다. */
  private void drawLabel(Canvas canvas, String s, float x, float y, int color) {
    canvas.drawText(s, x, y, labelHalo);
    labelPaint.setColor(color);
    canvas.drawText(s, x, y, labelPaint);
    labelPaint.setColor(COL_ROAD);
  }

  /** 그 차의 실제 속도(km/h). 값이 없으면 -1. */
  private int vehicleSpeed(JSONObject v) {
    if (!v.has("v")) {
      return -1;
    }
    double ms = v.optDouble("v", 0);
    double kmh = V_IS_RELATIVE ? egoSpeed + ms * 3.6 : ms * 3.6;
    return (int) Math.round(Math.max(0, kmh));
  }

  // ---- 3. 자차 ID.4 아이콘 ----

  // ID.4 는 4584 x 1852mm 로 길이:폭이 약 2.5:1 이다. 화면에서 그대로 쓰면
  // 세로를 너무 먹어서 1.65:1 로 줄였지만, 이전의 1.19:1 보다는 차로 읽힌다.
  private static final float EGO_W = 52f;
  private static final float EGO_L = 86f;
  /** 아이콘 위로 띄우는 간격. 경로 띠가 여기서부터 시작한다. */
  private static final float EGO_GAP = 12f;
  /** 안전영역 바닥에서 아이콘 중심까지. 그 아래는 투사되지 않는다. */
  private static final float EGO_BOTTOM_PAD = 16f;

  private float egoCenterY() {
    return proj.safeBottom() - EGO_BOTTOM_PAD - EGO_L * 0.5f;
  }

  private float egoTop() {
    return egoCenterY() - EGO_L * 0.5f - EGO_GAP;
  }

  /**
   * 위에서 본 ID.4.
   *
   * 이 크기(52x86px)에서 차종이 읽히려면 실루엣만으로는 부족하다. ID.4 를
   * 구분해 주는 세 가지를 검은 홈으로 판다. 콤바이너에서 검은색은 투사되지
   * 않으므로 홈은 실제로 '빈 곳'이 되어 밝은 면과 확실히 갈린다.
   *
   *   - 앞범퍼를 가로지르는 라이트바
   *   - 짧은 보닛과 앞으로 밀린 A필러(전기차 비율)
   *   - 뒤로 갈수록 좁아지는 루프와 누운 리어글라스
   *
   * 휠아치는 옆면을 직선으로 두지 않고 앞뒤를 부풀려 표현한다.
   */
  private void drawEgo(Canvas canvas) {
    float cx = proj.centerX();
    float cy = egoCenterY();
    float hw = EGO_W * 0.5f, hl = EGO_L * 0.5f;

    scratch.reset();
    scratch.moveTo(cx - hw * 0.58f, cy + hl);                        // 뒤 범퍼 좌
    scratch.lineTo(cx + hw * 0.58f, cy + hl);                        // 뒤 범퍼 우
    scratch.quadTo(cx + hw * 0.98f, cy + hl * 0.97f, cx + hw, cy + hl * 0.74f);
    scratch.lineTo(cx + hw, cy + hl * 0.34f);                        // 뒤 휠아치
    scratch.lineTo(cx + hw * 0.95f, cy - hl * 0.08f);                // 도어
    scratch.lineTo(cx + hw, cy - hl * 0.44f);                        // 앞 휠아치
    scratch.quadTo(cx + hw * 0.95f, cy - hl * 0.90f, cx + hw * 0.50f, cy - hl);
    scratch.lineTo(cx - hw * 0.50f, cy - hl);                        // 앞 범퍼
    scratch.quadTo(cx - hw * 0.95f, cy - hl * 0.90f, cx - hw, cy - hl * 0.44f);
    scratch.lineTo(cx - hw * 0.95f, cy - hl * 0.08f);
    scratch.lineTo(cx - hw, cy + hl * 0.34f);
    scratch.lineTo(cx - hw, cy + hl * 0.74f);
    scratch.quadTo(cx - hw * 0.98f, cy + hl * 0.97f, cx - hw * 0.58f, cy + hl);
    scratch.close();

    // 검은 테두리를 먼저 두른다. 뒤에 무엇이 겹쳐도 실루엣이 끊기지 않는다.
    stroke.setColor(Color.BLACK);
    stroke.setAlpha(255);
    stroke.setStrokeWidth(8f);
    stroke.setPathEffect(null);
    canvas.drawPath(scratch, stroke);

    fill.setColor(COL_SYSTEM);
    fill.setAlpha(255);
    canvas.drawPath(scratch, fill);

    // 사이드미러. A필러 위치에 둔다.
    float my = cy - hl * 0.26f, mw = hw * 0.22f, mh = hl * 0.055f;
    canvas.drawRect(cx - hw - mw, my - mh, cx - hw * 0.94f, my + mh, fill);
    canvas.drawRect(cx + hw * 0.94f, my - mh, cx + hw + mw, my + mh, fill);

    fill.setColor(Color.BLACK);

    // 앞범퍼 라이트바. ID.4 를 정면에서 구분해 주는 표식이다.
    canvas.drawRect(cx - hw * 0.40f, cy - hl * 0.90f,
        cx + hw * 0.40f, cy - hl * 0.83f, fill);

    // 앞유리. 위가 좁고 아래가 넓다(앞쪽이 위).
    scratch.reset();
    scratch.moveTo(cx - hw * 0.44f, cy - hl * 0.52f);
    scratch.lineTo(cx + hw * 0.44f, cy - hl * 0.52f);
    scratch.lineTo(cx + hw * 0.62f, cy - hl * 0.14f);
    scratch.lineTo(cx - hw * 0.62f, cy - hl * 0.14f);
    scratch.close();
    canvas.drawPath(scratch, fill);

    // 리어글라스. 루프가 뒤로 좁아지므로 위쪽이 넓다.
    scratch.reset();
    scratch.moveTo(cx - hw * 0.60f, cy + hl * 0.24f);
    scratch.lineTo(cx + hw * 0.60f, cy + hl * 0.24f);
    scratch.lineTo(cx + hw * 0.50f, cy + hl * 0.62f);
    scratch.lineTo(cx - hw * 0.50f, cy + hl * 0.62f);
    scratch.close();
    canvas.drawPath(scratch, fill);
  }

  // ---- 4. 제한속도 표지 / 방지턱 ----

  /**
   * 화면 우상단 표지 하나로 제한속도를 모은다.
   *
   * 예전에는 좌상단에 "제한 60", 우상단에 과속카메라 표지가 따로 있었다.
   * 같은 성격의 숫자가 두 곳에 흩어지면 좌상단이 두 줄이 되고 속도 숫자의
   * 존재감이 죽는다. 자리는 하나로 두고, 단속 지점일 때만 위험색 테두리와
   * 남은 거리를 붙인다.
   *
   * 흰 원을 채우지 않고 테두리만 그린다. 콤바이너에서 지름 60px 의 흰 면은
   * 야간에 화면에서 가장 밝은 물체가 되어 도로를 가린다.
   */
  /** 표지 아래 문구는 표지 중앙에 맞추되 화면 밖으로 나가지 않게 당긴다. */
  private float labelLeft(float centerX, float width, int w) {
    return Math.min(centerX - width * 0.5f, w - 16f - width);
  }

  private void drawSign(Canvas canvas, JSONObject p, int w) {
    int camera = p.optInt("camera", 0);
    int dist = p.optInt("cameraDist", 0);
    boolean section = p.optBoolean("cameraSection", false);
    int limit = p.optInt("limit", 0);
    int bump = p.optInt("bumpDist", 0);

    boolean enforced = camera > 0 && dist > 0;
    int value = enforced ? camera : limit;
    float cx = w - 62f, cy = proj.safeTop() + 42f;
    float labelY = cy + 58f;

    if (value > 0) {
      stroke.setColor(enforced ? COL_DANGER : COL_ROAD);
      stroke.setAlpha(255);
      stroke.setStrokeWidth(enforced ? 7f : 4f);
      stroke.setPathEffect(null);
      canvas.drawCircle(cx, cy, 28f, stroke);

      signPaint.setColor(COL_ROAD);
      String s = String.valueOf(value);
      canvas.drawText(s, cx - signPaint.measureText(s) * 0.5f, cy + 11f, signPaint);

      if (enforced) {
        labelPaint.setColor(COL_ROAD);
        String label = (section ? "구간 " : "") + dist + "m";
        canvas.drawText(label, labelLeft(cx, labelPaint.measureText(label), w), labelY, labelPaint);
        labelY += 26f;
      }
    }

    if (bump > 0) {
      labelPaint.setColor(COL_CAUTION);
      String label = "방지턱 " + bump + "m";
      canvas.drawText(label, labelLeft(cx, labelPaint.measureText(label), w),
          value > 0 ? labelY : cy, labelPaint);
      labelPaint.setColor(COL_ROAD);
    }
  }

  // ---- 5. 속도와 링크 상태 ----

  /**
   * 좌상단.
   *
   * 정상일 때는 속도 하나만 크게 둔다. 제한속도는 우상단 표지로 옮겼고,
   * 수신 카운터는 개발용 잡음이라 평소에는 그리지 않는다.
   *
   * 신호가 끊기면 속도를 흐리게 하지 않고 아예 지운다. 흐린 숫자는
   * 콤바이너에서 "의심스러운 값"이 아니라 그냥 안 보이는 값이고, 운이 나쁘면
   * 읽히는데 낡은 값이다.
   */
  private void drawStatus(Canvas canvas, JSONObject p) {
    float top = proj.safeTop();
    float baseline = top + SZ_SPEED;

    if (stale()) {
      long ago = lastPacketMs == 0 ? 0
          : (android.os.SystemClock.elapsedRealtime() - lastPacketMs) / 1000;
      // 속도 숫자보다 작게 둔다. 크게 외칠수록 잘 읽히는 게 아니라, 도로를
      // 가리고 시선을 오래 잡는다. 색만으로도 평소와 다른 상태인 줄 안다.
      speedPaint.setTextSize(SZ_ALERT);
      speedPaint.setColor(COL_DANGER);
      canvas.drawText("신호 끊김", 16f, top + SZ_ALERT, speedPaint);
      speedPaint.setTextSize(SZ_SPEED);
      speedPaint.setColor(COL_ROAD);

      labelPaint.setColor(COL_DANGER);
      canvas.drawText(ago + "초 · " + linkState, 16f, top + SZ_ALERT + 26f, labelPaint);
      labelPaint.setColor(COL_ROAD);
      return;
    }

    int speed = p.optInt("speed", 0);
    int limit = p.optInt("limit", 0);
    // 제한속도를 넘으면 숫자 자체가 주의색이 된다. 눈이 이미 가 있는 곳에서
    // 알려주는 것이라 새로 볼 것이 늘지 않는다.
    boolean over = limit > 0 && speed > limit + OVER_MARGIN;
    speedPaint.setColor(over ? COL_CAUTION : COL_ROAD);
    unitPaint.setColor(over ? COL_CAUTION : COL_ROAD);

    String num = String.valueOf(speed);
    canvas.drawText(num, 16f, baseline, speedPaint);
    canvas.drawText("km/h", 16f + speedPaint.measureText(num) + 8f, baseline, unitPaint);

    speedPaint.setColor(COL_ROAD);
    unitPaint.setColor(COL_ROAD);
  }
}
