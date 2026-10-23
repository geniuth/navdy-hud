# Navdy HUD — 콤마 주행 정보 표시

Navdy HUD 에서 직접 도는 안드로이드 앱. 콤마(openpilot)가 블루투스로 보내는
주행 텔레메트리를 받아 차선·앞차·과속카메라·사각지대를 HUD 에 그린다.

폰을 거치지 않는다. Navdy 가 루팅된 안드로이드라 우리 앱을 올릴 수 있어서,
콤마 → 블루투스 → Navdy 로 바로 간다.

## 기기

ADB 로 직접 확인한 값이다.

```
product   navdy_hud_6dl       Android 5.1.1 (API 22)
SoC       Freescale i.MX6 armv7l,  커널 3.14
패널      640 x 480  60Hz  32bpp   (네이티브, 스케일링 없음)
density   160 dpi
RAM       1GB            /data 여유 약 350MB
root      완전 루팅 (adbd as root, /sbin/su)
```

콤바이너 HUD 라 **검은색은 투사되지 않는다**. 배경은 항상 검게 두고 밝은
요소만 그린다. 회색 계열은 주간에 거의 사라지므로 쓰지 않는다.

**상하 1/6 은 실제로 투사되지 않는다.** 그 밖으로 그린 것은 운전자에게 닿지
않으므로 도로도 글자도 안쪽에만 배치한다(`Projection.SAFE_MARGIN`).

### USB 모드

Navdy 는 ADB 와 대용량저장을 배타적으로 내보낸다.

| 모드 | PID | 조건 |
|---|---|---|
| ADB | `VID_9886&PID_0001` | **부팅이 끝난 뒤** USB 를 꽂는다 |
| 대용량저장 | `VID_9886&PID_0003` | 부팅 중에 꽂혀 있으면 이쪽으로 고착 |

ADB 가 안 잡히면 USB 를 뽑고, HUD 화면이 평소대로 올라온 뒤 다시 꽂는다.
12V 전원은 항상 필요하다. PC USB 만으로는 부팅되지 않는다(저장소가
`No Media` 로 뜬다).

## 통신

콤마가 클라이언트, 이 앱이 RFCOMM 서버다.

```
서비스   CommaHUD
UUID     b8949674-c91b-4c36-a9d0-c24c644826a0
프레임   [타입 2B][길이 4B][내용]   빅엔디안
           1  텔레메트리 JSON
           2  PING
```

콤마 쪽 구현은 `openpilot/selfdrive/eon_cluster/bt_link.py` (geniuth/openpilot
carrot-wip). UUID 가 양쪽에서 같아야 한다.

패킷은 약 1.6KB, 10Hz 로 약 126kbps. 원본 텔레메트리(5.7KB)에서 HUD 가 그리는
19개 항목만 남기고 폴리라인을 13점으로 솎은 것이다.

### 좌표계

콤마가 보내는 값은 차량 기준이다. `x` 는 전방 거리(m), `y` 는 횡방향(m)이고
**음수가 왼쪽**이다. 실측으로 확인했다:

```
lanes[0] y=-4.89 (바깥좌)   lanes[1] y=-1.73 (좌)
lanes[2] y=+1.74 (우)       lanes[3] y=+4.51 (바깥우)
```

차선 슬롯은 0=바깥좌, 1/2=자차선, 3=바깥우로 고정이고 각 슬롯에 신뢰도 `c` 가
붙는다. 정차·저속에서는 `c` 가 0 으로 떨어져 차선이 안 그려지는데 버그가
아니다. 모델이 실제로 차선을 확신하지 못하는 상태다.

### 차선 종류

`laneL` / `laneR` 은 카메라가 읽은 실제 노면 표시다. 모델 차선(`lanes`)에는
없는 정보라 여기서만 점선/실선과 색을 알 수 있다. VW MEB 는 `MEB_Camera_01`
에서 읽어 채운다.

```
값 = 색*10 + 종류
  종류  0 점선 / 1 실선 / 2 미상
  색    +10 흰 / +20 노랑 / +30 파랑      예) 21 = 실선 노란색
  -1    지원하지 않는 차
```

바깥 차선(슬롯 0, 3)은 종류 정보가 없어 실선으로 그린다. 없는 노면 표시를
지어내지 않기 위해서다.

### 사각지대

별도 기호 없이 **차선 자체를 물들인다**. 시선이 도로에서 떨어지지 않는다.

