package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.HashMap;

/**
 * Entry point of the application. Handles login and initial data loading/updates.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private AlertDialog alertDialog = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Force Light Mode as early as possible
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // Required System properties for Apache POI to work correctly on Android
        System.setProperty("org.apache.poi.javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl");
        System.setProperty("org.apache.poi.ss.ignoreMissingFontSystem", "true");

        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);

        // Handle intent for opening Excel files via the app
        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (uri != null && !uri.equals(Uri.EMPTY)) {
            try {
                String name = ActualizarDatos.setFileInputStream(this, uri);
                alertDialog = Methods.showAlert(this, "Excel de " + name + " actualizado con éxito");
                alertDialog.setOnDismissListener(dialogInterface -> Methods.initializeData(this));
                alertDialog.show();
            } catch (Exception e) {
                Log.e(TAG, "Error updating data from URI", e);
                Methods.initializeData(this);
            }
        } else {
            Methods.initializeData(this);
        }

        setupUI();
    }

    /**
     * Links UI components and sets up listeners.
     */
    private void setupUI() {
        Button btnLogin = findViewById(R.id.btn_login);
        Button btnSalir = findViewById(R.id.btn_salir);
        EditText edtUsername = findViewById(R.id.edt_username);
        EditText edtPassword = findViewById(R.id.edp_password);

        btnLogin.setOnClickListener(v -> {
            String username = edtUsername.getText().toString().trim();
            String password = edtPassword.getText().toString().trim();
            
            HashMap<String, String> userData = Methods.validateCredentials(username, password);
            String code = userData.get("code");
            String message = userData.get("message");

            if ("1".equals(code)) {
                Intent myIntent = new Intent(this, MenuActivity.class);
                myIntent.putExtra("userData", userData);
                startActivity(myIntent);
                finishAffinity();
            } else if ("0".equals(code)) {
                // Code "0" indicates a fatal formatting error requiring app closure
                Methods.showFatalError(this, message);
            } else {
                Methods.showAlert(this, message).show();
            }
        });

        btnSalir.setOnClickListener(v -> finishAndRemoveTask());
    }
}
