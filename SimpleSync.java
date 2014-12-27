/* Baseline synchronization method
 *
 * This code implements baseline synchronization method for the Ptolemy [1] simulation motes.
 * Upon launch the motes will start flashing in random order with the same time period.
 * Each time the mote flashes, it will send a radio beacon signal.
 * Upon receival of the beacon the mote will reset its period and flash immediately.
 * In such way a swarm of motes will synchronize their flashes in a couple of seconds.
 *
 * 2014 Naums Mogers
 *
 * References
 * [1] The Ptolemy Project, Berkeley, http://ptolemy.eecs.berkeley.edu/
 */
package lsi.wsn.sync;

import ptolemy.actor.TypedAtomicActor;
import ptolemy.actor.util.Time;
import ptolemy.data.IntToken;
import ptolemy.domains.wireless.kernel.WirelessIOPort;
import ptolemy.kernel.CompositeEntity;
import ptolemy.kernel.util.IllegalActionException;
import ptolemy.kernel.util.NameDuplicationException;
import ptolemy.vergil.icon.EditorIcon;
import ptolemy.vergil.kernel.attributes.EllipseAttribute;

@SuppressWarnings("serial")
public class SimpleSync extends TypedAtomicActor{
	// Ports
	protected WirelessIOPort input; 
	protected WirelessIOPort output;

	// Icon related
	protected EllipseAttribute _circle; 
	protected EditorIcon node_icon;
	protected String iconColor = "{0.0, 0.0, 0.0, 1.0}"; 	// black, LED off by default
	protected boolean stateLED = false; 					// state of the LED, off by default

	// Timings and synchronisation algorithm related
	protected Time nextFire;  					// time of the next scheduled firing
	protected Time nextFire2; 					// time of the firing scheduled after next one
	protected Time previousFire; 				// time of the previous firing
	protected double flashDuration = 0.5; 		// for how long LEDs are on, for visual effects only, not used by the synchronisation mechanism
	protected double FFC;		 				// Firing function constant: FFC = 1/delta. This is for easier representations
	protected double syncPeriod = 2.0 + 0.007;	// synchronisation period + worst-case delay introduced due to the nature of Firefly algorithm 
	protected int[] FFCs = {15, 24, 37, 50, 72, 98, 133, 181, 269, 300, 300};
	
	/*
	 * Constructor - sets up channels, builds the node icon.
	 */
	public SimpleSync(CompositeEntity container, String name)
	    throws NameDuplicationException, IllegalActionException  {
		super(container, name);
		
		input = new WirelessIOPort(this, "input", true, false);
		output = new WirelessIOPort(this, "output", false, true);
		input.outsideChannel.setExpression("Channel");
    	output.outsideChannel.setExpression("Channel");
    	
		buildIcon();	
	}
	
	/*
	 * Initialising handler - sets up initial timings, schedules first firing.
	 */
	public void initialize() throws IllegalActionException {
		 super.initialize();
		 
		 Time curTime = getDirector().getModelTime();
		 
		 // Schedule the first firing randomly within the first second of the simulation
		 nextFire = curTime.add(Math.random());
		 // Next firing
		 nextFire2 = nextFire.add(syncPeriod);
		 previousFire = curTime;
		 getDirector().fireAt(this, nextFire);
	}
	
	/*
	 * Firing handler - handles firing on internal alarm, firing on receiving 
	 * of synchronisation frame and turning off the LED.
	 */
	public void fire() throws IllegalActionException{		
		Time curTime = getDirector().getModelTime();

		if(input.hasToken(0)) {   // If another node has transmitted
			// Discard token
			input.get(0);
			
			long timePercentage = (long) (100 * curTime.subtract(previousFire).getDoubleValue() / syncPeriod);
			if (timePercentage < 50)
				FFC = (-1)*FFCs[(int)((timePercentage) / 5)]; // Divide and floor
			else
				FFC = FFCs[(int)((timePercentage-50) / 5)]; // Divide and floor
						
			// nextFire2 = nextFire2 - (curTime - previousFire)*delta
			// where delta = 1/FFC
			// (as per Firefly synchronisation algorithm)
			nextFire2 = nextFire2.subtract(curTime.subtract(previousFire).getDoubleValue()/FFC);
		}

		else if(curTime.compareTo(nextFire) != -1) { // Time to fire: transmit and blink LED
			// Transmit a message
			output.broadcast(new IntToken(0));
			
			// Turn on the LED
			this.setLED(true);
			
			// Schedule turning the LED off
			getDirector().fireAt(this, curTime.add(flashDuration)); 
			
			// Make a record of current time
			previousFire = curTime;
			// Schedule next firings
			nextFire = nextFire2;
			nextFire2 = nextFire.add(syncPeriod);
			getDirector().fireAt(this, nextFire); 
		}
		else
			// It's not incoming message, nor scheduled end of synchronisation period. 
			// The only remaining reason for calling this method is scheduled
			// switching the LED off.
			this.setLED(false);		
	}

	/* 
	 * LED setter - changes the filling colour of the icon.
	 */
	protected void setLED(boolean on) throws IllegalActionException {
		stateLED=on;
		if (on)
			_circle.fillColor.setToken("{1.0, 0.0, 0.0, 1.0}"); // red
		else
			_circle.fillColor.setToken("{0.0, 0.0, 0.0, 1.0}"); // black
	}
	
	/*
	 * Icon builder - sets the actor icon as a 20x20 pixel black circle. 
	 */
	protected void buildIcon() throws IllegalActionException, NameDuplicationException {
		node_icon = new EditorIcon(this, "_icon");
		_circle = new EllipseAttribute(node_icon, "_circle");
		_circle.centered.setToken("true");
		_circle.width.setToken("20");
		_circle.height.setToken("20");
		_circle.fillColor.setToken(this.iconColor);
		_circle.lineColor.setToken("{0.0, 0.0, 0.0, 1.0}");
		node_icon.setPersistent(false);
	}	
}
