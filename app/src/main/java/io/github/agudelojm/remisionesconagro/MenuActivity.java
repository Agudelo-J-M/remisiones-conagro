package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
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
    private TextView tvwConsecutivo;

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
        Intent intent = getIntent();
        Serializable serializable = intent.getSerializableExtra("userData");
        if (!(serializable instanceof HashMap)) {
            serializable = intent.getSerializableExtra("hashMapKey");
        }

        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        if (userData == null) {
            AlertDialog alertDialog = Methods.showAlert(this, "Error recopilando datos del usuario. Contacte a un administrador.");
            Methods.setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }

        // Set Welcome Message
        TextView tvwBienvenida = findViewById(R.id.tvw_bienvenida);
        String nombre = (userData != null && userData.containsKey("nombre")) ? userData.get("nombre") : "";
        tvwBienvenida.setText("BIENVENIDO " + nombre.toUpperCase());

        // Set Consecutive Display
        tvwConsecutivo = findViewById(R.id.tvw_consecutivo_menu);
        updateConsecutivoDisplay();

        setupButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateConsecutivoDisplay();
    }

    private void updateConsecutivoDisplay() {
        if (userData != null && tvwConsecutivo != null) {
            String consec = userData.getOrDefault("consecutivo", "000000");
            tvwConsecutivo.setText("Consecutivo: " + consec);
        }
    }

    /**
     * Links UI buttons and sets up their click listeners.
     */
    private void setupButtons() {
        findViewById(R.id.btn_IngresarRemision).setOnClickListener(v -> showNotImplemented());
        findViewById(R.id.btn_ModificarRemision).setOnClickListener(v -> showNotImplemented());
        findViewById(R.id.btn_Reporte).setOnClickListener(v -> showNotImplemented());

        findViewById(R.id.btn_InventarioCarro).setOnClickListener(v -> {
            Intent intent = new Intent(this, InventarioCarroActivity.class);
            intent.putExtra("userData", userData);
            startActivity(intent);
        });

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
