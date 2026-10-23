// VW ID.4 (MEB) 용 nMirror 대시 프로필.
//
// 나브디 /maps 에 nmirror_obd.js 와 nmirror_obd_minimal.js 두 이름으로 넣는다.
// 원래 파일은 현대·기아 디젤용(DPF, 미션온도, 22ED27/21A0)이라 ID.4 에서는
// 전부 -- 로 나왔다.
//
// ── 왜 이렇게 생겼나 (나브디 OBD 칩 STN1110 v3.2.0 에 직접 명령을 보내 확인) ──
// ID.4 의 쓸 만한 값은 VW 진단 주소(0x17FC00xx, 29비트)에서만 나온다.
// 여기에 닿으려면 칩 설정 네 가지가 모두 필요하다:
//   1. 프로토콜 7(CAN 29비트 500k) 고정. 자동 검색 상태에서는 3번이 거부된다.
//   2. 우선순위 17 (ATCP17). 기본값 18 로는 BMS 가 답하지 않는다.
//   3. 수신필터 개방 (ATCF/ATCM 0). 자동필터는 0x17FE00xx 응답을 버린다.
//   4. 두 프레임 응답용 이어받기 신호를 0x17FC007B 로 (ATFCSH/SD/SM).
// 반대로 표준 OBD(010D 등)는 우선순위 18 이어야 해서 둘을 섞을 수 없다. 그래서
// 속도까지 BMS 의 22F40D 로 읽어 모든 항목이 같은 설정을 쓰게 했다.
// 외기온도는 11비트 공조 주소(0x746)에만 있어 이 방식으로는 못 읽는다.
//
// ── nMirror 쪽 동작 (순정 HUD 앱 ObdChannelThread/PidManager 디컴파일로 확인) ──
// * ModeAndPID 는 칩에 그대로 간다. AT 명령도 된다. 위 설정을 '항목' 으로
//   넣어 주기적으로 보낸다. nMirror 는 다시 붙을 때 ATZ 로 칩을 초기화한다.
// * Header 는 바뀔 때만 "ATSH <Header>" 로 보낸다. 이 칩은 8자리 헤더를
//   거부하므로 6자리만 쓴다(우선순위는 ATCP 가 맡는다).
// * requiredPids 는 내장 속도/RPM/냉각수 요청을 대체하고 맨 먼저 실행된다.
//   명령은 단일 스레드로 순서대로 나간다.
// * 지도 화면에서는 010D·ATRV 와 bindMapRight 의 항목만 요청한다. 그래서
//   설정과 속도를 bindMapRight 에도 넣는다(화면에는 빈 글자).
// * Equation 의 A 는 요청을 되돌려준 바이트(서비스+DID) 다음 첫 데이터 바이트.
//
// ── 주소·식의 출처 ──
// spot2000/Volkswagen-MEB-EV-CAN-parameters (ID.3 실측 목록),
// iternio/ev-obd-pids volkswagen/MEB.json (ABRP 운영 프로필).
// 모두 이 차(ID.4)에서 응답을 직접 받아 확인했다(2026-10-23).

function applyColor(text, color)
{
    return "<font color='" + color + "'>" + text + "</font>";
}

// 대시 속도 보정. 이전 차 기준 1.05 였는데 ID.4 와는 무관하다.
// 계기판과 비교해서 필요하면 조정한다. 예) 3% 높이려면 return speed * 1.03;
function getSpeed(speed)
{
    return speed;
}

// 왼쪽 뷰. current_time, tmap_tbt_dest_info 는 앱 내장 키다.
function bindLeft()
{
    return ["current_time", "hv_temp", "odometer", "tmap_tbt_dest_info"];
}

// 오른쪽 뷰
function bindRight()
{
    return ["soc", "hv_power", "hv_voltage", "lv_voltage"];
}

// 속도 위
function bindAboveSpeed()
{
    return "gear";
}

// 지도 화면에서 요청할 항목. 표시용이 아니라, 지도 화면에서도 칩 설정과
// 속도 요청이 돌게 하려고 넣는다. 설정 항목은 빈 글자를 돌려준다.
function bindMapRight()
{
    return ["cfg_sp", "cfg_cp", "cfg_cf", "cfg_cm", "cfg_fcsh", "cfg_fcsd", "cfg_fcsm", "speed_bms"];
}

function checkValid(pid, value)
{
    return !isNaN(value) && pid.MinValue <= value && value <= pid.MaxValue;
}

// 우선순위(17)는 ATCP 가 정하고 헤더는 나머지 6자리만 쓴다.
var HDR_BMS = "FC007B";   // 고전압 배터리 관리장치 0x17FC007B
var HDR_GW = "FC0076";    // 게이트웨이 0x17FC0076 (기어, 적산거리)

// 칩 설정. 순서가 중요하다(프로토콜을 먼저 고정해야 필터가 받아들여진다).
var CFG = [
    ["cfg_sp", "ATSP7"],
    ["cfg_cp", "ATCP17"],
    ["cfg_cf", "ATCF00000000"],
    ["cfg_cm", "ATCM00000000"],
    ["cfg_fcsh", "ATFCSH17FC007B"],
    ["cfg_fcsd", "ATFCSD300000"],
    ["cfg_fcsm", "ATFCSM1"]
];

function cfgPid(c)
{
    return {
        Key: c[0],
        Name: c[0],
        ModeAndPID: c[1],
        Equation: "",
        MinValue: 0,
        MaxValue: 0,
        Header: HDR_BMS,
        Units: "",
        Interval: 20000,
        Exp: function() { return ""; }
    };
}

