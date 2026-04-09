package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

public class MenuActivity extends AppCompatActivity {

    HashMap<String, String> userData;
    AlertDialog alertDialog;


    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_menu);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);


        Intent intent = getIntent();
        Serializable serializable =  intent.getSerializableExtra("hashMapKey");
        if (serializable instanceof java.util.HashMap<?, ?>) {
            // Safe to cast after instanceof check
            userData =  (HashMap<String, String>) serializable;
        } else {
           alertDialog = Methods.showAlert(this, "Error recopilando datos del usuario, pongase en contacto con un administrador");
           Methods.setCloseAppOnDismiss(alertDialog);
           alertDialog.show();
        }
        String bienvenida = "BIENVENIDO";

        try {
            {
                assert userData != null : "Error, hubo un problema recibiendo los datos del usuario, reinicie la app o pongase en contacto con un administrador";
                Log.w("dataMenu", Arrays.toString(userData));
                assert !userData[0].isEmpty() : "Error, el nombre del conductor registrado está vacío";
                bienvenida += " " + userData[0].toUpperCase();
                Log.w("userData", userData[0]);
                Log.w("userData", userData[1]);
                Log.w("userData", userData [2]);

            }
        } catch (Exception e) {
            Log.w("Menu Exception", e.toString());
            alertDialog = Methods.showAlert(this, "Error inesperado, reinicie la app o pongase en contacto con un administrador");
            alertDialog.show();

        }catch (Error e){

            Log.w("Menu assertError", e.toString());
            alertDialog = Methods.showAlert(this, e.getMessage());
            alertDialog.show();
        }

        TextView tvwBienvenida = findViewById(R.id.tvw_bienvenida);
        tvwBienvenida.setText(bienvenida);


        Button btnAutomatico = this.findViewById(R.id.btn_automatico);
        Button btnManual = this.findViewById(R.id.btn_manual);
        Button btnEliminar = this.findViewById(R.id.btn_eliminar);
        Button btnReporte = this.findViewById(R.id.btn_reporte);
        Button btnReportePase = this.findViewById(R.id.btn_reporte_pase);
        Button btnEnviar = this.findViewById(R.id.btn_enviar);
        Button btnCerrarSesion = this.findViewById(R.id.btn_cerrarSesion);

        btnAutomatico.setOnClickListener((View v) ->
                {
                    Log.w("Custom", "cargando automatico");
                    Intent myIntent = new Intent(this, AutomaticoActivity.class);
                    myIntent.putExtra("userData", userData); //Optional parameters
                    Log.w("menuData", Arrays.toString(userData));
                    this.startActivity(myIntent);
                }
        );

        btnManual.setOnClickListener((View v) ->
                {
                    Log.w("Custom", "cargando manual");
                    Intent myIntent = new Intent(this, ManualActivity.class);
                    myIntent.putExtra("userData", userData); //Optional parameters
                    this.startActivity(myIntent);
                }
        );

        btnEliminar.setOnClickListener((View v) ->
                {
                    Log.w("Custom", "cargando eliminar");
                    Intent myIntent = new Intent(this, EliminarActivity.class);
                    myIntent.putExtra("userData", userData); //Optional parameters
                    this.startActivity(myIntent);
                }
        );

        btnReporte.setOnClickListener((View v) ->
                {
                    Log.w("Custom", "cargando reportes");
                    Intent myIntent = new Intent(this, ReportesActivity.class);
                    myIntent.putExtra("userData", userData); //Optional parameters
                    this.startActivity(myIntent);
                }
        );

        btnReportePase.setOnClickListener((View v) ->
                {
                    Log.w("Custom", "cargando reportes pase");
                    Intent myIntent = new Intent(this, ReportePaseActivity.class);
                    myIntent.putExtra("userData", userData); //Optional parameters
                    this.startActivity(myIntent);
                }
        );

        btnEnviar.setOnClickListener((View v) ->
                {
                    Log.w("Custom", "cargando enviar");
                    sendMail();
                    //open gmail and attach new excel
                }
        );



        btnCerrarSesion.setOnClickListener((View v) ->
                {
                    // restart app or close all activities before launching main again.
                    Log.w("Custom", "cerrando sesion");
                    Intent myIntent = new Intent(this, MainActivity.class);
                    this.startActivity(myIntent);
                    finishAffinity();
                }
        );


    }

    private void sendMail(){
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("text/plain");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[] {"embarcaderos@uniban.com.co"});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Envio de Cajas Semana "+ getWeekYear() +" Conductor " + userData[0]);
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Se adjunta Excel");
        File file = Methods.getFile(this);
        if(file == null){
            Log.w("error enviando", "el archivo es null");
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, "io.github.agudelojm.acopiodefruta.fileprovider" ,file);
        emailIntent.putExtra(Intent.EXTRA_STREAM, uri);
        startActivity(Intent.createChooser(emailIntent, "Escoja su aplicación de Email"));
    }

    private String getWeekYear(){
        LocalDate date = LocalDate.now();
        // Define a locale (e.g., US locale)
        Locale locale = Locale.US;
        // Use the defined locale with WeekFields
        return String.valueOf(date.get(WeekFields.of(locale).weekOfYear()));
    }
}