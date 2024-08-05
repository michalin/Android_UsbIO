package com.docvolt.usbcontrol;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public class UsbIOService extends Service{

    interface ArduinoListener {
        /* This gets called when a physical or virtual pin is written to.*/
        void onPinChange();
    }

    private static final String TAG = "usbioservice";

    // USB permission Intents
    public static final String ACTION_USB_NOT_SUPPORTED = "com.docvolt.UsbIOService.USB_NOT_SUPPORTED";
    public static final String ACTION_NO_VALID_USB = "com.docvolt.UsbIOService.NO_VALID_USB";
    private static final String ACTION_USB_PERMISSION = "com.docvolt.UsbIOService.USB_PERMISSION";
    public static final String ACTION_USB_PERMISSION_GRANTED = "com.docvolt.UsbIOService.USB_PERMISSION_GRANTED";
    public static final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.docvolt.UsbIOService.USB_PERMISSION_NOT_GRANTED";
    public static final String ACTION_USB_DISCONNECTED = "com.docvolt.UsbIOService.USB_DISCONNECTED";
    public static final String ACTION_DIGITAL_WRITE = "com.docvolt.UsbIOService.DIGITAL_WRITE";
    private static final int VENDOR_ID = 0x0403; //FT232R
    private static final int PRODUCT_ID = 0x6001;

    // The Lower 8 Bits represent the FT232 IO values. The upper 24 bits represent virtual IO values used by the app
    private static final AtomicInteger mPins = new AtomicInteger(0xFFFFFF00); //Output
    private static int mMode = 0xFFFFFF00; //Input mode {INPUT | OUTPUT}

    // USB control constants
    private static final int FTDI_DEVICE_OUT_REQTYPE = 0x40;
    private static final int FTDI_DEVICE_IN_REQTYPE = 0xC0;
    private static final int SIO_SET_BITMODE_REQUEST = 0x0b;
    private static final int SIO_READ_PINS_REQUEST = 0x0c;
    private static final int BITMODE_SYNCBB = 0x04; //8-Bit control register for output values

    // FT232R IO numbers
    public static final byte PIN_TXD = 0;
    public static final byte PIN_RXD = 1;
    public static final byte PIN_RTS = 2;
    public static final byte PIN_CTS = 3;
    public static final byte PIN_DTR = 4;
    public static final byte PIN_DSR = 5;
    public static final byte PIN_DCD = 6;
    public static final byte PIN_RI = 7;
    public static final byte MAX_FT_PIN_NUMBER = 7;
    public static final byte VPIN_BASE = 8;

    private final IBinder binder = new UsbBinder();

    private UsbManager mUsbManager;
    private UsbDevice mUsbDevice;
    private UsbInterface mUsbInterface;
    private UsbDeviceConnection mUsbDevConnection;
    private IOThread mIOLoop = null;

    private ArduinoListener mArduinoFunctions;
    public static final int INPUT = 0;
    public static final int OUTPUT = 1;
    private static final ConcurrentHashMap<Integer, Integer> mAnalogPins = new ConcurrentHashMap<>();

    private final Sketch sketch = new Sketch();

    /*static {
        System.loadLibrary("usbcontrol");
    }*/
    //public native void csetup();
    //public native void cloop();

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(usbReceiver, filter);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        initIODevice();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        unregisterReceiver(usbReceiver);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                Log.d(TAG, "USB_DEVICE_ATTACHED");
                initIODevice();
                // DeviceAttached
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                Log.d(TAG, "USB_DEVICE_DETACHED");
                // Usb device was disconnected. send an intent to the Main Activity
                if(mIOLoop != null)
                    mIOLoop.stopThread();
                context.sendBroadcast(new Intent(ACTION_USB_DISCONNECTED));
            } else if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                Log.d(TAG, "ACTION_USB_PERMISSION");
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)) {
                    context.sendBroadcast(new Intent(ACTION_USB_PERMISSION_GRANTED));
                    mUsbInterface = mUsbDevice.getInterface(0);
                    mUsbDevConnection = mUsbManager.openDevice(mUsbDevice);
                    mIOLoop = new IOThread();
                    mIOLoop.setName("usbIOThread");
                    //mIOLoop.setPriority(Thread.MAX_PRIORITY);
                    mIOLoop.start();
                } else {// User not accepted our USB connection. Send an Intent to the Main Activity
                    context.sendBroadcast(new Intent(ACTION_USB_PERMISSION_NOT_GRANTED));
                }
            }
        }
    };

    /*
        This function checks if the device is a FT232R device and checks the permissions.
        Call this to initialize the device.
    */
    public void initIODevice() {
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
            Log.d(TAG, String.format("Device detected: %s:%s", entry.getValue().getVendorId(), entry.getValue().getProductId()));
            if (entry.getValue().getVendorId() == VENDOR_ID && entry.getValue().getProductId() == PRODUCT_ID) {
                Log.d(TAG, "FT 232R Device found");
                PendingIntent mPendingIntent = PendingIntent.getBroadcast(UsbIOService.this, 0, new Intent(ACTION_USB_PERMISSION), 0);
                mUsbManager.requestPermission(entry.getValue(), mPendingIntent);
                mUsbDevice = entry.getValue();
            }
        }
        Log.d(TAG, "No or no valid USB device");
        sendBroadcast(new Intent(ACTION_NO_VALID_USB));
    }

    public class UsbBinder extends Binder {
        public UsbIOService getService() {
            return UsbIOService.this;
        }
    }
    /* Thread of the loop() function */
    private class LoopThread extends Thread {
        private boolean isRunning = true;
        @Override
        public void run () {
            while (isRunning) {
                sketch.loop();
            }
        }
        public void stopThread() {
            isRunning = false;
        }
    }

    /*This reads and writes FT232 pins and virtual pins*/
    private class IOThread extends Thread {
        private boolean isRunning;
        private int _pins = 0;
        private byte _rdvals = (byte)0xFF;
        private final byte[] rdvals = {0};
        Handler mHandler = new Handler();
        private final LoopThread loopThread = new LoopThread();

        @Override
        public void run() {
            Log.d(TAG, "run");
            sketch.setup();
            loopThread.setName("loopThread");
            loopThread.start();
            //csetup();
            isRunning = true;
            //mUsbDevConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_SET_BITMODE_REQUEST, (BITMODE_SYNCBB << 8), mUsbInterface.getId(), null, 0, 0);
            while (isRunning) {
                //Write to FT232R ports
                byte writeval = (byte) (mPins.get() & mMode);
                if(writeval != (_pins & mMode)) {
                    int bang_val = (BITMODE_SYNCBB << 8) + writeval;
                    if (mUsbDevConnection.controlTransfer(FTDI_DEVICE_OUT_REQTYPE, SIO_SET_BITMODE_REQUEST, bang_val, mUsbInterface.getId(), null, 0, 0) == -1) {
                        Log.e(TAG, "Error, could not write pin");
                        isRunning = false;
                    }
                }

                //Read from FT232R ports
                if(mUsbDevConnection.controlTransfer(FTDI_DEVICE_IN_REQTYPE, SIO_READ_PINS_REQUEST, 0, mUsbInterface.getId(), rdvals, 1, 0) == -1) {
                    Log.e(TAG,"Error, could not read pin");
                    isRunning = false;
                }
                rdvals[0] = (byte) (rdvals[0] & ~mMode);
                if(rdvals[0] != _rdvals){
                    int pin_changed = 0x00FF & (_rdvals ^ rdvals[0]);
                    int val_changed = rdvals[0] & pin_changed;
                    //Log.d(TAG, String.format("val_changed: %X", val_changed));
                    if(val_changed != 0)
                        mPins.set(mPins.get() &~pin_changed);
                        //mPins &= ~pin_changed;
                    else
                        mPins.set(mPins.get() | pin_changed);
                        //mPins |= pin_changed;
                    _rdvals = rdvals[0];
                }

                if(mPins.get() != _pins) {
                    //Log.d(TAG, String.format("mPins: %X", mPins));
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mArduinoFunctions.onPinChange();
                        }
                    });
                }
                _pins = mPins.get();
                //Log.d(TAG, "IOTHREAD-->");
                //sketch.loop();
            }
        }
        public void stopThread() {
            Log.d(TAG, "Stopping threads");
            loopThread.stopThread();
            isRunning = false;
        }
    }

    public void setArduinoFunctionsCB(ArduinoListener cb) {
        mArduinoFunctions = cb;
    }

    //Arduino-like functions
    public static int digitalRead(int pin) {
        int b = mPins.get() & (1 << pin);
       //Log.d(TAG,"digitalRead: " + b);
        return (b == 0)? 1 : 0;
    }

    /* Arduino-like function definitions */

    public static void digitalWrite(int pin, int value) {
        if(((1 << pin) & mMode) == 0)
            return; //If pin is not masked as output, do nothing
        if(value == 0)
            mPins.set(mPins.get() | (1 << pin)); //On
            //mPins |= 1 << pin; //Only affect pins that are masked as input (0)
        else
            mPins.set(mPins.get() & ~(1 << pin)); //Off
            //mPins &= ~(1 << pin); //On
    }

    public static void pinMode(int pin, int mode) {
        if(pin > MAX_FT_PIN_NUMBER) {
            Log.e(TAG, "pinMode: pin out of range");
            return;
        }
        if(mode == INPUT) {
            digitalWrite(pin, 1); //Default High (pullup)
            mMode &= ~(1 << pin);
        }
        else {
            mMode |= 1 << pin;
            digitalWrite(pin, 0); //Default Low
        }
        Log.d(TAG, "pinMode: " + mMode);
    }

    public static void analogWrite(int pin, int value){
        mAnalogPins.put(pin, value);
        /*for(Map.Entry<Integer, Integer> item : mAnalogPins.entrySet()){
            Log.d(TAG, "key: " + item.getKey() + " value: " + item.getValue());*/
    }

    public static int analogRead(int pin) {
        Integer value = mAnalogPins.get(pin);
        if(value == null) {
            Log.e(TAG, "analogRead: Tried to read a nonexistent pin. Call analog Write to create one");
            return 0;
        }
        return value;
    }

    public static void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}