function speedPid(key)
{
    return {
        Key: key,
        Name: "Speed",
        ModeAndPID: "22F40D",
        Equation: "A",
        MinValue: 0,
        MaxValue: 255,
        Header: HDR_BMS,
        Units: "km/h",
        Interval: 300,
        Exp: function() { return ""; }
    };
}

// 설정이 맨 먼저 나가고 그다음 속도. 내장 속도 요청(010D)은 이것으로 대체된다.
requiredPids = [];
for (var i = 0; i < CFG.length; i++)
    requiredPids.push(cfgPid(CFG[i]));
requiredPids.push(speedPid("__speed__"));

scanPids = [];
// 지도 화면 매핑용(bindMapRight). requiredPids 와 ModeAndPID 가 같아 실제로는
// 한 번만 요청된다.
for (var j = 0; j < CFG.length; j++)
    scanPids.push(cfgPid(CFG[j]));
scanPids.push(speedPid("speed_bms"));

function groupDigits(n)
{
    var s = "" + Math.round(n);
    var out = "";
    while (s.length > 3) {
        out = "," + s.substring(s.length - 3) + out;
        s = s.substring(0, s.length - 3);
    }
    return s + out;
}

scanPids = scanPids.concat([

{
    // 기어. 응답 62 21 0E XX YY 의 YY.
    Key: "gear",
    Name: "기어",
    ModeAndPID: "22210E",
    Equation: "B",
    MinValue: 0,
    MaxValue: 255,
    Header: HDR_GW,
    Units: "",
    Interval: 1000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return "";
            v = parseInt(value);
            if(v == 8)
                return applyColor("P", "#AAAAFF");
            if(v == 7)
                return applyColor("R", "#FF9999");
            if(v == 6)
                return applyColor("N", "#AAFFAA");
            if(v == 5)
                return applyColor("D", "#FFFFFF");
            if(v == 12)
                return applyColor("B", "#99DDFF");
            return "";
        }
},
{
    // 계기판에 보이는 잔량. BMS 원값(A/2.5)을 계기판 표시값으로 환산한다.
    // SoC(HMI) = SoC(BMS)*51/46-6.4 (spot2000).
    Key: "soc",
    Name: "잔량",
    ModeAndPID: "22028C",
    Equation: "A/2.5*51/46-6.4",
    MinValue: -10,
    MaxValue: 110,
    Header: HDR_BMS,
    Units: "%",
    Interval: 10000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return this.Name + ": --";
            v = Math.max(0, Math.min(100, value));
            text = this.Name + ": " + v.toFixed(0) + this.Units;
            if(v <= 15)
                return applyColor(text, "#FF6666");
            return text;
        }
},
{
    Key: "hv_voltage",
    Name: "고전압",
    ModeAndPID: "221E3B",
    Equation: "(A*256+B)/4",
    MinValue: 200,
    MaxValue: 500,
    Header: HDR_BMS,
    Units: "V",
    Interval: 2000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return this.Name + ": --";
            return this.Name + ": " + value.toFixed(0) + " " + this.Units;
        }
},
{
    // 값 자체는 배터리 전류(A). 두 프레임 응답이라 칩 이어받기 설정이 필요하다.
    // 원식은 (WW*2^32+...) 로 적혀 있지만 WW 는 2^24 자리이고 실제로는 늘 0 이다.
    // 실측: 주차 중 전기 소비 때 -1.9A. 즉 방전이 음수다. 화면 '출력' 은
    // 달릴 때 + 가 되게 부호를 뒤집는다(회생·충전 때 -).
    Key: "hv_power",
    Name: "출력",
    ModeAndPID: "221E3D",
    Equation: "(A*16777216+B*65536+C*256+D-150000)/100",
    MinValue: -1500,
    MaxValue: 1500,
    Header: HDR_BMS,
    Units: "kW",
    Interval: 1000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return this.Name + ": --";
            vA = getPidValue("221E3B", "A");
            vB = getPidValue("221E3B", "B");
            if(isNaN(vA) || isNaN(vB))
                return this.Name + ": " + (-value).toFixed(0) + " A";
            kw = -value * ((vA * 256 + vB) / 4) / 1000;
            return this.Name + ": " + kw.toFixed(1) + " " + this.Units;
        }
},
{
    Key: "hv_temp",
    Name: "배터리",
    ModeAndPID: "222A0B",
    Equation: "A/2-40",
    MinValue: -40,
    MaxValue: 87,
    Header: HDR_BMS,
    Units: "℃",
    Interval: 10000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return this.Name + ": --";
            return this.Name + ": " + value.toFixed(0) + this.Units;
        }
},
{
    Key: "odometer",
    Name: "적산",
    ModeAndPID: "22295A",
    Equation: "A*65536+B*256+C",
    MinValue: 0,
    MaxValue: 16777215,
    Header: HDR_GW,
    Units: "km",
    Interval: 60000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return this.Name + ": --";
            return this.Name + ": " + groupDigits(value) + " " + this.Units;
        }
},
{
    // 칩이 OBD 단자에서 직접 재는 12V. 앱 내장 ATRV 결과를 같이 받는다.
    Key: "lv_voltage",
    Name: "12V",
    ModeAndPID: "ATRV",
    Equation: "",
    MinValue: 0,
    MaxValue: 20,
    Header: "",
    Units: "V",
    Interval: 5000,
    Exp:
        function(value)
        {
            if(isNaN(value))
                return this.Name + ": --";
            text = this.Name + ": " + value.toFixed(1) + " " + this.Units;
            if(value < 12.0)
                return applyColor(text, "#FFCC66");
            return text;
        }
}
]);
