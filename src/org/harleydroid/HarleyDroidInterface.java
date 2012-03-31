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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

public class HarleyDroidInterface implements J1850Interface
{
	private static final boolean D = false;
	private static final String TAG = HarleyDroidInterface.class.getSimpleName();

	//private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private static final int AT_TIMEOUT = 2000;
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

	public HarleyDroidInterface(HarleyDroidService harleyDroidService, BluetoothDevice device) {
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

			setName("HarleyDroidInterface: ConnectThread");
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
			int errors = 0, idxJ;

			setName("HarleyDroidInterface: PollThread");
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

				// strip off timestamp
				idxJ = line.indexOf('J');
				if (idxJ != -1) {
					if (J1850.parse(myGetBytes(line, idxJ + 1, line.length()), mHD))
						errors = 0;
					else
						++errors;
				}
				else
					++errors;

				if (errors > MAX_ERRORS) {
					try {
						mSock.close();
					} catch (IOException e2) {
					}
					mSock = null;
					mHarleyDroidService.disconnected(HarleyDroid.STATUS_TOOMANYERRORS);
					return;
				}
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
			int idxJ;

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

					byte[] data = new byte[3 + mCommand[i].length() / 2];
					data[0] = (byte)Integer.parseInt(mType[i], 16);
					data[1] = (byte)Integer.parseInt(mTA[i], 16);
					data[2] = (byte)Integer.parseInt(mSA[i], 16);
					for (int j = 0; j < mCommand[i].length() / 2; j++)
						data[j + 3] = (byte)Integer.parseInt(mCommand[i].substring(2 * j, 2 * j + 2), 16);

					String command = mCommand[i] + String.format("%02X", ((int)~J1850.crc(data)) & 0xff);

					if (D) Log.d(TAG, "send: " + mType[i] + "-" + mTA[i] + "-" +
							 mSA[i] + "-" + command + "-" + mExpect[i]);

					try {
						recv = chat(mType[i] + mTA[i] + mSA[i] + mCommand[i], mExpect[i], AT_TIMEOUT);
					} catch (IOException e) {
						mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERROR);
						// socket is already closed...
						mSock = null;
						return;
					}

					// split into lines and strip off timestamp
					if (!stop) {
						String lines[] = recv.split("\n");
						for (int j = 0; j < lines.length; ++j) {
							idxJ = lines[j].indexOf('J');
							if (idxJ != -1)
								J1850.parse(myGetBytes(lines[j], idxJ + 1, lines[j].length()), mHD);
						}
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