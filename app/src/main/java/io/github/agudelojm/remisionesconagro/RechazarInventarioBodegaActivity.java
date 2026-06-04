package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Activity to handle the rejection of Warehouse Inventory with a required reason.
 */
public class RechazarInventarioBodegaActivity extends AppCompatActivity {

    private EditText edtMotivo;
    private HashMap<String, String> userData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rechazar_inventario_bodega);

        Methods.updateActivity(this);
        
        // Retrieve userData
        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        edtMotivo = findViewById(R.id.edt_MotivoRechazo);
        Button btnEnviar = findViewById(R.id.btn_Enviar_Rechazo);

        btnEnviar.setOnClickListener(v -> enviarRechazo());
    }

    private void enviarRechazo() {
        String motivo = edtMotivo.getText().toString().trim();

        if (motivo.isEmpty()) {
            Methods.showAlert(this, "Por favor, indique el motivo del rechazo antes de continuar").show();
            return;
        }

        // 1. Prepare Email
        Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        emailIntent.setType("message/rfc822");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{""}); // Empty recipient as requested
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Rechazo de Inventario - " + 
                (userData != null ? userData.getOrDefault("nombre", "") : ""));
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Motivo de rechazo: " + motivo);

        ArrayList<Uri> uris = new ArrayList<>();
        File file = new File(getFilesDir(), "Inventario_Temp.xlsx");
        if (!file.exists()) file = new File(getFilesDir(), "Inventario.xlsx");
        
        if (file.exists()) {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            uris.add(uri);
        }
        
        emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Cleanup temporary inventory on rejection
        File tempFile = new File(getFilesDir(), "Inventario_Temp.xlsx");
        if (tempFile.exists()) tempFile.delete();

        startActivity(Intent.createChooser(emailIntent, "Enviar Motivo de Rechazo..."));
        finish();
    }
}
