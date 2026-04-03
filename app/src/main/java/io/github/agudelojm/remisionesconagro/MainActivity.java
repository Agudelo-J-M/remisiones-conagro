package io.github.agudelojm.remisionesconagro;

import android.os.Bundle;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.apache.poi.ss.usermodel.Workbook;

import java.io.FileInputStream;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private AlertDialog alertDialog = null;

    private Uri uri;

    private FileInputStream fileInputStream;
    Workbook wb;


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


        Intent intent = getIntent();
        uri = intent.getData();
        if (uri != null && !uri.equals(Uri.EMPTY)) {
            String name = ActualizarDatos.setFileInputStream(this, uri);
            alertDialog = Methods.showAlert(this, "Excel de "+name+" Actualizado con éxito");
            alertDialog.setOnDismissListener(dialogInterface -> {
                Methods.initializeData(this);
            });
            alertDialog.show();
        } else{
            Methods.initializeData(this);
        }
        Log.w("inicializa exceles", "");


        Button btnLogin = this.findViewById(R.id.btn_login);
        Button btnSalir = this.findViewById(R.id.btn_salir);
        EditText edtUsername = this.findViewById(R.id.edt_username);
        EditText edtPassword = this.findViewById(R.id.edp_password);
        Log.w("if de credenciales", "inicializados btn y edt");
        Log.w("tag", btnLogin.toString());

        btnLogin.setOnClickListener((View v) ->
        {
            Log.w("tag", "inicia el onclicklistener");
            String username = edtUsername.getText().toString();
            String password = edtPassword.getText().toString();
            var userData = Methods.validateCredentials(username, password);
            if (userData.get("code").equals("1")) {
                Log.w("login", "test exitoso");
                Intent myIntent = new Intent(this, MenuActivity.class);
                myIntent.putExtra("userData", userData);
                this.startActivity(myIntent);
                finishAffinity();
            } else {
                alertDialog = Methods.showAlert(this, userData.get("message"));
                if (Objects.equals(userData.get("code"), "0")){
                    Methods.setCloseAppOnDismiss(alertDialog);
                }
                alertDialog.show();
            }
        });

        btnSalir.setOnClickListener((View v) -> finishAndRemoveTask());


    }
}