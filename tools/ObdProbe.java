import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

/**
 * 나브디 OBD 칩에 명령을 하나씩 보내고 원본 응답을 그대로 찍는다.
 *
 * ObdService 가 여는 로컬 소켓(navdy.obd.local.address)은 접속한 쪽의 입출력을
 * 칩에 그대로 잇는다. nMirror 의 ObdChannelThread 도 같은 길로 붙는다.
 * 두 클라이언트가 동시에 붙으면 명령이 섞이므로, 돌리기 전에 순정 HUD 앱을
 * 내려야 한다.
 *
 * 빌드: javac(android.jar) -> d8 --min-api 22 -> classes.dex 를 jar 로 묶는다.
 * 실행: adb push obdprobe.jar /data/local/tmp/ 후
 *   CLASSPATH=/data/local/tmp/obdprobe.jar app_process /system/bin ObdProbe ATZ 0100 ...
 */
public class ObdProbe {

  private static final int TIMEOUT_MS = 5000;

  public static void main(String[] args) throws Exception {
    LocalSocket s = new LocalSocket();
    s.connect(new LocalSocketAddress("navdy.obd.local.address"));
    s.setSoTimeout(TIMEOUT_MS);
    InputStream in = s.getInputStream();
    OutputStream out = s.getOutputStream();

    // 남아 있던 프롬프트나 찌꺼기를 비운다.
    out.write("\r".getBytes("US-ASCII"));
    out.flush();
    readUntilPrompt(in);

    for (String cmd : args) {
      long t0 = System.currentTimeMillis();
      out.write((cmd + "\r").getBytes("US-ASCII"));
      out.flush();
      String resp = readUntilPrompt(in);
      long dt = System.currentTimeMillis() - t0;
      System.out.println(String.format("%-16s %5dms | %s", cmd, dt, oneLine(resp)));
    }
    s.close();
  }

  private static String readUntilPrompt(InputStream in) throws Exception {
    StringBuilder sb = new StringBuilder();
    try {
      int b;
      while ((b = in.read()) != -1) {
        if (b == '>') {
          break;
        }
        sb.append((char) b);
      }
    } catch (SocketTimeoutException e) {
      sb.append("<<TIMEOUT>>");
    }
    return sb.toString();
  }

  private static String oneLine(String s) {
    return s.replace("\r", " / ").replace("\n", " ").replaceAll("( / )+", " / ").trim();
  }
}
