package com.docvolt.usbcontrol;

import static com.docvolt.usbcontrol.UsbIOService.*;

import android.util.Log;

public class Sketch {
    private final String TAG = "usbsketch";
    private final byte[] mOutPins = {PIN_TXD, PIN_DTR, PIN_RTS, PIN_RXD};
    private final int MAX_DELAY = 250;
    public final static int VPORT_ANALOG = 0;

    void setup() {
        for (byte mOutputPin : mOutPins) {
            pinMode(mOutputPin, OUTPUT);
        }
    }

    void loop() {
        for (byte outPin : mOutPins) {
            int delay = MAX_DELAY - analogRead(VPORT_ANALOG);
            if(digitalRead(outPin + VPIN_BASE) == 1)
            {
                digitalWrite(outPin, digitalRead(VPIN_BASE + outPin));
                delay(2*delay);
                digitalWrite(outPin, 0);
            }
            //delay(delay);
        }
    }
}