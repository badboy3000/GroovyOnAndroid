package com.jsdroid.who.groovy;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        String code = "'Hello World!'";
        AndroidGroovyShell compiler = new AndroidGroovyShell(getFilesDir(), getClassLoader());
        try {
            AndroidScript script = compiler.evaluate(code, "Main.groovy");
            Object run = script.run();
            Toast.makeText(this, run.toString(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
        }
    }
}
