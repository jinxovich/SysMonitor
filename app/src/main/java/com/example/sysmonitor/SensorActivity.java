package com.example.sysmonitor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;

public class SensorActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer, lightSensor;
    private TextView valX, valY, valZ, valLight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensors);

        valX = findViewById(R.id.valX);
        valY = findViewById(R.id.valY);
        valZ = findViewById(R.id.valZ);
        valLight = findViewById(R.id.valLight);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null) {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            if (lightSensor != null) sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            valX.setText(String.format(Locale.US, "%.1f", event.values[0]));
            valY.setText(String.format(Locale.US, "%.1f", event.values[1]));
            valZ.setText(String.format(Locale.US, "%.1f", event.values[2]));
        } else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            valLight.setText((int)event.values[0] + " lx");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}