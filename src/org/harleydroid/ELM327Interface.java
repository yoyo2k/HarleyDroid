//
// HarleyDroid: Harley Davidson J1850 Data Analyser for Android.
//
// Copyright (C) 2010-2012 Stelian Pop <stelian@popies.net>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//

package org.harleydroid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;
//import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

public class ELM327Interface implements J1850Interface
{
	private static final boolean D = false;
	private static final String TAG = ELM327Interface.class.getSimpleName();

	//private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private static final int AT_TIMEOUT = 2000;
	private static final int ATZ_TIMEOUT = 5000;
	private static final int ATMA_TIMEOUT = 10000;
	private static final int MAX_ERRORS = 10;

	private HarleyDroidService mHarleyDroidService;
	private HarleyData mHD;
	private ConnectThread mConnectThread;
	private PollThread mPollThread;
	private SendThread mSendThread;
	private BluetoothDevice mDevice;
	private BluetoothSocket mSock = null;
	private BufferedReader mIn;
	private OutputStream mOut;
	private Timer mTimer = null;

	public ELM327Interface(HarleyDroidService harleyDroidService, BluetoothDevice device) {
		mHarleyDroidService = harleyDroidService;
		mDevice = device;
	}

	public void connect(HarleyData hd) {
		if (D) Log.d(TAG, "connect");

		mHD = hd;
		if (mTimer != null)
			mTimer.cancel();
		mTimer = new Timer();
		if (mConnectThread != null)
			mConnectThread.cancel();
		mConnectThread = new ConnectThread();
		mConnectThread.start();
	}