| 상황 | 그 쪽 차선 |
|---|---|
| 사각지대 비었음 | 흰색(평소) |
| 차 있음, 깜빡이 off | 노랑 |
| 차 있음, 그 방향 깜빡이 on | 빨강 |

## 빌드

Gradle 을 쓰지 않는다. Android SDK 도구만으로 빌드한다.

```powershell
.\build.ps1
```

필요한 것: JDK 17, Android SDK (build-tools 36.0.0, platforms/android-34).

`javac` 에 **`-encoding UTF-8` 이 반드시 있어야 한다**. 윈도 기본 코드페이지
(949)로 읽으면 한글 주석에서 컴파일이 깨진다. 그리고 네이티브 도구가 안내문을
stderr 로 내보내므로 실패 판정은 종료코드로만 한다.

## 실행

```powershell
adb install -r build\CommaHUD.apk
adb shell am start -n kr.geniu.navdyhud/.HudActivity                  # 콤마 연결
adb shell am start -n kr.geniu.navdyhud/.HudActivity --ez demo true   # 데모
```

**다이얼을 1.2초 누르면 HUD 와 순정 Navdy 화면(지도·대시)을 오간다.** 어느 쪽에
있든 같은 동작이다. 접근성 서비스(`DialService`)가 전면 앱과 상관없이 다이얼을
보기 때문이다. 순정 메뉴의 클릭·회전은 그대로 동작한다.

처음 한 번 adb 로 켜야 한다(재부팅해도 유지):

```powershell
adb shell settings put secure enabled_accessibility_services kr.geniu.navdyhud/kr.geniu.navdyhud.DialService
adb shell settings put secure accessibility_enabled 1
```

서비스가 꺼져 있으면 HUD 에서 순정으로 가는 길만 남고, 대신 3분 뒤 HUD 로
자동 복귀한다.
adb 로 직접 부를 수도 있다:

```powershell
adb shell am start -n com.navdy.hud.app/.ui.activity.MainActivity
```

### USB 로 업데이트 (adb 없이)

집에서는 12V 를 물릴 수 없어 나브디가 부팅하지 않고, 그러면 adb 도 붙지 않는다.
대신 USB 만 꽂으면 대용량저장소로 잡힌다. 그 드라이브가 기기 안에서는 `/maps`
다(vfat, 누구나 쓰기 가능).

1. 집에서 USB 로 꽂고, 잡힌 드라이브 최상위에 `CommaHUD.apk` 를 복사한다
2. 차에서 전원이 들어오면 부팅 10초 뒤 설치 화면이 뜬다
3. 다이얼로 확인한다 — 아래로 눌러 버튼 줄, 오른쪽이 INSTALL, 클릭이 확정

취소해도 90초 뒤 HUD 는 올라온다. 이미 설치한 버전이면 화면이 뜨지 않는다
(`versionCode` 비교. build.ps1 이 빌드할 때마다 올린다).

**선행 조건**: '알 수 없는 소스' 가 켜져 있어야 한다. 한 번만 하면 secure
설정이라 재부팅해도 남는다.

```powershell
adb shell settings put secure install_non_market_apps 1
```

설치까지 자동으로 하려면 root 가 필요한데, `/sbin/su` 가 `root:shell` 이라
앱(u0_aNN)은 쓸 수 없다. 그래서 확인 한 번은 남는다.

### 데모 모드

콤마 없이 모든 화면 상태를 40초에 순회한다. 사각지대 빨간 표시는 깜빡이를 켠
채 옆 차선에 차가 있어야 하고 과속카메라는 단속 구간에 들어가야 뜨는데, 그런
상태를 실차에서 우연히 만나길 기다리면 디자인 작업이 몇 주 늘어진다.

콤마가 보내는 것과 **같은 모양의 패킷**을 만들어 같은 렌더 경로로 넣는다.
데모 전용 그리기를 따로 두면 여기서 예쁜 것이 실차에서 다르게 나온다.

| # | 장면 | 확인 대상 |
|---|---|---|
| 1 | 정속 + 앞차 접근 | 차선·경로·앞차 크기 변화 |
| 2 | 앞차 급감속 | 후미등 빨강 |
| 3 | 좌측 깜빡이 + 사각지대 빔 | 노란 화살표 |
| 4 | 좌측 깜빡이 + 사각지대 점유 | 빨간 화살표 + "차량" |
| 5 | 과속카메라 200→40m | 원형 표지 + 거리 |
| 6 | 구간단속 | "구간" 접두 |
| 7 | 방지턱 + 우측 사각지대 | 방지턱 표시, 좌우 대칭 |
| 8 | 송신 중단 | 낡은 데이터 차단 |
| 9 | 인게이지 해제 | 경로 띠가 흰 외곽선만 |
| 10 | 커브 감속 + 좌회전 안내 | 주황색 한 줄 |

