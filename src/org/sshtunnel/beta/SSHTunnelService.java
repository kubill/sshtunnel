package org.sshtunnel.beta;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.sshtunnel.beta.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

public class SSHTunnelService extends Service implements ConnectionMonitor {

	/**
	 * A multi-thread-safe produce-consumer byte array. Only allows one producer
	 * and one consumer.
	 */

	class ByteQueue {
		public ByteQueue(int size) {
			mBuffer = new byte[size];
		}

		public int getBytesAvailable() {
			synchronized (this) {
				return mStoredBytes;
			}
		}

		public int read(byte[] buffer, int offset, int length)
				throws InterruptedException {
			if (length + offset > buffer.length) {
				throw new IllegalArgumentException(
						"length + offset > buffer.length");
			}
			if (length < 0) {
				throw new IllegalArgumentException("length < 0");

			}
			if (length == 0) {
				return 0;
			}
			synchronized (this) {
				while (mStoredBytes == 0) {
					wait();
				}
				int totalRead = 0;
				int bufferLength = mBuffer.length;
				boolean wasFull = bufferLength == mStoredBytes;
				while (length > 0 && mStoredBytes > 0) {
					int oneRun = Math.min(bufferLength - mHead, mStoredBytes);
					int bytesToCopy = Math.min(length, oneRun);
					System.arraycopy(mBuffer, mHead, buffer, offset,
							bytesToCopy);
					mHead += bytesToCopy;
					if (mHead >= bufferLength) {
						mHead = 0;
					}
					mStoredBytes -= bytesToCopy;
					length -= bytesToCopy;
					offset += bytesToCopy;
					totalRead += bytesToCopy;
				}
				if (wasFull) {
					notify();
				}
				return totalRead;
			}
		}

		public void write(byte[] buffer, int offset, int length)
				throws InterruptedException {
			if (length + offset > buffer.length) {
				throw new IllegalArgumentException(
						"length + offset > buffer.length");
			}
			if (length < 0) {
				throw new IllegalArgumentException("length < 0");

			}
			if (length == 0) {
				return;
			}
			synchronized (this) {
				int bufferLength = mBuffer.length;
				boolean wasEmpty = mStoredBytes == 0;
				while (length > 0) {
					while (bufferLength == mStoredBytes) {
						wait();
					}
					int tail = mHead + mStoredBytes;
					int oneRun;
					if (tail >= bufferLength) {
						tail = tail - bufferLength;
						oneRun = mHead - tail;
					} else {
						oneRun = bufferLength - tail;
					}
					int bytesToCopy = Math.min(oneRun, length);
					System.arraycopy(buffer, offset, mBuffer, tail, bytesToCopy);
					offset += bytesToCopy;
					mStoredBytes += bytesToCopy;
					length -= bytesToCopy;
				}
				if (wasEmpty) {
					notify();
				}
			}
		}

		private byte[] mBuffer;
		private int mHead;
		private int mStoredBytes;
	}

	private NotificationManager notificationManager;
	private Intent intent;
	private PendingIntent pendIntent;
	private SSHMonitor sm;

	private static final String TAG = "SSHTunnel";

	private static final int MSG_CONNECT_START = 0;
	private static final int MSG_CONNECT_FINISH = 1;
	private static final int MSG_CONNECT_SUCCESS = 2;
	private static final int MSG_CONNECT_FAIL = 3;

	private final static String DEFAULT_SHELL = "/system/bin/sh -";

	private SharedPreferences settings = null;

	// private Process proxyProcess = null;
	// private DataOutputStream proxyOS = null;

	private String host;
	private String hostIP = "127.0.0.1";
	private int port;
	private int localPort;
	private int remotePort;
	private String user;
	private String password;
	private boolean isAutoSetProxy = false;
	// private LocalPortForwarder lpf2 = null;
	private DNSServer dnsServer = null;
	private boolean isSocks = false;
	private int processId = 0;

	private ProxyedApp apps[];

	private boolean connected = false;

	/**
	 * The pseudo-teletype (pty) file descriptor that we use to communicate with
	 * another process, typically a shell.
	 */
	private FileDescriptor mTermFd;

	public static final String BASE = "/data/data/org.sshtunnel.beta/";

	final static String CMD_IPTABLES_REDIRECT_DEL = BASE
			+ "iptables -t nat -D OUTPUT -p tcp --dport 80 -j REDIRECT --to-ports 8123\n"
			+ BASE
			+ "iptables -t nat -D OUTPUT -p tcp --dport 443 -j REDIRECT --to-ports 8124\n";

