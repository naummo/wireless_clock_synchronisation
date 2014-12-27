/* Firefly Synchronization Method
 *
 * This code implements a Firefly Synchronization method as described by Werner-Allen et al [1]
 * for the Wireles Mote Runner [2] devices.
 * Upon launch the motes will start flashing in random order with the same time period.
 * Each time the mote flashes, it will send a radio beacon signal.
 * Upon receival of the beacon the mote will adjust it period by a variable offset.
 * The offset can be either positive or negative and of different value depending
 * on how desynchronized the motes are.
 * 4 motes will synchronize in 35 seconds average.
 *
 * 2014 Naums Mogers
 *
 * References
 * [1] Geoffrey Werner-Allen, Geetika Tewari, Ankit Patel, Matt Welsh, and Radhika Nagpal. 
       Firefly-inspired sensor network synchronicity with realistic radio effects. 
       In: Proceedings of the 3rd Int Conf on Embedded Networked Sensor Systems (SenSys '05)
 * [2] IBM Mote Runner: http://www.zurich.ibm.com/moterunner/
*/

package embs;

import com.ibm.saguaro.system.*;

public class fireflySync {
	// Radio related
	@Immutable
	private static final int PAN_ID = 0x28;
	// Random mote ID
	@Immutable
	private static final int MOTE_ADDR = Util.rand8();
	@Immutable
	private static final byte PAN_CHANNEL = 3;
	private static Radio radioInstance;
	private static byte[] frame;
	@Immutable
	private static final int frame_size = 7;
	
	// Time of the next scheduled firing, in ticks
	private static long nextFire;
	// Time of the firing scheduled after next, in ticks
	private static long nextFire2;
	// Time of the previous firing
	private static long previousFire;
	// For how long LEDs are on, in ticks
	@Immutable
	private static final long flashDuration = Time.toTickSpan(Time.MILLISECS, 500);
	// Synchronisation period + worst-case delay introduced due to the nature of Firefly algorithm, in ticks
	@Immutable
	private static final long syncPeriod = Time.toTickSpan(Time.MILLISECS, 2000 + 25);
	
	// Firing function constant: FFC = 1/delta.
	private static int FFC;
	// A set of FFC values for different mistiming degrees
	// The smaller mistiming is, the bigger FFC value will be used,
	// hence synchronization period will be changed to a lesser extent.
	@Immutable
	private static final int[] FFCs = {15, 17, 20, 23, 28, 34, 42, 53, 73, 80, 80};
	
	// Timers
	private static TimerEvent periodEndEvent;
	private static Timer ledTimer;
	private static Timer transmitterTimer;
	
	// Collision detection related variables
	// Status of the channel, for collision detection
	private static boolean channelMayBeBusy;
	// Number of failed transmissions due to collisions
	private static int failedTransmissions;
	// Time it takes to send a frame
	// It takes approximately 0.2 milliseconds to send 8-byte frame, so wait the least
	// period possible - 1 millisecond.
	@Immutable 
	private static final long transmissionDuration = Time.toTickSpan(Time.MILLISECS, 1);
	// Limit on how much time can mote wait for a free window in a channel
	@Immutable 
	private static final long waitMaximum = Time.toTickSpan(Time.MILLISECS, 100);
	// Amount of random numbers to store 
	@Immutable
	private static final int numOfRandoms = 8;
	// Random numbers for collision prevention. Computing them
	// at time of each collision is expensive, so we'll stock
	// them up once per synchronization period
	private static int[] randomNumbers;
	
