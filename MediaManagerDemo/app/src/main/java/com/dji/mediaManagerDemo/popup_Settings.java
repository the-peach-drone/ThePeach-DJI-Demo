package com.dji.mediaManagerDemo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

public class popup_Settings extends Activity{
    String ip;
    String port;

    EditText editTextIp;
    EditText editTextPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.setting_connection);
        editTextIp = (EditText) findViewById(R.id.input_ip);
        editTextPort = (EditText) findViewById(R.id.input_port);
    }

    public void setting_btn_Clicked(View v) {
        ip = editTextIp.getText().toString();
        port = editTextPort.getText().toString();

        Intent intent = new Intent();
        intent.putExtra("ip", ip);
        intent.putExtra("port", port);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            return false;
        }
        else {
            return true;
        }
    }

    @Override
    public void onBackPressed() {
        return;
    }
}