	final static String CMD_IPTABLES_REDIRECT_ADD = BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to-ports 8123\n"
			+ BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-ports 8124\n";

	final static String CMD_IPTABLES_DNAT_DEL = BASE
			+ "iptables -t nat -D OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
			+ BASE
			+ "iptables -t nat -D OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8124\n";

	final static String CMD_IPTABLES_DNAT_ADD = BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
			+ BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8124\n";

	private boolean hasRedirectSupport = true;

	private static final Class<?>[] mStartForegroundSignature = new Class[] {
			int.class, Notification.class };
	private static final Class<?>[] mStopForegroundSignature = new Class[] { boolean.class };

	private Method mStartForeground;
	private Method mStopForeground;

	private Object[] mStartForegroundArgs = new Object[2];
	private Object[] mStopForegroundArgs = new Object[1];

	void invokeMethod(Method method, Object[] args) {
		try {
			method.invoke(this, mStartForegroundArgs);
		} catch (InvocationTargetException e) {
			// Should not happen.
			Log.w("ApiDemos", "Unable to invoke method", e);
		} catch (IllegalAccessException e) {
			// Should not happen.
			Log.w("ApiDemos", "Unable to invoke method", e);
		}
	}

	/**
	 * This is a wrapper around the new startForeground method, using the older
	 * APIs if it is not available.
	 */
	void startForegroundCompat(int id, Notification notification) {
		// If we have the new startForeground API, then use it.
		if (mStartForeground != null) {
			mStartForegroundArgs[0] = Integer.valueOf(id);
			mStartForegroundArgs[1] = notification;
			invokeMethod(mStartForeground, mStartForegroundArgs);
			return;
		}

		// Fall back on the old API.
		setForeground(true);
		notificationManager.notify(id, notification);
	}

	/**
	 * This is a wrapper around the new stopForeground method, using the older
	 * APIs if it is not available.
	 */
	void stopForegroundCompat(int id) {
		// If we have the new stopForeground API, then use it.
		if (mStopForeground != null) {
			mStopForegroundArgs[0] = Boolean.TRUE;
			try {
				mStopForeground.invoke(this, mStopForegroundArgs);
			} catch (InvocationTargetException e) {
				// Should not happen.
				Log.w("ApiDemos", "Unable to invoke stopForeground", e);
			} catch (IllegalAccessException e) {
				// Should not happen.
				Log.w("ApiDemos", "Unable to invoke stopForeground", e);
			}
			return;
		}

		// Fall back on the old API. Note to cancel BEFORE changing the
		// foreground state, since we could be killed at that point.
		notificationManager.cancel(id);
		setForeground(false);
	}

