package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.Serializable;
import java.util.HashMap;

/**
 * Activity to manually update the document consecutive number.
 * Updates both the in-memory userData and the persistent Técnico.xlsx file.
 */
public class CambiarConsecutivoActivity extends AppCompatActivity {

    private static final String TAG = "CambiarConsecutivo";
    private HashMap<String, String> userData;
    private TextView tvwDisplayActual;
    private EditText edtNuevo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_cambiar_consecutivo);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);

        // Retrieve User Data from Intent
        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (!(serializable instanceof HashMap)) {
            serializable = getIntent().getSerializableExtra("hashMapKey");
        }

        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        if (userData == null) {
            AlertDialog ad = Methods.showAlert(this, "Error de sesión. Contacte a un administrador.");
            Methods.setCloseAppOnDismiss(ad);
            ad.show();
            return;
        }

        setupUI();
    }

    private void setupUI() {
        tvwDisplayActual = findViewById(R.id.tvw_ConsecutivoActualDisplay);
        edtNuevo = findViewById(R.id.edt_IngresarConsecutivoNuevo);
        Button btnCambiar = findViewById(R.id.btn_Cambiar);
        Button btnSalir = findViewById(R.id.btn_salirCambiarConsecutivo);

        // Display current consecutive
        String actual = userData.getOrDefault("consecutivo", "000000");
        tvwDisplayActual.setText(actual);

        btnCambiar.setOnClickListener(v -> procesarCambio());
        btnSalir.setOnClickListener(v -> finish());
    }

    /**
     * Updates the consecutive number in the local session and persists it to Tecnico.xlsx.
     */
    private void procesarCambio() {
        String nuevoVal = edtNuevo.getText().toString().trim();
        
        // Validation: Ensure it's not empty
        if (nuevoVal.isEmpty()) {
            Methods.showAlert(this, "Ingrese un valor válido").show();
            return;
        }

        // Standardize to 6 digits if needed (optional based on user's prompt "six digit number")
        // Keeping it simple as per prompt: just save what the user enters.
        
        String username = userData.get("Usuario"); // Need the username key to find the right row
        // If "Usuario" isn't in the map, we might need another way to identify the technician.
        // Assuming "Usuario" was stored during login. Let's verify login logic.

        boolean success = Methods.updateConsecutivo(this, userData.get("nombre"), nuevoVal);
        
        if (success) {
            // Update local map for current session
            userData.put("consecutivo", nuevoVal);
            tvwDisplayActual.setText(nuevoVal);
            Methods.showAlert(this, "Consecutivo actualizado correctamente.").show();
        } else {
            Methods.showAlert(this, "Error al actualizar el archivo. Contacte a un administrador.").show();
        }
    }
}