	public void disconnect() {
		if (D) Log.d(TAG, "disconnect");

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}
		if (mPollThread != null) {
			mPollThread.cancel();
			mPollThread = null;
		}
		if (mSendThread != null) {
			mSendThread.cancel();
			mSendThread = null;
		}
		// if the ELM327 is polling, we need to get it out of ATMA
		// or it will be left unusable (blocked in poll mode)
		try {
			writeLine("");
		} catch (Exception e) {
		}
		if (mTimer != null) {
			mTimer.cancel();
			mTimer = null;
		}
		if (mSock != null) {
			try {
				mSock.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of socket failed", e);
			}
		}
	}

	public void startSend(String type[], String ta[], String sa[],
						  String command[], String expect[], int delay) {
		if (D) Log.d(TAG, "send: " + type + "-" + ta + "-" +
				  sa + "-" + command + "-" + expect);

		if (mPollThread != null) {
			mPollThread.cancel();
			mPollThread = null;
		}
		if (mSendThread != null) {
			mSendThread.cancel();
		}
		mSendThread = new SendThread(type, ta, sa, command, expect, delay);
		mSendThread.start();
	}

	public void setSendData(String type[], String ta[], String sa[],
							String command[], String expect[], int delay) {
		if (D) Log.d(TAG, "setSendData");

		if (mSendThread != null)
			mSendThread.setData(type, ta, sa, command, expect, delay);
	}

	public void startPoll() {
		if (D) Log.d(TAG, "startPoll");

		if (mSendThread != null) {
			mSendThread.cancel();
			mSendThread = null;
		}
		if (mPollThread != null) {
			mPollThread.cancel();
		}
		mPollThread = new PollThread();
		mPollThread.start();
	}

	private class CancelTimer extends TimerTask {
		public void run() {
			if (D) Log.d(TAG, "CANCEL AT " + System.currentTimeMillis());
			try {
				writeLine("");
			} catch (IOException e) {
			}
			try {
				mSock.close();
			} catch (IOException e) {
			}
		}
	};

	private String readLine(long timeout) throws IOException {
		CancelTimer t = new CancelTimer();
		mTimer.schedule(t, timeout);
		if (D) Log.d(TAG, "READLINE AT " + System.currentTimeMillis());
		String line = mIn.readLine();
		t.cancel();
		if (D) Log.d(TAG, "read (" + line.length() + "): " + line);
		return line;
	}

	private void writeLine(String line) throws IOException {
		line += "\r";
		if (D) Log.d(TAG, "write: " + line);
		mOut.write(myGetBytes(line));
		mOut.flush();
	}

	private String chat(String send, String expect, long timeout) throws IOException {
		StringBuilder line = new StringBuilder();
		writeLine(send);
		long start = System.currentTimeMillis();
		while (timeout > 0) {
			line.append(readLine(timeout) + "\n");
			long now = System.currentTimeMillis();
			if (line.indexOf(expect) != -1)
				return line.toString();
			timeout -= (now - start);
			start = now;
		}
		throw new IOException("timeout");
	}

	static byte[] myGetBytes(String s, int start, int end) {
		byte[] result = new byte[end - start];
		for (int i = start; i < end; i++) {
			result[i] = (byte) s.charAt(i);
		}
		return result;
	}

	static byte[] myGetBytes(String s) {
		return myGetBytes(s, 0, s.length());
	}

	private class ConnectThread extends Thread {

		public ConnectThread() {
			BluetoothSocket tmp = null;

			setName("ELM327Interface: ConnectThread");
			try {
				//tmp = mDevice.createRfcommSocketToServiceRecord(SPP_UUID);
				Method m = mDevice.getClass().getMethod("createRfcommSocket", new Class[] { int.class });
				tmp = (BluetoothSocket) m.invoke(mDevice, 1);
			} catch (Exception e) {
				Log.e(TAG, "createRfcommSocket() failed", e);
				mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERROR);
			}
			mSock = tmp;
		}

		 public void run() {
			int elmVersionMajor = 0;
			int elmVersionMinor = 0;
			@SuppressWarnings("unused")
			int elmVersionRelease = 0;

			try {
				mSock.connect();
				mIn = new BufferedReader(new InputStreamReader(mSock.getInputStream()), 128);
				mOut = mSock.getOutputStream();
			} catch (IOException e1) {
				Log.e(TAG, "connect() socket failed", e1);
				try {
					mSock.close();
				} catch (IOException e2) {
					Log.e(TAG, "close() of connect socket failed", e2);
				}
				mSock = null;
				mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERROR);
				return;
			}

			try {
				chat("AT", "", AT_TIMEOUT);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e2) {
				}
				// Warm Start
				String reply = chat("ATWS", "ELM327", ATZ_TIMEOUT);
				// parse reply to extract version information
				Pattern p = Pattern.compile("(?s)^.*ELM327 v(\\d+)\\.(\\d+)(\\w?).*$");
				Matcher m = p.matcher(reply);
				if (m.matches()) {
					elmVersionMajor = Integer.parseInt(m.group(1));
					elmVersionMinor = Integer.parseInt(m.group(2));
					if (m.group(3).length() > 0)
						elmVersionRelease = m.group(3).charAt(0);
				}
				// Echo ON
				chat("ATE1", "OK", AT_TIMEOUT);
				// Headers ON
				chat("ATH1", "OK", AT_TIMEOUT);
				// Allow long (>7 bytes) messages
				chat("ATAL", "OK", AT_TIMEOUT);
				// Spaces OFF
				if (elmVersionMajor >= 1 && elmVersionMinor >= 3)
					chat("ATS0", "OK", AT_TIMEOUT);
				// Select Protocol SAE J1850 VPW (10.4 kbaud)
				chat("ATSP2", "OK", AT_TIMEOUT);
			} catch (IOException e1) {
				mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERRORAT);
				// socket is already closed...
				mSock = null;
				return;
			}

			mHarleyDroidService.connected();
		}

		public void cancel() {
			try {
				if (mSock != null)
					mSock.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
			mSock = null;
		}
	}

	private class PollThread extends Thread {
		private boolean stop = false;

		public void run() {
			int errors = 0;

			setName("ELM327Interface: PollThread");
			try {
				// Monitor All
				chat("ATMA", "", AT_TIMEOUT);
			} catch (IOException e1) {
				mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERRORAT);
				// socket is already closed...
				mSock = null;
				return;
			}

			mHarleyDroidService.startedPoll();

			while (!stop) {
				String line;

				try {
					line = readLine(ATMA_TIMEOUT);
				} catch (IOException e1) {
					if (!stop)
						mHarleyDroidService.disconnected(HarleyDroid.STATUS_NODATA);
					// socket is already closed...
					mSock = null;
					return;
				}

				if (J1850.parse(myGetBytes(line), mHD))
					errors = 0;
				else
					++errors;

				if (errors > MAX_ERRORS) {
					try {
						writeLine("");
					} catch (IOException e1) {
					}
					try {
						mSock.close();
					} catch (IOException e2) {
					}
					mSock = null;
					mHarleyDroidService.disconnected(HarleyDroid.STATUS_TOOMANYERRORS);
					return;
				}
			}
			try {
				writeLine("");
			} catch (IOException e1) {
			}
		}

		public void cancel() {
			stop = true;
		}
	}

	private class SendThread extends Thread {
		private boolean stop = false;
		private boolean newData = false;
		private String mType[], mTA[], mSA[], mCommand[], mExpect[];
		private String mNewType[], mNewTA[], mNewSA[], mNewCommand[], mNewExpect[];
		private int mDelay, mNewDelay;

		public SendThread(String type[], String ta[], String sa[], String command[], String expect[], int delay) {
			setName("ELM327Interface: SendThread");
			mType = type;
			mTA = ta;
			mSA = sa;
			mCommand = command;
			mExpect = expect;
			mDelay = delay;
		}

		public void setData(String type[], String ta[], String sa[], String command[], String expect[], int delay) {
			synchronized (this) {
				mNewType = type;
				mNewTA = ta;
				mNewSA = sa;
				mNewCommand = command;
				mNewExpect = expect;
				mNewDelay = delay;
				newData = true;
			}
		}

		public void run() {
			String recv;

			mHarleyDroidService.startedSend();

			while (!stop) {

				synchronized (this) {
					if (newData) {
						mType = mNewType;
						mTA = mNewTA;
						mSA = mNewSA;
						mCommand = mNewCommand;
						mExpect = mNewExpect;
						mDelay = mNewDelay;
						newData = false;
					}
				}

				for (int i = 0; !stop && i < mCommand.length; i++) {
					try {
						chat("ATSH" + mType[i] + mTA[i] + mSA[i], "OK", AT_TIMEOUT);
						recv = chat(mCommand[i], mExpect[i], AT_TIMEOUT);
					} catch (IOException e) {
						mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERROR);
						// socket is already closed...
						mSock = null;
						return;
					}

					// split into lines
					if (!stop) {
						String lines[] = recv.split("\n");
						for (int j = 0; j < lines.length; ++j)
							J1850.parse(myGetBytes(lines[j]), mHD);
					}

					try {
						Thread.sleep(mDelay);
					} catch (InterruptedException e) {
					}
				}
			}
		}

		public void cancel() {
			stop = true;
		}
	}
}