	private void initHasRedirectSupported() {
		Process process = null;
		DataOutputStream os = null;
		DataInputStream es = null;

		String command;
		String line = null;

		command = BASE
				+ "iptables -t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to-ports 8153";

		try {
			process = Runtime.getRuntime().exec("su");
			es = new DataInputStream(process.getErrorStream());
			os = new DataOutputStream(process.getOutputStream());
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			process.waitFor();

			while (null != (line = es.readLine())) {
				Log.d(TAG, line);
				if (line.contains("No chain/target/match")) {
					this.hasRedirectSupport = false;
					break;
				}
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
		} finally {
			try {
				if (os != null) {
					os.close();
				}
				if (es != null)
					es.close();
				process.destroy();
			} catch (Exception e) {
				// nothing
			}
		}

		// flush the check command
		runRootCommand(command.replace("-A", "-D"));
	}

	public static boolean runRootCommand(String command) {
		Process process = null;
		DataOutputStream os = null;
		Log.d(TAG, command);
		try {
			process = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(process.getOutputStream());
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			process.waitFor();
		} catch (Exception e) {
			Log.e(TAG, "Exception in root command", e);
			return false;
		} finally {
			try {
				if (os != null) {
					os.close();
				}
				process.destroy();
			} catch (Exception e) {
				// nothing
			}
		}
		return true;
	}

	public boolean connect() {

		String cmd = "";

		FileInputStream mTermIn = new FileInputStream(mTermFd);
		FileOutputStream mTermOut = new FileOutputStream(mTermFd);

		try {
			if (isSocks)
				cmd = "/data/data/org.sshtunnel.beta/openssh -NT -D "
						+ localPort + " -p " + port + " -L "
						+ "127.0.0.1:5353:8.8.8.8:53 " + user + "@" + hostIP;
			else
				cmd = "/data/data/org.sshtunnel.beta/ssh -NTy -L 127.0.0.1:"
						+ localPort + ":" + "127.0.0.1" + ":" + remotePort
						+ " -L " + "127.0.0.1:5353:8.8.8.8:53 " + user + "@"
						+ hostIP + "/" + port;

			Log.e(TAG, cmd);

			mTermOut.write((cmd + "\n").getBytes());
			mTermOut.flush();

			int count = 0;
			byte[] data = new byte[256];
			while ((mTermIn.read(data)) != -1) {
				StringBuffer sb = new StringBuffer();
				for (int i = 0; i < data.length; i++) {
					char printableB = (char) data[i];
					if (data[i] < 32 || data[i] > 126) {
						printableB = ' ';
					}
					sb.append(printableB);
				}
				String line = sb.toString();
				if (line.toLowerCase().contains("yes")) {
					mTermOut.write(("yes\n").getBytes());
					mTermOut.flush();
					continue;
				}
				if (line.toLowerCase().contains("password")) {
					mTermOut.write((password + "\n").getBytes());
					mTermOut.write("exit\n".getBytes());
					mTermOut.flush();
					Log.d(TAG, "Flush count: " + count);
					break;
				} else {
					Log.e(TAG, "Connect fail: " + line);
					if (count > 10) {
						return false;
					}
				}
				count++;
			}

		} catch (Exception e) {
			Log.e(TAG, "Connect Error!");
			return false;
		}

		finishConnection();
		return true;
	}

	/**
	 * Internal method to request actual PTY terminal once we've finished
	 * authentication. If called before authenticated, it will just fail.
	 */
	private void finishConnection() {

		Log.e(TAG, "Forward Successful");

		StringBuffer cmd = new StringBuffer();

		if (isSocks)
			runRootCommand(BASE + "proxy_socks.sh start " + localPort);
		else
			runRootCommand(BASE + "proxy_http.sh start " + localPort);

		if (hasRedirectSupport) {
			cmd.append(BASE
					+ "iptables -t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to-ports 8153\n");
		} else {
			cmd.append(BASE
					+ "iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:8153\n");
		}

		if (isAutoSetProxy) {

			cmd.append(hasRedirectSupport ? CMD_IPTABLES_REDIRECT_ADD
					: CMD_IPTABLES_DNAT_ADD);
		} else {
			// for proxy specified apps
			if (apps == null || apps.length <= 0)
				apps = AppManager.getApps(this);

			for (int i = 0; i < apps.length; i++) {
				if (apps[i].isProxyed()) {
					cmd.append((hasRedirectSupport ? CMD_IPTABLES_REDIRECT_ADD
							: CMD_IPTABLES_DNAT_ADD).replace("-t nat",
							"-t nat -m owner --uid-owner " + apps[i].getUid()));
				}
			}
		}

		String rules = cmd.toString();

		if (hostIP != null)
			rules = rules.replace("--dport 443",
					"! -d " + hostIP + " --dport 443").replace("--dport 80",
					"! -d " + hostIP + " --dport 80");

		if (isSocks)
			runRootCommand(rules.replace("8124", "8123"));
		else
			runRootCommand(rules);

		// Forward Successful
		return;

	}

	// private String getCmd(String cmd) {
	// return cmd.replace("--dport 443", "! -d " + hostIP + " "
	// + "--dport 443");
	// }

	/** Called when the activity is first created. */
	public boolean handleCommand() {

		try {
			InetAddress ia;
			ia = InetAddress.getByName(host);
			String ip = ia.getHostAddress();
			if (ip != null && !ip.equals(""))
				hostIP = ip;
		} catch (UnknownHostException e) {
			Log.e(TAG, "cannot resolve the host name");
			return false;
		}

		Log.d(TAG, "Host IP: " + hostIP);

		// dnsServer = new DNSServer("DNS Server", 8153, "208.67.222.222",
		// 5353);
		dnsServer = new DNSServer("DNS Server", 8153, "127.0.0.1", 5353);
		dnsServer.setBasePath("/data/data/org.sshtunnel.beta");
		new Thread(dnsServer).start();

		return connect();
	}

	private void notifyAlert(String title, String info) {
		Notification notification = new Notification(R.drawable.ic_stat, title,
				System.currentTimeMillis());

		initSoundVibrateLights(notification);

		notification.setLatestEventInfo(this, getString(R.string.app_name),
				info, pendIntent);
		startForegroundCompat(1, notification);
	}

	private void initSoundVibrateLights(Notification notification) {
		final String ringtone = settings.getString(
				"settings_key_notif_ringtone", null);
		AudioManager audioManager = (AudioManager) this
				.getSystemService(Context.AUDIO_SERVICE);
		if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
			notification.sound = null;
		} else if (ringtone != null)
			notification.sound = Uri.parse(ringtone);
		else
			notification.defaults |= Notification.DEFAULT_SOUND;

		if (settings.getBoolean("settings_key_notif_vibrate", false)) {
			long[] vibrate = { 0, 1000, 500, 1000, 500, 1000 };
			notification.vibrate = vibrate;
		}

		notification.defaults |= Notification.DEFAULT_LIGHTS;
	}

