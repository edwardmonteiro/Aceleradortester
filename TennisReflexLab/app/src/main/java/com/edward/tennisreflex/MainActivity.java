package com.edward.tennisreflex;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;

public class MainActivity extends Activity {
    private ReflexLabView labView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(11, 15, 20));
        getWindow().setNavigationBarColor(Color.rgb(11, 15, 20));
        labView = new ReflexLabView(this);
        setContentView(labView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (labView != null) labView.startSensors();
    }

    @Override
    protected void onPause() {
        if (labView != null) labView.stopSensors();
        super.onPause();
    }
}
