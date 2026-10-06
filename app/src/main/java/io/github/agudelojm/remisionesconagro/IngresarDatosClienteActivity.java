package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.Map;

/**
 * Activity to capture data for a new/unregistered client.
 * This data is formatted into the "Observaciones" field of the PDF.
 */
public class IngresarDatosClienteActivity extends AppCompatActivity {

    private EditText edtFinca, edtDir, edtCiu, edtNom, edtTel, edtEmail;
    private String tecnicoNumero = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ingresar_datos_cliente);

        Methods.updateActivity(this);

        String numero = getIntent().getStringExtra("tecnicoNumero");
        if (numero != null) tecnicoNumero = numero;

        edtFinca = findViewById(R.id.edt_Nuevo_NombreFinca);
        edtDir = findViewById(R.id.edt_Nuevo_Direccion);
        edtCiu = findViewById(R.id.edt_Nuevo_Ciudad);
        edtNom = findViewById(R.id.edt_Nuevo_NombrePersona);
        edtTel = findViewById(R.id.edt_Nuevo_Telefono);
        edtEmail = findViewById(R.id.edt_Nuevo_Email);
        Button btnNext = findViewById(R.id.btn_Continuar_Datos_Cliente);

        // Load existing draft if any
        loadClientDraft();

        // Real-time draft saving
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                saveClientDraft();
            }
        };
        edtFinca.addTextChangedListener(watcher);
        edtDir.addTextChangedListener(watcher);
        edtCiu.addTextChangedListener(watcher);
        edtNom.addTextChangedListener(watcher);
        edtTel.addTextChangedListener(watcher);
        edtEmail.addTextChangedListener(watcher);

        btnNext.setOnClickListener(v -> {
            String emailText = edtEmail.getText().toString().trim();
            if (!emailText.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
                Methods.showAlert(this, "Por favor ingrese un email válido").show();
                return;
            }

            String finca = edtFinca.getText().toString().trim();
            String dir = edtDir.getText().toString().trim();
            String ciu = edtCiu.getText().toString().trim();
            String nom = edtNom.getText().toString().trim();
            String tel = edtTel.getText().toString().trim();
            String email = emailText;

            if (finca.isEmpty()) finca = "POR DEFINIR";
            if (dir.isEmpty()) dir = "N/A";
            if (ciu.isEmpty()) ciu = "N/A";
            if (nom.isEmpty()) nom = "POR DEFINIR";
            if (tel.isEmpty()) tel = "N/A";
            if (email.isEmpty()) email = "N/A";

            String formattedObs = "NUEVO CLIENTE, AÑADIR A CLIENTES: \n" +
                    "Finca: " + finca + " | Dir: " + dir + " | Ciu: " + ciu + "\n" +
                    "Cliente: " + nom + " | Tel: " + tel + " | Email: " + email;

            Intent result = new Intent();
            result.putExtra(ObservacionesActivity.RESULT_OBSERVACIONES, formattedObs);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void saveClientDraft() {
        Map<String, String> data = new HashMap<>();
        data.put("finca", edtFinca.getText().toString());
        data.put("dir", edtDir.getText().toString());
        data.put("ciu", edtCiu.getText().toString());
        data.put("nom", edtNom.getText().toString());
        data.put("tel", edtTel.getText().toString());
        data.put("email", edtEmail.getText().toString());
        RemisionDraftCache.saveTempClient(tecnicoNumero, data);
    }

    private void loadClientDraft() {
        RemisionDraftCache.Draft draft = RemisionDraftCache.get(tecnicoNumero);
        if (draft != null && draft.tempClientData != null) {
            Map<String, String> data = draft.tempClientData;
            edtFinca.setText(data.getOrDefault("finca", ""));
            edtDir.setText(data.getOrDefault("dir", ""));
            edtCiu.setText(data.getOrDefault("ciu", ""));
            edtNom.setText(data.getOrDefault("nom", ""));
            edtTel.setText(data.getOrDefault("tel", ""));
            edtEmail.setText(data.getOrDefault("email", ""));
        }
    }
}
