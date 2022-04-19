package com.dji.mediaManagerDemo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.EditText;

public class popup_Settings extends Activity{
    EditText editTextIp;
    EditText editTextPort;
    EditText editTextUser;
    EditText editTextPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.setting_connection);
        editTextIp = (EditText) findViewById(R.id.input_ip);
        editTextPort = (EditText) findViewById(R.id.input_port);
        editTextUser = (EditText) findViewById(R.id.input_user);
        editTextPass = (EditText) findViewById(R.id.input_pass);

        // FTP Env
        SharedPreferences ftpEnv = getSharedPreferences("FTP_ENV", MODE_PRIVATE);

        editTextIp.setText(ftpEnv.getString("ftpHost", ""));
        editTextPort.setText(ftpEnv.getString("ftpPort", ""));
        editTextUser.setText(ftpEnv.getString("ftpUser", ""));
        editTextPass.setText(ftpEnv.getString("ftpPass", ""));
    }

    public void setting_btn_Clicked(View v) {
        // FTP Env
        SharedPreferences ftpEnv = getSharedPreferences("FTP_ENV", MODE_PRIVATE);

        // Env Editor
        SharedPreferences.Editor ftpEnv_Editor = ftpEnv.edit();

        // Set Env
        ftpEnv_Editor.putString("ftpHost", editTextIp.getText().toString());
        ftpEnv_Editor.putString("ftpPort", editTextPort.getText().toString());
        ftpEnv_Editor.putString("ftpUser", editTextUser.getText().toString());
        ftpEnv_Editor.putString("ftpPass", editTextPass.getText().toString());

        // Env commit
        ftpEnv_Editor.commit();

        finish();
    }

    public void cancel_btn_Clicked(View v) {
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