## 순정 대시 모드 (ID.4 프로필)

나브디 순정 대시 화면의 항목은 `/maps/nmirror_obd.js`(nMirror) 가 정한다.
원래 파일은 현대·기아 디젤용(DPF, 미션온도)이라 ID.4 에서는 전부 `--` 였다.
[dash/nmirror_obd_id4.js](dash/nmirror_obd_id4.js) 로 바꿨다.

| 위치 | 항목 | 주소·DID |
|---|---|---|
| 속도 위 | 기어 P/R/N/D/B | 게이트웨이 `17FC0076` `210E` |
| 속도 | 차속 | BMS `17FC007B` `F40D` |
| 왼쪽 | 시각 · 배터리 온도 · 적산거리 · 내비 도착 | 내장 / BMS `2A0B` / 게이트웨이 `295A` / 내장 |
| 오른쪽 | 잔량(계기판 환산) · 출력(kW) · 고전압 · 12V | BMS `028C` / `1E3D`×`1E3B` / `1E3B` / `ATRV` |

모두 이 차에서 응답을 직접 받아 확인했다(2026-10-23, 잔량 74%·376V·21℃·74,651km).

### 왜 이렇게 복잡한가

나브디 OBD 칩(STN1110 v3.2.0)에 직접 명령을 보내 확인한 사실:

- ID.4 의 쓸 만한 값은 VW 진단 주소(`0x17FC00xx`, 29비트)에만 있다. ISO 표준
  주소(`18DA7BF1`)로는 답하지 않는다
- 여기에 닿으려면 칩 설정 네 가지가 모두 필요하다: 프로토콜 7 고정, 우선순위
  17(`ATCP17`), 수신필터 개방(`ATCF/ATCM 0`), 두 프레임 응답용 이어받기 신호
  (`ATFCSH17FC007B`)
- 표준 OBD(`010D` 등)는 우선순위 18 이어야 한다. 둘은 공존할 수 없어서 속도까지
  BMS 의 `F40D` 로 읽는다
- 이 칩은 8자리 헤더(`ATSH 17FC007B`)를 거부한다. 6자리만 쓰고 우선순위는
  `ATCP` 가 맡는다
- 수신필터는 프로토콜이 확정된 뒤에만 받아들여진다. 그래서 `ATSP7` 이 맨 앞이다
- 외기온도는 11비트 공조 주소(`0x746`)에만 있어 이 방식으로는 못 읽는다.
  표준 `0146` 은 ID.4 가 지원하지 않는다(`7F 01 11`)

nMirror 는 `Header` 칸을 `ATSH` 로만 보내므로, 위 설정을 AT 명령 '항목' 으로 넣어
20초마다 보낸다(`requiredPids` 맨 앞 → 가장 먼저 실행). 지도 화면에서는
`bindMapRight` 의 항목만 요청하므로 설정과 속도를 거기에도 넣었다.

칩과 직접 대화하는 진단 도구는 [tools/ObdProbe.java](tools/ObdProbe.java). 순정 HUD
앱을 내린 뒤(`am force-stop com.navdy.hud.app`) 돌린다 — 같은 소켓을 두 쪽이
쓰면 명령이 섞인다.

적용과 복원:

```powershell
adb push dash
mirror_obd_id4.js /maps/nmirror_obd.js
adb push dash
mirror_obd_id4.js /maps/nmirror_obd_minimal.js
adb shell am force-stop com.navdy.hud.app
# 원래대로: /maps 의 *.hyundai.bak 을 원래 이름으로 되돌린다
```

남은 확인: 주행 중 출력이 + 로 나오는지(주차 중 소비 -1.9A 로 부호를 정했다),
대시 속도가 계기판과 맞는지.

## 구성

| 파일 | 역할 |
|---|---|
| `CommaLink.java` | RFCOMM 서버. 리스너를 계속 열어둬 SDP 레코드를 유지한다 |
| `Projection.java` | 월드(m) → 화면(px). `u = cx + f·y/x`, `v = 지평선 + f·camH/x` |
| `HudView.java` | 렌더링 전부 |
| `DemoSource.java` | 합성 데이터 |
| `HudActivity.java` | 진입점. `--ez demo true` 로 데모 선택 |

