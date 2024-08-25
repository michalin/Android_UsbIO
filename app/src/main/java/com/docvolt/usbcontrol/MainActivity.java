package com.docvolt.usbcontrol;

import static com.docvolt.usbcontrol.UsbIOService.*;
import static com.docvolt.usbcontrol.Sketch.VPORT_ANALOG;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, ArduinoListener, SeekBar.OnSeekBarChangeListener {
    private final String TAG = "MainActivity";
    UsbIOService  mUsbIOService = new UsbIOService();

    //SurfaceHolder mPreview;
    //Camera mCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cb_rsd), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        //Button btn_connect = findViewById(R.id.Connect);

        findViewById(R.id.btn_txd).setOnClickListener(this);
        findViewById(R.id.btn_dtr).setOnClickListener(this);
        findViewById(R.id.btn_rts).setOnClickListener(this);
        findViewById(R.id.btn_rxd).setOnClickListener(this);
        findViewById(R.id.cb_txd).setOnClickListener(this);
        findViewById(R.id.cb_dtr).setOnClickListener(this);
        findViewById(R.id.cb_rts).setOnClickListener(this);
        findViewById(R.id.cb_rxd).setOnClickListener(this);
        ((SeekBar)findViewById(R.id.sb_speed)).setOnSeekBarChangeListener(this);

        bindService(new Intent(this, UsbIOService.class), connection, Context.BIND_AUTO_CREATE);

        IntentFilter usbIntentFilter = new IntentFilter();
        usbIntentFilter.addAction(UsbIOService.ACTION_USB_PERMISSION_GRANTED);
        usbIntentFilter.addAction(UsbIOService.ACTION_NO_VALID_USB);
        usbIntentFilter.addAction(UsbIOService.ACTION_USB_DISCONNECTED);
        usbIntentFilter.addAction(UsbIOService.ACTION_USB_NOT_SUPPORTED);
        usbIntentFilter.addAction(UsbIOService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, usbIntentFilter);
        //findViewById(R.id.btn_dtr).setBackgroundColor(0xFFFF0000);
        analogWrite(VPORT_ANALOG,0);

    }

    @Override
    public void onPinChange() {
        //Log.d(TAG, "onPinChange");

        //Change text color of buttons
        ((ToggleButton) findViewById(R.id.btn_txd)).setChecked(digitalRead(PIN_TXD) == 1);
        ((ToggleButton)findViewById(R.id.btn_dtr)).setChecked(digitalRead(PIN_DTR) == 1);
        ((ToggleButton)findViewById(R.id.btn_rts)).setChecked(digitalRead(PIN_RTS) == 1);
        ((ToggleButton)findViewById(R.id.btn_rxd)).setChecked(digitalRead(PIN_RXD) == 1);

        //Radiobuttons representing inputs
        ((RadioButton)findViewById(R.id.rb_ri)).setChecked(digitalRead(PIN_RI) == 1);
        ((RadioButton)findViewById(R.id.rb_dsr)).setChecked(digitalRead( PIN_DSR) == 1);
        ((RadioButton)findViewById(R.id.rb_dcd)).setChecked(digitalRead(PIN_DCD) == 1);
        ((RadioButton)findViewById(R.id.rb_cts)).setChecked(digitalRead(PIN_CTS) == 1);
    }

    @Override
    public void onClick(View view) {
        int isChecked = ((CompoundButton)view).isChecked()? 1:0;
        if(view.getId() == R.id.btn_txd){
            digitalWrite(PIN_TXD, isChecked);
        } else if(view.getId() == R.id.cb_txd){
            digitalWrite(VPIN_BASE+ PIN_TXD, isChecked);
        }
        else if(view.getId() == R.id.btn_dtr){
            digitalWrite(PIN_DTR, isChecked);
        } else if(view.getId() == R.id.cb_dtr){
            digitalWrite(VPIN_BASE + PIN_DTR, isChecked);
        }
        else if(view.getId() == R.id.btn_rts) {
            digitalWrite(PIN_RTS, isChecked);
        } else if(view.getId() == R.id.cb_rts) {
            digitalWrite(VPIN_BASE + PIN_RTS, isChecked);
        }
        else if(view.getId() == R.id.btn_rxd) {
            digitalWrite(PIN_RXD, isChecked);
        } else if(view.getId() == R.id.cb_rxd) {
            digitalWrite(VPIN_BASE + PIN_RXD, isChecked);
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
        Log.d("APP", String.format("onProgressChanged i: %d b: %b", i, b));
        analogWrite(VPORT_ANALOG, i);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        Log.d("APP", "onStartTrackingTouch");
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        Log.d("APP", "onStopTrackingTouch");
    }

    /*This handles authentication of USB devices*/
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        private AlertDialog no_usb_dialog= null;
        @Override
        public void onReceive(Context context, Intent intent) {
            AlertDialog.Builder no_usb_dialog_builder = new AlertDialog
                    .Builder(MainActivity.this)
                    .setPositiveButton(R.string.retry, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            mUsbIOService.initIODevice();
                        }
                    })
                    .setMessage(R.string.NO_USB);

            switch (Objects.requireNonNull(intent.getAction())) {
                case UsbIOService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    if (no_usb_dialog != null && no_usb_dialog.isShowing())
                        no_usb_dialog.dismiss();
                    break;
                case UsbIOService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbIOService.ACTION_NO_VALID_USB: // NO USB CONNECTED
                    if(no_usb_dialog == null || !no_usb_dialog.isShowing()) {
                        no_usb_dialog = no_usb_dialog_builder.create();
                        no_usb_dialog.show();
                    }
                    break;
                case UsbIOService.ACTION_USB_DISCONNECTED:
                    if(no_usb_dialog == null || !no_usb_dialog.isShowing()) {
                        no_usb_dialog = no_usb_dialog_builder.create();
                        no_usb_dialog.show();
                    }
                    break;
                case UsbIOService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.d(TAG, "onServiceConnected");
            UsbIOService.UsbBinder binder = (UsbIOService.UsbBinder) service;
            mUsbIOService = binder.getService();
            mUsbIOService.setArduinoFunctionsCB(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "ServiceDisconnected");
            mUsbIOService = null;
        }
    };


};