	private void notifyAlert(String title, String info, int flags) {
		Notification notification = new Notification(R.drawable.ic_stat, title,
				System.currentTimeMillis());
		notification.flags = flags;

		initSoundVibrateLights(notification);

		notification.setLatestEventInfo(this, getString(R.string.app_name),
				info, pendIntent);
		notificationManager.notify(0, notification);
	}

	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}

	private ArrayList<String> parse(String cmd) {
		final int PLAIN = 0;
		final int WHITESPACE = 1;
		final int INQUOTE = 2;
		int state = WHITESPACE;
		ArrayList<String> result = new ArrayList<String>();
		int cmdLen = cmd.length();
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < cmdLen; i++) {
			char c = cmd.charAt(i);
			if (state == PLAIN) {
				if (Character.isWhitespace(c)) {
					result.add(builder.toString());
					builder.delete(0, builder.length());
					state = WHITESPACE;
				} else if (c == '"') {
					state = INQUOTE;
				} else {
					builder.append(c);
				}
			} else if (state == WHITESPACE) {
				if (Character.isWhitespace(c)) {
					// do nothing
				} else if (c == '"') {
					state = INQUOTE;
				} else {
					state = PLAIN;
					builder.append(c);
				}
			} else if (state == INQUOTE) {
				if (c == '\\') {
					if (i + 1 < cmdLen) {
						i += 1;
						builder.append(cmd.charAt(i));
					}
				} else if (c == '"') {
					state = PLAIN;
				} else {
					builder.append(c);
				}
			}
		}
		if (builder.length() > 0) {
			result.add(builder.toString());
		}
		return result;
	}

	private FileDescriptor createSubprocess(String shell, int[] processId) {

		if (shell == null || shell.equals("")) {
			shell = DEFAULT_SHELL;
		}
		ArrayList<String> args = parse(shell);
		String arg0 = args.get(0);
		String arg1 = null;
		String arg2 = null;

		if (args.size() >= 2) {
			arg1 = args.get(1);
		}
		if (args.size() >= 3) {
			arg2 = args.get(2);
		}

		return Exec.createSubprocess(arg0, arg1, arg2, processId);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		settings = PreferenceManager.getDefaultSharedPreferences(this);
		notificationManager = (NotificationManager) this
				.getSystemService(NOTIFICATION_SERVICE);

		initHasRedirectSupported();

		intent = new Intent(this, SSHTunnel.class);
		pendIntent = PendingIntent.getActivity(this, 0, intent, 0);

		try {
			mStartForeground = getClass().getMethod("startForeground",
					mStartForegroundSignature);
			mStopForeground = getClass().getMethod("stopForeground",
					mStopForegroundSignature);
		} catch (NoSuchMethodException e) {
			// Running on an older platform.
			mStartForeground = mStopForeground = null;
		}
	}

	/** Called when the activity is closed. */
	@Override
	public void onDestroy() {

		stopForegroundCompat(1);

		synchronized (this) {
			if (sm != null) {
				sm.close();
				sm = null;
			}

			if (connected) {

				notifyAlert(getString(R.string.forward_stop),
						getString(R.string.service_stopped),
						Notification.FLAG_AUTO_CANCEL);
			}

			// Make sure the connection is closed, important here
			onDisconnect();
		}

		try {
			if (dnsServer != null) {
				dnsServer.close();
				dnsServer = null;
			}
		} catch (Exception e) {
			Log.e(TAG, "DNS Server close unexpected");
		}

		Editor ed = settings.edit();
		ed.putBoolean("isRunning", false);
		ed.commit();

		notificationManager.cancel(0);

		if (mTermFd != null) {
			Exec.close(mTermFd);
			mTermFd = null;
		}

		super.onDestroy();
	}

	private void onDisconnect() {

		connected = false;

		StringBuffer cmd = new StringBuffer();

		if (hasRedirectSupport) {
			cmd.append(BASE
					+ "iptables -t nat -D OUTPUT -p udp --dport 53 -j REDIRECT --to-ports 8153\n");
		} else {
			cmd.append(BASE
					+ "iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:8153\n");
		}

		if (isAutoSetProxy) {
			cmd.append(hasRedirectSupport ? CMD_IPTABLES_REDIRECT_DEL
					: CMD_IPTABLES_DNAT_DEL);
		} else {
			// for proxy specified apps
			if (apps == null || apps.length <= 0)
				apps = AppManager.getApps(this);

			for (int i = 0; i < apps.length; i++) {
				if (apps[i].isProxyed()) {
					cmd.append((hasRedirectSupport ? CMD_IPTABLES_REDIRECT_DEL
							: CMD_IPTABLES_DNAT_DEL).replace("-t nat",
							"-t nat -m owner --uid-owner " + apps[i].getUid()));
				}
			}
		}

		String rules = cmd.toString();

		if (hostIP != null)
			rules = rules.replace("--dport 443",
					"! -d " + hostIP + " --dport 443").replace("--dport 80",
					"! -d " + hostIP + " --dport 80");

		if (isSocks)
			runRootCommand(rules.replace("8124", "8123"));
		else
			runRootCommand(rules);

		runRootCommand("/data/data/org.sshtunnel.beta/proxy_http.sh stop");

	}

	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			Editor ed = settings.edit();
			switch (msg.what) {
			case MSG_CONNECT_START:
				ed.putBoolean("isConnecting", true);
				break;
			case MSG_CONNECT_FINISH:
				ed.putBoolean("isConnecting", false);
				break;
			case MSG_CONNECT_SUCCESS:
				ed.putBoolean("isRunning", true);
				break;
			case MSG_CONNECT_FAIL:
				ed.putBoolean("isRunning", false);
				break;
			}
			ed.commit();
			super.handleMessage(msg);
		}
	};

	// This is the old onStart method that will be called on the pre-2.0
	// platform. On 2.0 or later we override onStartCommand() so this
	// method will not be called.
	@Override
	public void onStart(Intent intent, int startId) {

		super.onStart(intent, startId);

		Log.e(TAG, "Service Start");

		Bundle bundle = intent.getExtras();
		host = bundle.getString("host");
		user = bundle.getString("user");
		password = bundle.getString("password");
		port = bundle.getInt("port");
		localPort = bundle.getInt("localPort");
		remotePort = bundle.getInt("remotePort");
		isAutoSetProxy = bundle.getBoolean("isAutoSetProxy");
		isSocks = bundle.getBoolean("isSocks");

		new Thread(new Runnable() {
			public void run() {
				handler.sendEmptyMessage(MSG_CONNECT_START);
				int[] processIds = new int[1];
				mTermFd = createSubprocess(null, processIds);
				processId = processIds[0];
				if (isOnline() && handleCommand()) {
					// Connection and forward successful
					notifyAlert(getString(R.string.forward_success),
							getString(R.string.service_running));
					connected = true;
					handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);
					sm = new SSHMonitor();
					sm.setMonitor(SSHTunnelService.this);
					new Thread(sm).start();

				} else {
					// Connection or forward unsuccessful
					notifyAlert(getString(R.string.forward_fail),
							getString(R.string.service_failed),
							Notification.FLAG_AUTO_CANCEL);
					connected = false;
					handler.sendEmptyMessage(MSG_CONNECT_FAIL);
					stopSelf();
				}
				handler.sendEmptyMessage(MSG_CONNECT_FINISH);
			}
		}).start();
	}

	public boolean isOnline() {

		ConnectivityManager manager = (ConnectivityManager) this
				.getSystemService(CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = manager.getActiveNetworkInfo();
		if (networkInfo == null)
			return false;
		return true;
	}

	@Override
	public void connectionLost(boolean isReconnect) {

		if (!connected)
			return;

		SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss");

		if (!isOnline() || !isReconnect) {

			connected = false;
			notifyAlert(
					getString(R.string.auto_reconnected) + " "
							+ df.format(new Date()),
					getString(R.string.reconnect_fail) + " @"
							+ df.format(new Date()),
					Notification.FLAG_AUTO_CANCEL);
			stopSelf();
			return;
		}

		try {
			Thread.sleep(1000);
		} catch (Exception ignore) {
			// Nothing
		}

		synchronized (this) {
			onDisconnect();

			connect();

			connected = true;
		}

		return;

	}

	@Override
	public void notifySuccess() {
		SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss");
		notifyAlert(
				getString(R.string.auto_reconnected) + " "
						+ df.format(new Date()),
				getString(R.string.reconnect_success) + " @"
						+ df.format(new Date()));
	}

	@Override
	public void waitFor() {
		try {
			Exec.waitFor(processId);
		} catch (Exception ignore) {
			// Nothing
		}
		int[] processIds = new int[1];
		mTermFd = createSubprocess(null, processIds);
		processId = processIds[0];

	}

}