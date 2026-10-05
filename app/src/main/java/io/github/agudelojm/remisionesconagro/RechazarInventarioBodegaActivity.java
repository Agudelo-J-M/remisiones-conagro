package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
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
        emailIntent.setType("*/*");
        // Office address from the Tecnicos sheet ("CORREO OFICINA"); left empty for the técnico
        // to type when the server has no value for them.
        emailIntent.putExtra(Intent.EXTRA_EMAIL,
                Methods.buildEmailRecipients(Methods.getCorreoOficina(userData)));
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Rechazo de Inventario - " +
                (userData != null ? userData.getOrDefault("nombre", "") : ""));
        
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Motivo de rechazo: " + motivo);

        File tempFile = new File(getFilesDir(), "Inventario_Temp.xlsx");
        File definitiveFile = new File(getFilesDir(), "Inventario.xlsx");
        File fileToShare = tempFile.exists() ? tempFile : definitiveFile;

        ArrayList<Uri> uris = new ArrayList<>();
        if (fileToShare.exists()) {
            String originalName = Methods.getLastInventoryFileName(this);
            if (originalName == null || originalName.isEmpty()) originalName = "Inventario_Rechazado.xlsx";
            if (!originalName.toLowerCase().endsWith(".xlsx")) originalName += ".xlsx";
            
            File renamedFile = new File(getCacheDir(), originalName);
            try {
                if (renamedFile.getParentFile() != null && !renamedFile.getParentFile().exists()) renamedFile.getParentFile().mkdirs();
                java.nio.file.Files.copy(fileToShare.toPath(), renamedFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", renamedFile));
            } catch (Exception e) {
                Log.e("Rechazo", "CRITICAL: Error copying to cache", e);
            }
        }

        if (!uris.isEmpty()) {
            emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        startActivity(Intent.createChooser(emailIntent, "Enviar Motivo de Rechazo..."));
        
        // Show confirmation dialog after starting intent
        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmación")
                .setMessage("¿Se envió el correo correctamente? Si confirma, el archivo temporal será eliminado.")
                .setPositiveButton("Sí, enviado", (dialog, which) -> {
                    if (tempFile.exists()) tempFile.delete();
                    Methods.setInventarioWbToNull();
                    finish();
                })
                .setNegativeButton("No", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }
}
