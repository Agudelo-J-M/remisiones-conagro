package io.github.agudelojm.remisionesconagro;

import android.os.Bundle;
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
 * This activity is considered obsolete after the header update but remains for consistency.
 */
public class CambiarConsecutivoActivity extends AppCompatActivity {

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
            Methods.showFatalError(this, "Error de sesión. Contacte a un administrador.");
            return;
        }

        setupUI();
    }

    private void setupUI() {
        tvwDisplayActual = findViewById(R.id.tvw_ConsecutivoActualDisplay);
        edtNuevo = findViewById(R.id.edt_IngresarConsecutivoNuevo);
        Button btnCambiar = findViewById(R.id.btn_Cambiar);
        Button btnSalir = findViewById(R.id.btn_salirCambiarConsecutivo);

        // Display current consecutive (if available in map)
        String actual = userData.getOrDefault("consecutivo", "000000");
        tvwDisplayActual.setText(actual);

        btnCambiar.setOnClickListener(v -> Methods.showAlert(this, "Esta función ha sido deshabilitada.").show());
        btnSalir.setOnClickListener(v -> finish());
    }
}
