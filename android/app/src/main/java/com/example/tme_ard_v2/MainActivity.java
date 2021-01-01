package com.example.tme_ard_v2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import com.felhr.usbserial.UsbSerialDevice;

import android.widget.Toast;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collection;
import java.util.Map;

import ArduinoUploader.ArduinoSketchUploader;
import ArduinoUploader.ArduinoUploaderException;
import ArduinoUploader.Config.Arduino;
import ArduinoUploader.Config.McuIdentifier;
import ArduinoUploader.Config.Protocol;
import ArduinoUploader.IArduinoUploaderLogger;
import CSharpStyle.IProgress;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.plugins.GeneratedPluginRegistrant;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

import androidx.annotation.NonNull;
import io.flutter.embedding.engine.FlutterEngine;



public class MainActivity extends FlutterActivity {

    public static final String TAG = MainActivity.class.getSimpleName();
    private UsbSerialManager usbSerialManager;



    public enum UsbConnectState {
        DISCONNECTED,
        CONNECT
    }

    private final BroadcastReceiver mUsbNotifyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                //Get intent
                case UsbSerialManager.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    Toast.makeText(context, "USB permission granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbSerialManager.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission denied", Toast.LENGTH_SHORT).show();
                    break;
                case UsbSerialManager.ACTION_NO_USB: // NO USB CONNECTED
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbSerialManager.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    usbConnectChange(UsbConnectState.DISCONNECTED);
                    break;
                case UsbSerialManager.ACTION_USB_CONNECT: // USB DISCONNECTED
                    Toast.makeText(context, "USB connected", Toast.LENGTH_SHORT).show();
                    usbConnectChange(UsbConnectState.CONNECT);
                    break;
                case UsbSerialManager.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
                case UsbSerialManager.ACTION_USB_READY:
                    Toast.makeText(context, "Usb device ready", Toast.LENGTH_SHORT).show();
                    break;
                case UsbSerialManager.ACTION_USB_DEVICE_NOT_WORKING:
                    Toast.makeText(context, "USB device not working", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };
    private final BroadcastReceiver mUsbHardwareReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UsbSerialManager.ACTION_USB_PERMISSION_REQUEST)) {
                boolean granted = intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if (granted) // User accepted our USB connection. Try to open the device as a serial port
                {
                    UsbDevice grantedDevice = intent.getExtras().getParcelable(UsbManager.EXTRA_DEVICE);
                    usbPermissionGranted(grantedDevice.getDeviceName());
                    Intent it = new Intent(UsbSerialManager.ACTION_USB_PERMISSION_GRANTED);
                    context.sendBroadcast(it);

                } else // User not accepted our USB connection. Send an Intent to the Main Activity
                {
                    Intent it = new Intent(UsbSerialManager.ACTION_USB_PERMISSION_NOT_GRANTED);
                    context.sendBroadcast(it);
                }
            } else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                Intent it = new Intent(UsbSerialManager.ACTION_USB_CONNECT);
                context.sendBroadcast(it);

            } else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                Intent it = new Intent(UsbSerialManager.ACTION_USB_DISCONNECTED);
                context.sendBroadcast(it);

            }
        }
    };

    private void setUsbFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbSerialManager.ACTION_USB_PERMISSION_REQUEST);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(mUsbHardwareReceiver, filter);
    }


    private String deviceKeyName;
    private String filename;


    public void usbConnectChange(UsbConnectState state) {
        methodChannel.invokeMethod("callMe", state.toString());
    }


    public void usbPermissionGranted(String usbKey) {
        Toast.makeText(this, "UsbPermissionGranted:" + usbKey, Toast.LENGTH_SHORT).show();
        deviceKeyName = usbKey;
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        methodChannel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        methodChannel.setMethodCallHandler(
                        (call, result) -> {
                            if (call.method.equals("helloFromNativeCode")) {
                                    result.success("Success");
                            } else if (call.method.equals("requestPermission")) {
                                result.success(requestPermission());
                            } else if (call.method.equals("uploadToBoard")) {
                                filename = call.argument("filename");
                                uploadHex();
                                new Thread(new UploadRunnable()).start();
                                result.success(call.argument("filename"));
                            }
                        }
                );
    }

    private static final String  CHANNEL = "flutter.native/helper";
    private static MethodChannel channel;
    MethodChannel methodChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GeneratedPluginRegistrant.registerWith(this.getFlutterEngine());
        usbSerialManager = new UsbSerialManager(this);
        setUsbFilter();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbHardwareReceiver);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbSerialManager.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbSerialManager.ACTION_NO_USB);
        filter.addAction(UsbSerialManager.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbSerialManager.ACTION_USB_CONNECT);
        filter.addAction(UsbSerialManager.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbSerialManager.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbNotifyReceiver, filter);
    }

    public void requestDevicePermission(String key) {
        usbSerialManager.getDevicePermission(key);

    }

    public boolean checkDevicePermission(String key) {
        return usbSerialManager.checkDevicePermission(key);
    }

    public UsbSerialDevice getUsbSerialDevice(String key) {
        return usbSerialManager.tryGetDevice(key);
    }

    @Override
    public void onResume() {
        super.onResume();
        setFilters();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mUsbNotifyReceiver);
    }

    public String requestPermission(){
        Map.Entry<String, UsbDevice> entry = usbSerialManager.getUsbDeviceList().entrySet().iterator().next();
            String keySelect = entry.getKey();
            boolean hasPem = checkDevicePermission(keySelect);
            if (hasPem) {
                deviceKeyName = keySelect;
                return keySelect;
            } else {
                requestDevicePermission(keySelect);
            }
        return "null";
    }

    public void uploadHex() {

        Boards board = Boards.ARDUINO_UNO;

        Arduino arduinoBoard = new Arduino(board.name, board.chipType, board.uploadBaudrate, board.uploadProtocol);

        Protocol protocol = Protocol.valueOf(arduinoBoard.getProtocol().name());
        McuIdentifier mcu = McuIdentifier.valueOf(arduinoBoard.getMcu().name());
        String preOpenRst = arduinoBoard.getPreOpenResetBehavior();
        String preOpenStr = preOpenRst;
        if (preOpenRst == null) preOpenStr = "";
        else if (preOpenStr.equalsIgnoreCase("none")) preOpenStr = "";

        String postOpenRst = arduinoBoard.getPostOpenResetBehavior();
        String postOpenStr = postOpenRst;
        if (postOpenRst == null) postOpenStr = "";
        else if (postOpenStr.equalsIgnoreCase("none")) postOpenStr = "";

        String closeRst = arduinoBoard.getCloseResetBehavior();
        String closeStr = closeRst;
        if (closeRst == null) closeStr = "";
        else if (closeStr.equalsIgnoreCase("none")) closeStr = "";

        Arduino customArduino = new Arduino("Custom", mcu, arduinoBoard.getBaudRate(), protocol);
        if (!TextUtils.isEmpty(preOpenStr))
            customArduino.setPreOpenResetBehavior(preOpenStr);
        if (!TextUtils.isEmpty(postOpenStr))
            customArduino.setPostOpenResetBehavior(postOpenStr);
        if (!TextUtils.isEmpty(closeStr))
            customArduino.setCloseResetBehavior(closeStr);
        if (protocol == Protocol.Avr109) customArduino.setSleepAfterOpen(0);
        else customArduino.setSleepAfterOpen(250);

        IArduinoUploaderLogger logger = new IArduinoUploaderLogger() {
            @Override
            public void Error(String message, Exception exception) {
                Log.e(TAG, "Error:" + message);
            }

            @Override
            public void Warn(String message) {
                Log.w(TAG, "Warn:" + message);
            }

            @Override
            public void Info(String message) {
                Log.i(TAG, "Info:" + message);
            }

            @Override
            public void Debug(String message) {
                Log.d(TAG, "Debug:" + message);
            }

            @Override
            public void Trace(String message) {
                Log.d(TAG, "Trace:" + message);
            }
        };

        IProgress progress = new IProgress<Double>() {
            @Override
            public void Report(Double value) {
                String result = String.format("Upload progress: %1$,3.2f%%", value * 100);
                Log.d(TAG, result);
                methodChannel.invokeMethod("callMe", result);

            }
        };

        try {
            final InputStream file = getAssets().open(filename);
            Reader reader = new InputStreamReader(file);
            Collection<String> hexFileContents = new LineReader(reader).readLines();
            ArduinoSketchUploader<SerialPortStreamImpl> uploader = new ArduinoSketchUploader<SerialPortStreamImpl>(this, SerialPortStreamImpl.class, null, logger, progress);

            uploader.UploadSketch(hexFileContents, customArduino, deviceKeyName);
        } catch (ArduinoUploaderException ex) {
            ex.printStackTrace();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    private class UploadRunnable implements Runnable {
        @Override
        public void run() {
            uploadHex();
        }
    }

}