### 투영 상수

`Projection.java` 의 값은 실기기 화면을 보며 맞춘 것이다.

```
CAM_H   2.2m    가상 카메라 높이. 화면 구도를 정하는 값이지 실제 높이가 아니다
MAX_X   50m     더 멀리 그리면 지평선에 뭉쳐 근거리가 눌린다
MIN_X   2.0m    더 가까우면 u 가 발산해 화면 밖으로 나간다
지평선  0.30h
초점    0.625w
```

1.2m / 90m 로 뒀을 때는 3~90m 가 화면 y=168~323 에만 몰려 아래 30% 가 비고
도로가 공중에 뜬 것처럼 보였다.

## 화면

실기기(640x480) 캡처다. 콤바이너 HUD 라 검은 부분은 투사되지 않으므로 실제로는
밝은 요소만 유리에 뜬다. 상하 1/6 이 비어 있는 것은 그 영역이 투사되지 않기
때문이다.

| 정속 주행 | 앞차 급감속 |
|---|---|
| ![](docs/captures/s1-cruise.png) | ![](docs/captures/s2-lead-braking.png) |
| 좌측 점선 / 우측 실선, 경로 띠, 과속카메라 표지 | 앞차가 감속하면 붉게 |

사각지대는 별도 기호 없이 그 쪽 차선을 물들인다.

| 깜빡이 off — 노랑 | 그 방향 깜빡이 on — 빨강 |
|---|---|
| ![](docs/captures/s3-bsd-yellow.png) | ![](docs/captures/s4-bsd-red.png) |
| 옆에 차가 있다 | 지금 들어가면 위험하다 |

| 구간단속 | 신호 끊김 |
|---|---|
| ![](docs/captures/s6-section.png) | ![](docs/captures/s8-lost.png) |
| 제한속도와 남은 거리 | 낡은 정보를 실시간으로 착각하지 않도록 전부 감춘다 |

경로 띠 색이 곧 인게이지 상태다. 따로 읽을 글자를 두지 않았다.

| 인게이지 — 무지개 | 해제 — 흰 외곽선 |
|---|---|
| ![](docs/captures/s1-cruise.png) | ![](docs/captures/s9-disengaged.png) |
| 채움이 있고 색이 흐른다 | 채우지 않는다. 반투명 흰색은 결국 회색이고 주간에 사라진다 |

커브·경로 감속은 속도 아래 한 줄로 모은다.

| 커브 감속 + 좌회전 안내 |
|---|
| ![](docs/captures/s10-nav-turn.png) |
| `VT 45` 커브 한계속도, `↰ 170m` 다음 안내, `▼ 45` 목표속도. 경고색이 아닌 주황인 이유는 지금 위험한 게 아니라 예고이기 때문이다 |

### 디자인 변경 과정

이전 캡처는 `docs/captures-old/` 에 남겼다. 주요 변경은 세 단계였다.

**투영** — 가상 카메라 1.2m / 90m 까지 그리던 때는 거리 대부분이 상단에
압축돼 별 모양이 되고 아래 30% 가 비었다.

| 이전 | 이후 |
|---|---|
| ![](docs/captures-old/demo1-cruise.png) | ![](docs/captures/tune4-final.png) |

**경로** — 선 한 줄은 굵기가 일정해 수직 막대처럼 보인다. 거리별 폭을 투영한
띠로 바꿨다.

| 이전 | 이후 |
|---|---|
| ![](docs/captures-old/tune1-projection.png) | ![](docs/captures-old/tune2-path-band.png) |

**차선과 사각지대** — 파란 차선을 흰색으로 바꾸고 점선/실선을 구분했다.
사각지대는 화살표에서 차선 색으로 옮겼다.

| 이전 (파란 차선 + 화살표) | 이후 (흰 차선, 차선이 물든다) |
|---|---|
| ![](docs/captures-old/demo4-bsd-occupied.png) | ![](docs/captures/s4-bsd-red.png) |

## 남은 일

- 실차 주행 사진(주간/야간)을 보고 색·굵기 확정. 화면 캡처는 프레임버퍼
  이미지라 실제 투사 밝기·대비를 알려주지 못한다
- 안전영역 확인. `dumpsys display` 에 `real 640x480 / app 598x480` 로 나와
  앱 영역이 42px 좁게 보고되는데, 실제로는 전체를 받는 것으로 보인다
- 과속카메라 표지와 사각지대 표시는 아직 실주행에서 뜨는 것을 못 봤다
