// VW ID.4 (MEB) 용 nMirror 대시 프로필.
//
// 나브디 /maps 에 nmirror_obd.js 와 nmirror_obd_minimal.js 두 이름으로 넣는다.
// 원래 파일은 현대·기아 디젤용(DPF, 미션온도, 22ED27/21A0)이라 ID.4 에서는
// 전부 -- 로 나왔다.
//
// ── 이 파일이 어떻게 쓰이는지 (순정 HUD 앱 ObdChannelThread 를 읽어 확인) ──
// * ModeAndPID 는 OBD 칩에 그대로 보낸다. "ATRV" 같은 AT 명령도 된다.
// * Header 가 바뀔 때만 "ATSH <Header>" 를 보낸다. 빈 헤더로 돌아갈 때는
//   되돌리지 않는다. 그래서 여기서는 모든 OBD 항목에 헤더를 적는다 - 배터리
//   항목(BMS 헤더) 다음에 속도가 BMS 로 가 버리면 속도가 멈춘다.
// * requiredPids 가 있으면 내장 속도/RPM/냉각수 요청을 대체한다. 속도를 여기서
//   표준 헤더로 다시 정의해 매번 헤더가 돌아오게 한다. RPM·냉각수는 전기차에
//   의미가 없어 뺐다.
// * Equation 의 A 는 요청을 되돌려준 바이트(서비스+DID) 바로 다음 첫 데이터 바이트.
//   예) 221E3D 요청 -> 응답 62 1E 3D WW XX YY ZZ 에서 A=WW, B=XX ...
//
// ── 주소와 식의 출처 ──
// BMS(0x17FC007B, 29비트) DID 와 식은 spot2000/Volkswagen-MEB-EV-CAN-parameters
// (ID.3 실측 목록)와 iternio/ev-obd-pids 의 MEB.json(ABRP 운영 프로필)에서 가져왔다.
// 이 나브디는 ID.4 와 이미 29비트 CAN(프로토콜 7)으로 통신한다(ObdDevice.xml).
//
// ⚠ 실차 미검증 항목: BMS 응답이 칩의 자동 수신 필터를 통과하는지. 안 되면 배터리
// 항목만 -- 로 나오고 속도·12V·외기온도는 그대로 동작한다.

function applyColor(text, color)
{
    return "<font color='" + color + "'>" + text + "</font>";
}

// 대시 속도 보정. 이전 차 기준 1.05 였는데 ID.4 OBD 속도와는 무관하다.
// 계기판과 비교해서 필요하면 조정한다. 예) 3% 높이려면 return speed * 1.03;
function getSpeed(speed)
{
    return speed;
}

// 왼쪽 뷰. current_time, tmap_tbt_dest_info 는 앱 내장 키다.
function bindLeft()
{
    return ["current_time", "outside_temp", "hv_temp", "tmap_tbt_dest_info"];
}

// 오른쪽 뷰
function bindRight()
{
    return ["hv_power", "hv_voltage", "lv_voltage"];
}

// 속도 위. 전기차에서 가장 자주 보는 값.
function bindAboveSpeed()
{
    return "soc";
}

function checkValid(pid, value)
{
    return !isNaN(value) && pid.MinValue <= value && value <= pid.MaxValue;
}

// 표준 OBD 기능 주소(29비트). 칩의 프로토콜 7 기본 헤더와 같다.
var HDR_OBD = "18DB33F1";
// 고전압 배터리 관리장치(BMS).
var HDR_BMS = "17FC007B";

requiredPids = [
{
    Key: "__speed__",
    Name: "Speed",
    ModeAndPID: "010D",
    Equation: "A",
    MinValue: 0,
    MaxValue: 255,
    Header: HDR_OBD,
    Units: "km/h",
    Interval: 300
},
];

scanPids = [

{
    // 계기판에 보이는 잔량. BMS 원값(A/2.5)을 계기판 표시값으로 환산한다.
    // 환산식 SoC(HMI) = SoC(BMS)*51/46-6.4 (spot2000).
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
                return "";
            v = Math.max(0, Math.min(100, value));
            text = v.toFixed(0) + this.Units;
            if(v <= 15)
                return applyColor(text, "#FF6666");
            return applyColor(text, "#99FF99");
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
    // 값 자체는 전류(A). 화면에는 고전압과 곱한 출력(kW)을 보인다.
    // 원식은 (WW*2^32+...) 로 적혀 있지만 WW 는 2^24 자리이고 실제로는 늘 0 이다.
    // 부호(방전/충전 중 어느 쪽이 +인지)는 실차 확인 전이다.
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
                return this.Name + ": " + value.toFixed(0) + " A";
            kw = value * ((vA * 256 + vB) / 4) / 1000;
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
    Key: "outside_temp",
    Name: "외기",
    ModeAndPID: "0146",
    Equation: "A-40",
    MinValue: -40,
    MaxValue: 85,
    Header: HDR_OBD,
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
},
];
