/* Baseline synchronization method
 *
 * This code implements baseline synchronization method for the wireless Mote Runner [1] devises.
 * Upon launch the motes will start flashing in random order with the same time period.
 * Each time the mote flashes, it will send a radio beacon signal.
 * Upon receival of the beacon the mote will reset its period and flash immediately.
 * In such way a swarm of motes will synchronize their flashes in a couple of seconds.
 *
 * 2014 Naums Mogers
 *
 * References
 * [1] IBM Mote Runner, http://www.zurich.ibm.com/moterunner/
 */

package embs;

import com.ibm.saguaro.system.*;

public class baselineSync {
	private static final int PAN_ID = 0x41;
	// Random mote ID
	private static final int MOTE_ADDR = Util.rand8();
	private static final byte PAN_CHANNEL = 3;
	private static Radio radioInstance;
	private static byte[] frame;
	private static int frame_size = 7;
	// Time of the next scheduled firing, in ticks
	private static long nextFire;
	// For how long LEDs are on, in ticks
	private static long flashDuration = Time.toTickSpan(Time.MILLISECS, 500);
	// Synchronisation period, in ticks
	private static long syncPeriod = Time.toTickSpan(Time.SECONDS, 2);

	private static Timer ledTimer;	
	
	/**
	 * Class constructor.
	 */
	static {		
		// Set handler for exiting the assembly execution
		// to release resources properly
		Assembly.setSystemInfoCallback(new SystemInfo(null) {
			public int invoke(int type, int info) {
				return baselineSync.onExit(type, info);
			}
		});
		
		baselineSync.initRadio();		
		baselineSync.initFiring();		
		baselineSync.initLED();
	}
	
	/**
 	 * Sets up radio, prepares frame.
	 */
	private static void initRadio() {
		// Initialize radio
		radioInstance = new Radio();
		radioInstance.open(Radio.DID, null, 0, 0);	
		radioInstance.setPanId(PAN_ID, true);
		radioInstance.setShortAddr(MOTE_ADDR);
		radioInstance.setChannel(PAN_CHANNEL);
		
		// Set receive handler
		radioInstance.setRxHandler(new DevCallback(null) {
				public int invoke(int flags, byte[] data, int len, int info, long time) {
					return baselineSync.onReceive(flags, data, len, info, time);
				}
			});
		
		frame = new byte[frame_size];
		frame[0] = Radio.FCF_BEACON;
		// Only transmit source PAN ID and address
		frame[1] = Radio.FCA_SRC_SADDR;
		Util.set16le(frame, 3, PAN_ID);
		Util.set16le(frame, 5, MOTE_ADDR);
		// Empty payload
	}
	
	/**
 	 * Sets up firing timer.
	 */
	private static void initFiring() {
		// Schedule the first firing randomly within the first 
		// 1023 milliseconds of execution
		nextFire = Time.currentTicks() + 
					Time.toTickSpan(Time.MILLISECS, (long)Util.rand8() << 2);
		radioInstance.startRx(Device.ASAP, 0, nextFire);
	}
	
	/**
 	 * Sets up LED timer.
	 */
	private static void initLED() {
		ledTimer = new Timer();
		ledTimer.setCallback(new TimerEvent(null) {
				public void invoke(byte param, long time) {
					baselineSync.onLEDExpiry(param, time);
				}
			});
		baselineSync.toggleLED(0);
	}
	
	/**
 	 * Changes LED state.
	 */	
	private static void toggleLED(int state) {
        LED.setState((byte)2, (byte)state);
    }
    
	/**
 	 * Firing handler drives LEDs and notifies other motes
 	 * when necessary that period has elapsed.
	 */	
	public static void blinkAndTransmit(boolean earlyFire) {		
		// Send a frame to everybody unless synchronizing with another node.
		if (!earlyFire)
			radioInstance.transmit(Device.ASAP | Radio.TXMODE_CCA, frame, 0, frame_size, 0);
		
		baselineSync.toggleLED(1);
		
		// Schedule switching off LED
		ledTimer.setAlarmBySpan(flashDuration);
	}

	/**
 	 * Receiving handler will be fired in two cases:
 	 * 1. If synchronization period has elapsed - "data" will contain null
 	 * 2. If another mote has fired - "data" will contain incoming frame
	 */	
	public static int onReceive(int flags, byte[] data, int len, int info, long time) {
		if (data == null) {
			// End of receiving period, i.e. sync period
			baselineSync.blinkAndTransmit(false);
			nextFire = Time.currentTicks() + syncPeriod;
			radioInstance.startRx(Device.ASAP, 0, nextFire);
		}
		else if (len == frame_size) {		
			// We received signal from another mote earlier,
			// so fire now and start next period
			baselineSync.blinkAndTransmit(true);
			nextFire = Time.currentTicks() + syncPeriod;
			radioInstance.startRx(Device.ASAP, 0, nextFire);
		}
		
		return 0;
	}
	
	/**
 	 * LED handler turns off LED and exits deaf mode.
	 */	
	public static void onLEDExpiry(byte param, long time) {
		baselineSync.toggleLED(0);
	}
	
	/**
 	 * Garbage collector - releases radio
	 */	
	private static int onExit(int type, int info) {
		if (type == Assembly.SYSEV_DELETED)
			radioInstance.close();
		return 0;
	}
}
