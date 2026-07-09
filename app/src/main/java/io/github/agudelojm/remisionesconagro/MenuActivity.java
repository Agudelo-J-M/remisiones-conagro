package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.Serializable;
import java.util.HashMap;

/**
 * Main menu of the application, providing access to various functionalities.
 */
public class MenuActivity extends AppCompatActivity {

    private HashMap<String, String> userData;

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

        // Retrieve userData from Intent
        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (!(serializable instanceof HashMap)) {
            serializable = getIntent().getSerializableExtra("hashMapKey");
        }

        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        if (userData == null) {
            Methods.showFatalError(this, "Error recopilando datos del usuario. Contacte a un administrador.");
            return;
        }

        // Set Welcome Message
        TextView tvwBienvenida = findViewById(R.id.tvw_bienvenida);
        String nombre = userData.getOrDefault("nombre", "");
        tvwBienvenida.setText("BIENVENIDO " + nombre.toUpperCase());

        setupButtons();
    }

    /**
     * Links UI buttons and sets up their click listeners.
     */
    private void setupButtons() {
        findViewById(R.id.btn_IngresarRemision).setOnClickListener(v -> {
            Intent intent = new Intent(this, IngresarRemisionActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });

        findViewById(R.id.btn_ModificarRemision).setOnClickListener(v -> {
            Intent intent = new Intent(this, ModificarRemisionActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });
        findViewById(R.id.btn_Reporte).setOnClickListener(v -> {
            Intent intent = new Intent(this, ReportesActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });

        /* Reenable later if needed
        findViewById(R.id.btn_InventarioCarro).setOnClickListener(v -> {
            Intent intent = new Intent(this, InventarioCarroActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });
        */

        findViewById(R.id.btn_InventarioBodega).setOnClickListener(v -> {
            Intent intent = new Intent(this, InventarioBodegaActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });

        findViewById(R.id.btn_CambiarConsecutivo).setOnClickListener(v -> {
            Intent intent = new Intent(this, CambiarConsecutivoActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });

        findViewById(R.id.btn_cerrarSesion).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finishAffinity();
        });
    }

    private void showNotImplemented() {
        Methods.showAlert(this, "Aún no implementada").show();
    }
}
