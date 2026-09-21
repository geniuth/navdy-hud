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
  /** 내비/커브 감속 안내. 경고가 아니라 '곧 이렇게 된다'는 예고다. */
  private static final int COL_NAV         = Color.rgb(255, 150, 40);

  // ---- 타이포: 속도가 1차 정보다 ----

  private static final float SZ_SPEED = 58f;   // 주행 속도. 힐끗 봐서 읽혀야 한다
  private static final float SZ_UNIT  = 20f;   // 단위는 한 번 익히면 안 읽는다
  private static final float SZ_LABEL = 20f;   // 거리, 상태 문구
  private static final float SZ_SIGN  = 30f;   // 표지 안 숫자
  private static final float SZ_ALERT = 38f;   // 상태 경고. 속도보다 작게 둔다
  private static final float SZ_SET   = 22f;   // 인게이지 속도. 속도에 딸린 값
  private static final float SZ_NAV   = 21f;   // 커브/경로 감속 예고

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
  private final Paint setPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint navPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    setPaint.setColor(COL_SYSTEM);
    setPaint.setTextSize(SZ_SET);
    setPaint.setFakeBoldText(true);

    navPaint.setColor(COL_NAV);
    navPaint.setTextSize(SZ_NAV);
    navPaint.setFakeBoldText(true);
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
    advanceTravel();
    if (p == null) {
      labelPaint.setColor(COL_ROAD);
      canvas.drawText("링크: " + linkState, 16f, proj.safeTop() + SZ_LABEL, labelPaint);
      return;
    }

    // 링크가 끊겨도 마지막 화면이 그대로 남으면, 주행 중에 낡은 차선과 앞차를
    // 실시간인 것처럼 보게 된다. 오래된 데이터면 움직이는 것들은 전부 감춘다.
    if (!stale()) {
      if (SHOW_EDGES) {
        drawEdges(canvas, p.optJSONArray("edges"));
      }
      drawLanes(canvas, p.optJSONArray("lanes"), p);
      drawPath(canvas, p.optJSONArray("path"), p.optBoolean("enabled", false));
      drawVehicles(canvas, p);
      drawSign(canvas, p, w);
    }
    drawStatus(canvas, p);

    if (stale()) {
      // 다음 패킷이 없으면 onDraw 가 다시 안 불리므로, 끊긴 뒤에도 표시를
      // 지우려면 스스로 한 번 더 그려야 한다.
      postInvalidateDelayed(500);
    }
  }

  // ---- 1. 차선 / 도로경계 / 경로 ----

  // ---- 차선 원근 ----
  //
  // 화면 좌표에서 굵기를 고정하고 DashPathEffect 로 점선을 만들면, 먼 차선도
  // 가까운 차선과 같은 굵기로 그려지고 점선 간격도 거리와 무관해진다. 그래서
  // 도로가 누워 보이지 않고 벽처럼 선다.
  //
  // 대신 월드 좌표(m)에서 일정 간격으로 다시 샘플링해 조각마다 그 거리의
  // 굵기로 긋고, 점선도 실제 노면 규격(m)으로 끊는다. 원근은 투영이 알아서
  // 만들어 준다.

  /** 실제 노면 표시 폭(m). 자차선을 조금 굵게 둬 중요도를 낸다. */
  private static final float MARK_INNER_M = 0.16f;
  private static final float MARK_OUTER_M = 0.11f;
  /** 도심 백색 점선 규격: 표시 3m + 공백 5m. */
  private static final float DASH_ON_M = 3.0f;
  private static final float DASH_OFF_M = 5.0f;
  /** 너무 가늘면 콤바이너에서 사라지고, 너무 굵으면 가까운 쪽이 뭉갠다. */
  private static final float MARK_MIN_PX = 2.0f;
  private static final float MARK_MAX_PX = 14f;
  /** 월드에서 이 간격으로 잘라 그린다. 곡선과 굵기 변화가 계단지지 않을 만큼. */
  private static final float STEP_M = 0.75f;

  /**
   * 지금까지 달린 거리(m).
   *
   * 차선 점선은 차량 기준 좌표로 오기 때문에, x 만으로 점선 위상을 정하면
   * 달리는 중에도 점선이 화면에 붙박여 있는다. 속도를 적분해 위상을 밀어
   * 실제처럼 다가오게 한다.
   */
  private float travelM = 0f;
  private long travelMs = 0;

  private void advanceTravel() {
    long now = android.os.SystemClock.elapsedRealtime();
    if (travelMs != 0) {
      float dt = Math.min(0.5f, (now - travelMs) / 1000f);
      travelM += egoSpeed / 3.6f * dt;
      // 주기의 정수배로 접어 float 정밀도가 떨어지는 것을 막는다.
      float period = DASH_ON_M + DASH_OFF_M;
      if (travelM > period * 1000f) {
        travelM -= period * 1000f;
      }
    }
    travelMs = now;
  }

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
      // 슬롯 1 이 왼쪽 자차선이므로 leftLaneLine 을 쓴다. laneColor 의 좌우와
      // 같은 매핑이어야 점선/실선이 제 차선에 그려진다.
      int type = inner ? (i == 1 ? typeL : typeR) : -1;

      stroke.setColor(laneColor(p, i, type));
      // 종류를 아는 자차선만 점선/실선을 구분한다. 바깥 차선은 정보가 없으므로
      // 실선으로 그려 없는 노면 표시를 지어내지 않는다.
      boolean dash = type >= 0 && type % 10 == 0;
      drawLaneLine(canvas, lane.optJSONArray("p"), dash,
          inner ? MARK_INNER_M : MARK_OUTER_M);
    }
  }

  /**
   * 차선 색. 기본은 흰색이고, 사각지대에 차가 있을 때만 그 쪽 차선이 경고색이 된다.
   *
   * 깜빡이를 안 켰으면 주의색(옆에 차가 있다), 그 방향으로 깜빡이를 켰으면
   * 위험색(지금 들어가면 위험하다). 화살표 같은 별도 기호 없이 차선 자체를
   * 물들이면 시선이 도로에서 떨어지지 않는다.
   */
  private int laneColor(JSONObject p, int index, int type) {
    // laneLines 는 왼쪽부터 0,1,2,3 이고 y 음수가 왼쪽이다(기기 실측).
    // 따라서 슬롯 0,1 이 왼쪽 차선이다. 여기가 >= 2 로 돼 있어서 사각지대
    // 경고가 늘 반대편에 칠해졌다.
    boolean leftSide = index <= 1;
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

  /**
   * 차선 한 줄. 월드 좌표에서 잘라 조각마다 그 거리의 굵기로 긋는다.
   *
   * @param dash  점선이면 true. 노면 규격(3m+5m)대로 끊고, 주행거리만큼
   *              위상을 밀어 실제처럼 다가오게 한다.
   * @param markM 실제 노면 표시 폭(m).
   */
  private void drawLaneLine(Canvas canvas, JSONArray pts, boolean dash, float markM) {
    if (pts == null || pts.length() < 2) {
      return;
    }
    final float period = DASH_ON_M + DASH_OFF_M;
    float px = Float.NaN, py = 0f;
    for (int i = 0; i < pts.length(); i++) {
      JSONArray pt = pts.optJSONArray(i);
      if (pt == null || pt.length() < 2) {
        continue;
      }
      float x = (float) pt.optDouble(0, 0), y = (float) pt.optDouble(1, 0);
      if (Float.isNaN(px)) {
        px = x;
        py = y;
        continue;
      }
      float dx = x - px, dy = y - py;
      float len = (float) Math.sqrt(dx * dx + dy * dy);
      int steps = Math.max(1, (int) Math.ceil(len / STEP_M));
      for (int k = 0; k < steps; k++) {
        float t0 = k / (float) steps, t1 = (k + 1) / (float) steps;
        float ax = px + dx * t0, ay = py + dy * t0;
        float bx = px + dx * t1, by = py + dy * t1;
        float mx = (ax + bx) * 0.5f;
        if (!proj.visible(mx) || mx > Projection.MAX_X) {
          continue;
        }
        if (dash) {
          // 위상은 노면에 고정돼야 한다. 차량 기준 거리 mx 에 주행거리를 더하면
          // 달릴수록 패턴이 다가온다.
          float phase = (mx + travelM) % period;
          if (phase < 0f) {
            phase += period;
          }
          if (phase >= DASH_ON_M) {
            continue;
          }
        }
        float wpx = proj.scale(mx, markM);
        stroke.setStrokeWidth(Math.max(MARK_MIN_PX, Math.min(MARK_MAX_PX, wpx)));
        canvas.drawLine(proj.screenX(ax, ay), proj.screenY(ax),
            proj.screenX(bx, by), proj.screenY(bx), stroke);
      }
      px = x;
      py = y;
    }
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
   * 인게이지 중에는 무지개, 해제 중에는 흰색이다. 색이 곧 상태라 따로 읽을
   * 글자가 없고, 시선이 도로에서 떨어지지 않는다.
   */
  // 넓게 채우면 HUD 에서 뒤의 실제 도로를 가린다. 자차 폭이 아니라
  // 진행 방향만 알려주는 가는 띠로 둔다.
  private static final float PATH_WIDTH_M = 0.7f;
  private static final float PATH_MAX_X = 35f;   // 너무 멀리 가면 실처럼 가늘어진다

  private void drawPath(Canvas canvas, JSONArray path, boolean engaged) {
    if (path == null || path.length() < 2) {
      return;
    }
    // 자차 아이콘을 없앴으므로 띠는 안전영역 바닥까지 내려온다.
    float bottom = proj.safeBottom();
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
      if (v > bottom) {
        continue;   // 투사되지 않는 아래 여백
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
    float near = vs[0], far = vs[n - 1];
    android.graphics.Shader shader = engaged ? rainbow(near, far) : null;

    // 해제 중에는 채우지 않는다. 반투명 흰색은 결국 회색이고, 회색은 주간
    // 콤바이너에서 가장 먼저 사라진다. 형태는 외곽선이 책임진다.
    // 인게이지 중에만 무지개로 채워, 채움 자체가 상태 표시가 되게 한다.
    if (engaged) {
      fill.setShader(shader);
      fill.setAlpha(120);
      canvas.drawPath(scratch, fill);
      fill.setShader(null);
      fill.setAlpha(255);
    }

    stroke.setShader(shader);
    stroke.setColor(COL_ROAD);
    stroke.setAlpha(255);
    stroke.setStrokeWidth(3f);
    stroke.setPathEffect(null);
    canvas.drawPath(scratch, stroke);
    stroke.setShader(null);
  }

  /**
   * 인게이지 중 경로 띠에 쓰는 무지개.
   *
   * 색 자체에 뜻은 없다. "지금 openpilot 이 몰고 있다"를 한눈에 알리는 표시다.
   * 흐르는 느낌을 주려고 시간에 따라 색상을 밀어 준다. 어차피 10Hz 로 다시
   * 그리므로 추가 비용이 없다.
   *
   * 색상은 한 바퀴 다 돌리지 않는다. 360도를 그대로 쓰면 남색과 보라가
   * 섞이는데, 콤바이너에서 그 구간은 휘도가 낮아 띠가 중간중간 끊겨 보인다.
   * 노랑~청록(40~200도)만 왕복시키면 어디서 끊어도 밝고, 왕복이라 이음매가
   * 생기지 않는다.
   */
  private static final int RAINBOW_STEPS = 9;
  private static final float HUE_MIN = 40f;    // 주황빛 노랑
  private static final float HUE_MAX = 200f;   // 청록
  private final int[] rainbowColors = new int[RAINBOW_STEPS];
  private final float[] rainbowStops = new float[RAINBOW_STEPS];
  private final float[] hsv = new float[]{0f, 0.80f, 1f};

  private android.graphics.Shader rainbow(float nearY, float farY) {
    // 2 초에 한 왕복. 더 빠르면 시선을 끌고, 더 느리면 멈춰 보인다.
    float phase = (android.os.SystemClock.elapsedRealtime() % 2000L) / 2000f;
    for (int i = 0; i < RAINBOW_STEPS; i++) {
      float t = i / (float) (RAINBOW_STEPS - 1);
      // 삼각파: 0->1->0. 양 끝 색이 같아 반복해도 이음매가 없다.
      float u = (t + phase) % 1f;
      float tri = u < 0.5f ? u * 2f : (1f - u) * 2f;
      hsv[0] = HUE_MIN + (HUE_MAX - HUE_MIN) * tri;
      rainbowColors[i] = Color.HSVToColor(hsv);
      rainbowStops[i] = t;
    }
    return new android.graphics.LinearGradient(0f, nearY, 0f, farY,
        rainbowColors, rainbowStops, android.graphics.Shader.TileMode.CLAMP);
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

    // 인게이지 속도. 주행 속도에 딸린 값이라 바로 아래에 작게 둔다.
    // 해제 중에는 지운다 - 안 쓰이는 설정값이 계속 떠 있으면 지금 그 속도로
    // 가고 있는 줄 안다.
    float y = baseline + SZ_SET + 6f;
    int set = p.optInt("set", 0);
    if (p.optBoolean("enabled", false) && set > 0) {
      canvas.drawText("SET " + set, 16f, y, setPaint);
    }

    drawNav(canvas, p, y + SZ_NAV + 6f);
  }

  /**
   * 커브/경로 감속 예고. 속도 아래 한 줄에 주황색으로 모은다.
   *
   *   VT 45     시야 커브에서 낼 수 있는 속도
   *   ↰ 120m    다음 경로 안내와 남은 거리
   *   ▼ 50      최종 목표속도
   *
   * 셋 다 "곧 느려진다"는 같은 얘기라 한 줄에 묶었다. 경고색(노랑/빨강)을
   * 쓰지 않는 이유는 지금 위험한 게 아니라 예고이기 때문이다.
   */
  private void drawNav(Canvas canvas, JSONObject p, float y) {
    int vturn = p.optInt("vTurnSpeed", 0);
    int turn = p.optInt("turnInfo", -1);
    int turnDist = p.optInt("turnDist", 0);
    int desired = p.optInt("desiredSpeed", 0);

    StringBuilder sb = new StringBuilder();
    if (vturn > 0) {
      sb.append("VT ").append(vturn);
    }
    String arrow = turnArrow(turn);
    if (arrow != null && turnDist > 0) {
      if (sb.length() > 0) {
        sb.append("  ");
      }
      sb.append(arrow).append(' ').append(turnDist).append('m');
    }
    // 목표속도는 위 둘 중 하나라도 있을 때만 적는다. 평소 주행에서는
    // 제한속도와 같은 값이 계속 떠 있어 읽을 것만 늘린다.
    if (desired > 0 && sb.length() > 0) {
      sb.append("  ▼ ").append(desired);
    }
    if (sb.length() == 0) {
      return;
    }
    canvas.drawText(sb.toString(), 16f, y, navPaint);
  }

  /**
   * 경로 안내 기호. carrot 의 xTurnInfo 규약을 따른다.
   * 1 좌회전 2 우회전 3 좌차선변경 4 우차선변경 5 로터리 6 톨게이트 7 도착/유턴.
   */
  private String turnArrow(int turn) {
    switch (turn) {
      case 1: return "↰";   // 좌회전
      case 2: return "↱";   // 우회전
      case 3: return "←";   // 좌차선변경
      case 4: return "→";   // 우차선변경
      case 5: return "↻";   // 로터리
      case 6: return "TG";
      case 7: return "↩";   // 도착/유턴
      default: return null;
    }
  }
}
