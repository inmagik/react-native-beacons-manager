package com.mackentoch.beaconsandroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.BeaconTransmitter;
import org.altbeacon.beacon.Region;

import java.util.Collection;

public class BeaconsAndroidModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;
    private final BeaconManager beaconManager;
    private BeaconTransmitter beaconTransmitter;
    private final SensorManager sensorManager;
    private boolean backgroundModeConfigured = false;
    private float[] lastAccelerometerData = new float[]{0, 0, 0};

    public BeaconsAndroidModule(ReactApplicationContext reactContext) {
        this.reactContext = reactContext;
        Context appContext = reactContext.getApplicationContext();
        this.beaconManager = BeaconManager.getInstanceForApplication(appContext);
        this.sensorManager = (SensorManager) this.reactContext.getApplicationContext().getSystemService(Activity.SENSOR_SERVICE);
    }

    @NonNull
    @Override
    public String getName() {
        return "BeaconsAndroidModule";
    }


    /* ------------------ BEACON RANGING -------------------- */

    @SuppressLint("UnspecifiedImmutableFlag")
    private void configureBackgroundMode() {
        Log.d("BeaconsAndroidModule", "Configure background mode");
        if (this.backgroundModeConfigured) {
            return;
        }
        Log.d("BeaconsAndroidModule", "Configure background mode started");
        Context appContext = this.reactContext.getApplicationContext();
        Notification.Builder builder = new Notification.Builder(appContext);
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle("Scanning for Beacons");
        Intent intent = new Intent(appContext, MainActivity.class);
        PendingIntent pendingIntent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        } else {
            pendingIntent = PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
            );
        }
        builder.setContentIntent(pendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("Servizi museali",
                    "Audioguida", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifiche riguardanti i servizi digitali attivi presso il museo");
            NotificationManager notificationManager = (NotificationManager) appContext.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder.setChannelId(channel.getId());
        }
        beaconManager.enableForegroundServiceScanning(builder.build(), 456);
        beaconManager.setEnableScheduledScanJobs(false);
        beaconManager.setBackgroundBetweenScanPeriod(0);
        beaconManager.setBackgroundScanPeriod(1100);
        this.backgroundModeConfigured = true;
        Log.d("BeaconsAndroidModule", "Configure background mode completed");
    }

    @ReactMethod
    public void addBeaconLayout(String layout) {
        Log.d("BeaconsAndroidModule", "Add layout " + layout);
        this.beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout(layout));
    }

    @ReactMethod
    public void startRanging(String regionId, String uuid) {
        Log.d("BeaconsAndroidModule", "Request ranging on UUID " + uuid);
        this.configureBackgroundMode();
        beaconManager.startRangingBeacons(new Region(regionId, Identifier.parse(uuid), null, null));
    }

    @ReactMethod
    public void stopRanging(String regionId, String uuid, Promise promise) {
        Log.d("BeaconsAndroidModule", "Stopping ranging on UUID " + uuid);
        try {
            this.beaconManager.stopRangingBeacons(new Region(regionId, Identifier.parse(uuid), null, null));
            promise.resolve("Ok");
        } catch (Exception e) {
            Log.e("BeaconsAndroidModule", "stopRanging, error: ", e);
            promise.reject(e);
        }
    }

    private final RangeNotifier rangeNotifier = (beacons, region) -> {
        Log.d("BeaconsAndroidModule", "Beacons ranged");
        sendEvent("beaconsDidRange", createRangingResponse(beacons, region));
    };

    private final SensorEventListener accelerometerListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            Log.d("BeaconsAndroidModule", "Received accelerometer data");
            lastAccelerometerData = sensorEvent.values;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        Log.d("BeaconsAndroidModule", "Sending event");
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    private WritableMap createRangingResponse(Collection<Beacon> beacons, Region region) {
        WritableMap map = new WritableNativeMap();
        map.putString("identifier", region.getUniqueId());
        map.putString("uuid", region.getId1() != null ? region.getId1().toString() : "");
        WritableArray a = new WritableNativeArray();
        for (Beacon beacon : beacons) {
            WritableMap b = new WritableNativeMap();
            b.putString("uuid", beacon.getId1().toString());
            if (beacon.getIdentifiers().size() > 2) {
                b.putInt("major", beacon.getId2().toInt());
                b.putInt("minor", beacon.getId3().toInt());
            }
            b.putInt("rssi", beacon.getRssi());
            if (beacon.getDistance() == Double.POSITIVE_INFINITY
                    || Double.isNaN(beacon.getDistance())
                    || beacon.getDistance() == Double.NEGATIVE_INFINITY) {
                b.putDouble("distance", 999.0);
                b.putString("proximity", "far");
            } else {
                b.putDouble("distance", beacon.getDistance());
                b.putString("proximity", getProximity(beacon.getDistance()));
            }
            a.pushMap(b);
        }
        map.putArray("beacons", a);
        WritableArray accel = new WritableNativeArray();
        for (float c: lastAccelerometerData) {
            accel.pushDouble(c);
        }
        map.putArray("accelerometer", accel);
        return map;
    }

    private String getProximity(double distance) {
        if (distance == -1.0) {
            return "unknown";
        } else if (distance < 1) {
            return "immediate";
        } else if (distance < 3) {
            return "near";
        } else {
            return "far";
        }
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Set up any upstream listeners or background tasks as necessary
        Log.d("BeaconsAndroidModule", "Add listener for " + eventName);
        beaconManager.addRangeNotifier(this.rangeNotifier);
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(accelerometerListener, sensor, 1 * 1000 * 1000);
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Remove upstream listeners, stop unnecessary background tasks
        Log.d("BeaconsAndroidModule", "Removed " + count.toString() + " listeners");
        beaconManager.removeRangeNotifier(this.rangeNotifier);
        sensorManager.unregisterListener(accelerometerListener);
    }

    /* -------------------- END BEACON RANGING --------------------------- */

    /* -------------------- BEACON TRANSMISSION --------------------------- */
    @ReactMethod
    public void checkTransmissionSupported(Promise promise) {
        int result = BeaconTransmitter.checkTransmissionSupported(reactContext);
        promise.resolve(result);
    }

    @ReactMethod
    public void startSharedAdvertisingBeaconWithString(String uuid, int major, int minor, String identifier, Promise promise) {
        int manufacturer = 0x4C;
        try {
            Beacon beacon = new Beacon.Builder()
                    .setId1(uuid)
                    .setId2(String.valueOf(major))
                    .setId3(String.valueOf(minor))
                    .setManufacturer(manufacturer)
                    .setBluetoothName(identifier)
                    .setTxPower(-56)
                    .build();
            BeaconParser beaconParser = new BeaconParser()
                    .setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24");

            this.beaconTransmitter = new BeaconTransmitter(reactContext, beaconParser);
            this.beaconTransmitter.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY);
            this.beaconTransmitter.setAdvertiseTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH);
            this.beaconTransmitter.startAdvertising(beacon, new AdvertiseCallback() {

                @Override
                public void onStartFailure(int errorCode) {
                    promise.reject("Error activating beacon", String.format("Error %d", errorCode));
                    Log.d("ReactNative", "Error from start advertising " + errorCode);
                }

                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    promise.resolve("started");
                    Log.d("ReactNative", "Success start advertising");
                }
            });
        } catch (Exception ex) {
            promise.reject(ex);
        }
    }

    @ReactMethod
    public void stopSharedAdvertisingBeacon() {
        if (this.beaconTransmitter != null) {
            try {
                this.beaconTransmitter.stopAdvertising();
            } catch (Exception ex) {
            }
        }
    }
    /* -------------------- END BEACON TRANSMISSION --------------------------- */
}
