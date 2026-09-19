package kr.geniu.navdyhud;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * 콤마에서 오는 텔레메트리를 받는 RFCOMM 서버.
 *
 * 콤마가 클라이언트로 붙는다. 프레임은 빅엔디안 [타입 2B][길이 4B][내용]:
 *   1 텔레메트리 JSON, 2 PING
 * 콤마 쪽 구현은 selfdrive/eon_cluster/bt_link.py 이고 UUID 가 반드시 같아야 한다.
 */
public class CommaLink {

  public static final UUID SERVICE_UUID =
      UUID.fromString("b8949674-c91b-4c36-a9d0-c24c644826a0");
  private static final String SERVICE_NAME = "CommaHUD";
  private static final String TAG = "CommaHUD";

  private static final int TYPE_TELEMETRY = 1;
  private static final int TYPE_PING = 2;
  private static final int MAX_FRAME = 4 * 1024 * 1024;

  public interface Listener {
    void onPacket(JSONObject packet);
    void onState(String state);
  }

  private final Listener listener;
  private volatile boolean running = true;
  private Thread thread;

  public CommaLink(Listener listener) {
    this.listener = listener;
  }

  public void start() {
    thread = new Thread(new Runnable() {
      @Override public void run() { loop(); }
    }, "comma-link");
    thread.setDaemon(true);
    thread.start();
  }

  public void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void loop() {
    BluetoothServerSocket server = null;
    try {
      while (running) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
          listener.onState("블루투스 꺼짐");
          closeQuietly(server);
          server = null;
          sleep(3000);
          continue;
        }
        if (server == null) {
          // 보안 채널로 연다. 콤마와 이미 본딩돼 있어야 붙는다.
          //
          // 리스너를 계속 열어둔다. 예전에는 accept 직후 닫았는데, 닫는 순간
          // SDP 레코드가 같이 내려가서 콤마가 다음 접속 때 채널을 못 찾았다
          // (sdptool browse 에 CommaHUD 가 아예 안 보였다). 끊길 때마다
          // 재등록되는 탓에 간헐적으로만 붙는 것처럼 보였다.
          try {
            server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
          } catch (IOException e) {
            Log.w(TAG, "listen: " + e);
            listener.onState("리스너 실패: " + e.getMessage());
            sleep(3000);
            continue;
          }
        }
        BluetoothSocket sock = null;
        try {
          listener.onState("대기중");
          sock = server.accept();
          listener.onState("연결됨");
          read(sock);
        } catch (IOException e) {
          Log.w(TAG, "link: " + e);
          listener.onState("끊김: " + e.getMessage());
          // accept 자체가 깨졌으면 리스너를 새로 만든다.
          if (sock == null) {
            closeQuietly(server);
            server = null;
          }
        } finally {
          closeQuietly(sock);
        }
        if (running) {
          sleep(800);
        }
      }
    } finally {
      closeQuietly(server);
    }
  }

  private void read(BluetoothSocket sock) throws IOException {
    DataInputStream in = new DataInputStream(sock.getInputStream());
    while (running) {
      int type = in.readUnsignedShort();
      int len = in.readInt();
      if (len < 0 || len > MAX_FRAME) {
        throw new IOException("bad frame length " + len);
      }
      byte[] body = new byte[len];
      in.readFully(body);
      if (type == TYPE_TELEMETRY) {
        try {
          listener.onPacket(new JSONObject(new String(body, "UTF-8")));
        } catch (Exception e) {
          Log.w(TAG, "json: " + e);
        }
      } else if (type != TYPE_PING) {
        Log.w(TAG, "unknown frame type " + type);
      }
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void closeQuietly(BluetoothServerSocket s) {
    if (s != null) {
      try { s.close(); } catch (IOException ignored) { }
    }
  }

  private static void closeQuietly(BluetoothSocket s) {
    if (s != null) {
      try { s.close(); } catch (IOException ignored) { }
    }
  }
}
