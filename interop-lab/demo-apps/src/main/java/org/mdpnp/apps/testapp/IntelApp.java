package org.mdpnp.apps.testapp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.Collection;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import org.mdpnp.apps.gui.swing.DevicePanelFactory;
import org.mdpnp.comms.Gateway;
import org.mdpnp.comms.GatewayListener;
import org.mdpnp.comms.IdentifiableUpdate;
import org.mdpnp.comms.Identifier;
import org.mdpnp.comms.data.enumeration.EnumerationUpdate;
import org.mdpnp.comms.data.identifierarray.IdentifierArrayUpdate;
import org.mdpnp.comms.data.identifierarray.MutableIdentifierArrayUpdate;
import org.mdpnp.comms.data.identifierarray.MutableIdentifierArrayUpdateImpl;
import org.mdpnp.comms.data.text.MutableTextUpdate;
import org.mdpnp.comms.data.text.MutableTextUpdateImpl;
import org.mdpnp.comms.data.textarray.TextArrayUpdate;
import org.mdpnp.comms.nomenclature.ConnectedDevice;
import org.mdpnp.comms.nomenclature.Device;
import org.mdpnp.comms.nomenclature.SerialDevice;
import org.mdpnp.comms.nomenclature.ConnectedDevice.ConnectionType;
import org.mdpnp.devices.nonin.pulseox.DemoPulseOx;

import com.jeffplourde.util.Arrays;

public class IntelApp {
	public static void main(String[] args) {
		final Gateway gateway = new Gateway();
		final JFrame frame = new DemoFrame("Nonin Demo");
		frame.getContentPane().setBackground(Color.black);
		frame.getContentPane().setLayout(new BorderLayout());
		
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(640, 480);
		
//		Device simpox = new SimulatedPulseOximeterImpl(gateway);
		Device noninpox = new DemoPulseOx(gateway);
		
		
		gateway.addListener(new GatewayListener() {
			@Override
			public void update(IdentifiableUpdate<?> update) {
				if(Device.GET_AVAILABLE_IDENTIFIERS.equals(update.getIdentifier())) {
					IdentifierArrayUpdate tau = (IdentifierArrayUpdate) update;
					Collection<org.mdpnp.apps.gui.swing.DevicePanel> panels = DevicePanelFactory.findPanel((IdentifierArrayUpdate)update, gateway, update.getSource());
					frame.getContentPane().add(panels.iterator().next(), BorderLayout.CENTER);
					frame.setVisible(true);
					
					if(Arrays.indexOf(tau.getValue(), ConnectedDevice.CONNECTION_TYPE)>=0) {
						MutableIdentifierArrayUpdate mtau = new MutableIdentifierArrayUpdateImpl(Device.REQUEST_IDENTIFIED_UPDATES);
						mtau.setValue(new Identifier[] {ConnectedDevice.CONNECTION_TYPE});
						mtau.setTarget(update.getSource());
						mtau.setSource("*");
						gateway.update(mtau);
					}
					
					 
				} else if(ConnectedDevice.CONNECTION_TYPE.equals(update.getIdentifier())) {
					EnumerationUpdate eu = (EnumerationUpdate) update;
					ConnectedDevice.ConnectionType ct = (ConnectionType) eu.getValue();
					MutableTextUpdate mtu = null;
					switch(ct) {
					case Simulated:
						 mtu = new MutableTextUpdateImpl(ConnectedDevice.CONNECT_TO, "");
						 mtu.setTarget(update.getSource());
						 mtu.setSource("*");
						 gateway.update(mtu);
						 break;
					case Network:
						// TODO check for cancellation of dialog
						mtu = new MutableTextUpdateImpl(ConnectedDevice.CONNECT_TO, JOptionPane.showInputDialog(frame, "Please enter a network address"));
						 mtu.setTarget(update.getSource());
						 mtu.setSource("*");
						 gateway.update(mtu);
						break;
					case Serial:
						MutableIdentifierArrayUpdate mtau = new MutableIdentifierArrayUpdateImpl(Device.REQUEST_IDENTIFIED_UPDATES);
						mtau.setValue(new Identifier[] {SerialDevice.SERIAL_PORTS});
						mtau.setTarget(update.getSource());
						mtau.setSource("*");
						gateway.update(mtau);
						break;
					}
				} else if(SerialDevice.SERIAL_PORTS.equals(update.getIdentifier())) {
					TextArrayUpdate tau = (TextArrayUpdate) update;
					// check for cancellation of dialog
					Object serial = JOptionPane.showInputDialog(frame, "Serial Port", "Serial Port", JOptionPane.QUESTION_MESSAGE, null, tau.getValue(), tau.getValue()[0]);
					MutableTextUpdate mtu = new MutableTextUpdateImpl(ConnectedDevice.CONNECT_TO, serial.toString());
					 mtu.setTarget(update.getSource());
					 mtu.setSource("*");
					 gateway.update(mtu);
				}
				
			}
		});
		
		MutableTextUpdate mtu = new MutableTextUpdateImpl(Device.REQUEST_AVAILABLE_IDENTIFIERS, "");
		mtu.setSource("*");
		mtu.setTarget("*");
		gateway.update(mtu);
		
		
		
		
	}
}