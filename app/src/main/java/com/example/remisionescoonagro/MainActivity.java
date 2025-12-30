package com.example.remisionescoonagro;

import android.os.Bundle;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    AlertDialog alertDialog = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        Log.w("INICIA A CREAR LA ACTIVIDAD", "inicia actividad");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl");
        System.setProperty("org.apache.poi.ss.ignoreMissingFontSystem", "true");
        Methods.updateActivity(this);


        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);

            return insets;
        });

        Log.w("WB", "crea actividad");




        if (!isExternalStorageAvailable() || isExternalStorageReadOnly()) {
            Log.w("FileUtils", "Storage not available or read only");
            alertDialog = Methods.showAlert(this, "Error, almacenamiento inaccesible.");
            Methods.setCloseAppOnDismiss(alertDialog);

            alertDialog.show();
        }

        Log.w("WB", "pasa estado almacenamiento");

        if (!Environment.isExternalStorageManager()) {
            Log.w("permission", "no es ExternalStorageManager");
            alertDialog = Methods.showAlert(this, "Error, la aplicación no tiene permiso para acceder al almacenamiento, redireccionando a configuración para que se habilite dicho permiso.");
            alertDialog.setOnDismissListener(dialogInterface -> {
                askPermission();
                Methods.validatePermission(this);
            });
            alertDialog.show();
        } else {
            Methods.inputStream(this);
        }


        Log.w("WB", "pasa permisos");

        Button btnLogin = this.findViewById(R.id.btn_login);
        Button btnSalir = this.findViewById(R.id.btn_salir);
        EditText edtUsername = this.findViewById(R.id.edt_username);
        EditText edtPassword = this.findViewById(R.id.edp_password);
        Log.w("if de credenciales", "inicializados btn y edt");
        Log.w("tag", btnLogin.toString());

        btnLogin.setOnClickListener((View v) ->
        {
            Log.w("tag", "inicia el onclicklistener");
            String username = edtUsername.getText().toString().toUpperCase();
            String password = edtPassword.getText().toString().toUpperCase();
            String[] message = Methods.validateCredentials(username, password);

            if (message[0].equals("1")) {
                final String[] userData = {message[2], message[3], message[4]};
                Log.w("login", "test exitoso");
                Intent myIntent = new Intent(this, MenuActivity.class);
                myIntent.putExtra("userData", userData);
                this.startActivity(myIntent);
                finishAffinity();
            } else {
                alertDialog = Methods.showAlert(this, message[1]);
                if (message[0].equals("0")){
                    Methods.setCloseAppOnDismiss(alertDialog);
                }
                alertDialog.show();
            }
        });

        btnSalir.setOnClickListener((View v) -> finishAndRemoveTask());


    }

    private void askPermission() {
        Intent Environment = new
                Intent().setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:" + getPackageName()));

        if (Environment.resolveActivity(getPackageManager()) != null) {

            startActivity(Environment);


        }
    }



    private static boolean isExternalStorageReadOnly() {
        return Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState());
    }

    private static boolean isExternalStorageAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }


}