	/**
	 * Class constructor. 
	 */
	static {		
		// Set handler for exiting the assembly execution
		// to release resources properly
		Assembly.setSystemInfoCallback(new SystemInfo(null) {
			public int invoke(int type, int info) {
				return fireflySync.onExit(type, info);
			}
		});
		
		fireflySync.initRadio();		
		fireflySync.initFiring();		
		fireflySync.initLED();
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
					return fireflySync.onReceive(flags, data, len, info, time);
				}
			});
		
		frame = new byte[frame_size];
		frame[0] = Radio.FCF_BEACON;
		// Only transmit source PAN ID and mote address
		frame[1] = Radio.FCA_SRC_SADDR;
		Util.set16le(frame, 3, PAN_ID);
		Util.set16le(frame, 5, MOTE_ADDR);
		// Empty payload
		
		// Set timer for transmitions - it will be used for collision detection
		transmitterTimer = new Timer();
		transmitterTimer.setCallback(new TimerEvent(null) {
				public void invoke(byte param, long time) {
					fireflySync.onTransmitTime(param, time);
				}
			});
	}
	
	/**
 	 * Sets up firing using receiving window with a size of the synchronization period
	 */
	private static void initFiring() {
		long curTime = Time.currentTicks();
		
		// Schedule the first firing randomly within the first 
		// 1023 milliseconds of execution
		nextFire = curTime + Time.toTickSpan(Time.MILLISECS, (long)Util.rand8() << 2);
		nextFire2 = nextFire + syncPeriod;
		previousFire = curTime;
		radioInstance.startRx(Device.ASAP, 0, nextFire);
	}
	
	/**
 	 * Sets up LED timer.
	 */
	private static void initLED() {
		ledTimer = new Timer();
		ledTimer.setCallback(new TimerEvent(null) {
				public void invoke(byte param, long time) {
					fireflySync.onLEDExpiry(param, time);
				}
			});
		fireflySync.toggleLED(0);
	}
	
	/**
 	 * Changes LED state.
	 */	
	private static void toggleLED(int state) {
        LED.setState((byte)2, (byte)state);
    }
    
	/**
 	 * Stocks up random numbers proactively. Util.rand8() generates random
 	 * number in a range [0,255] and this function maps it to a smaller number (maximum parameter).
	 */	
	private static void generateRandomNumbers(int maximum) {
		int r;
		int k = (int)(255 / maximum);
		for (int i = 0; i < numOfRandoms; i++) {
			r = Util.rand8();
			for (int l = 0; l < maximum; l++)
				if (r < k*l) {
					randomNumbers[i] = l;
					break;
				}
		}
	}
	
	/**
 	 * Max() returns maximum of two numbers.
	 */	
	public static long max(long n1, long n2) {
		if (n1 >= n2)
			return n1;
		else
			return n2;
	}

	/**
 	 * Recieving handler will be fired when syncronisation period has elapsed
 	 * i.e. on the end of receiving period.
 	 * If received a frame from another mote, next syncronisation period is adjusted.
	 */	
	public static int onReceive(int flags, byte[] data, int len, int info, long time) {
		long curTime = Time.currentTicks();
		if (data == null) {
			// End of receiving period, i.e. sync period
			// Send a frame to everybody
			
			// Schedule transmission and wait a bit to check if channel is free
			channelMayBeBusy = false;
			failedTransmissions = 0;
			transmitterTimer.setAlarmBySpan(transmissionDuration);
			
			// Turn on LED
			fireflySync.toggleLED(1);
			
			// Schedule turning off LED
			ledTimer.setAlarmBySpan(flashDuration);
			
			previousFire = curTime;
			// Schedule next firings
			nextFire = nextFire2;
			nextFire2 = nextFire + syncPeriod;
			
			// Start receiver again
			radioInstance.startRx(Device.ASAP, 0, nextFire);
			
			if (failedTransmissions > 1)
				// Multiple signal collisions happened and some of
				// the random numbers were used. Stock up more in range of [0, 8].
				generateRandomNumbers(8);
		}
		else if (len == frame_size) {
			// Mote received signal from another mote earlier than its internal
			// synchronization period has ended
			
			// If preparing to transmit
			channelMayBeBusy = true;
			
			// Adjust next synchronization period	
			long timePercentage = 100 * (curTime - previousFire) / syncPeriod;
			if (timePercentage < 50)
				FFC = (-1)*FFCs[(int)((timePercentage) / 5)]; // Divide and floor
			else
				FFC = FFCs[(int)((timePercentage-50) / 5)]; // Divide and floor
			
			nextFire2 = nextFire2 - (curTime - previousFire) / FFC;			
		}		
		return 0;
	}
	
	/**
 	 * LED handler turns off LED and exits deaf mode.
	 */	
	public static void onLEDExpiry(byte param, long time) {
		fireflySync.toggleLED(0);
	}
	
	/**
 	 * Transmission handler handles collision detection and frame transmission.
	 */	
	public static void onTransmitTime(byte param, long time) {
		if (!channelMayBeBusy) {
			// Channel is free, let's transmit
			radioInstance.transmit(Device.ASAP | Radio.TXMODE_CCA, frame, 0, frame_size, 0);
			// Start receiver again
			radioInstance.startRx(Device.ASAP, 0, nextFire);
		}
		else {
			// Channel might be congested
			failedTransmissions = failedTransmissions + 1;
			// Increase waiting time exponentially as transmissions continue to fail
			// and change it randomly in predefined range. For that 
			// use pregenerated random numbers.
			// Do not wait more than waitMaximum
			transmitterTimer.setAlarmBySpan(max(waitMaximum, 
												transmissionDuration 
															* randomNumbers[(failedTransmissions-1) % numOfRandoms] 
															* failedTransmissions^2));
		}
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
