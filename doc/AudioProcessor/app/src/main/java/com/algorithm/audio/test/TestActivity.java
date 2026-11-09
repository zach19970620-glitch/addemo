package com.algorithm.audio.test;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.algorithm.audio.AudioProcessor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TestActivity extends Activity {
    private static final String TAG = "TestActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private AudioProcessor audioProcessor;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        audioProcessor = AudioProcessor.getInstance();
        checkAndRequestPermission();
    }

    private void checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        } else {
            performFileOperation();
        }
    }

    private void performFileOperation() {
        Log.d(TAG, "performFileOperation...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                audioProcessor.init();
                try {
                    //FileInputStream fisMic = new FileInputStream(new File("/sdcard/mic.pcm"));
                    //FileInputStream fisRef = new FileInputStream(new File("/sdcard/ref.pcm"));
                    InputStream fisMic =  TestActivity.this.getResources().openRawResource(R.raw.mic);
                    InputStream fisRef =  TestActivity.this.getResources().openRawResource(R.raw.ref);
                    FileOutputStream fosOut = new FileOutputStream(new File("/sdcard/aes.pcm"));

                    final int SEG_LEN = 240;
                    byte[] bufMic = new byte[SEG_LEN << 1];
                    byte[] bufRef = new byte[SEG_LEN << 1];
                    byte[] bufOut = new byte[SEG_LEN << 1];

                    int frm_i = 0;
                    int bytes1, bytes2;
                    while (true) {
                        bytes1 = fisMic.read(bufMic);
                        bytes2 = fisRef.read(bufRef);

                        if ((bytes1 != bytes2) || (bytes1 < (SEG_LEN << 1))) {
                            break;
                        }

                        audioProcessor.process(bufMic, bufRef, bufOut);
                        fosOut.write(bufOut);

                        Log.d(TAG, "frm_i:" + frm_i++);
                    }
                } catch (Exception e) {
                    Log.d(TAG, e.toString());
                }
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                performFileOperation();
            } else {
                Toast.makeText(this, "权限被拒绝，将无法使用相关功能", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
