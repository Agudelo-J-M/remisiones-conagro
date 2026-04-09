package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;

public class RechazarInventarioBodegaActivity extends AppCompatActivity {

    private EditText edtMotivo;
    private Button btnEnviar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rechazar_inventario_bodega);

        edtMotivo = findViewById(R.id.edt_MotivoRechazo);
        btnEnviar = findViewById(R.id.btn_Enviar_Rechazo);

        btnEnviar.setOnClickListener(v -> enviarRechazo());
    }

    private void enviarRechazo() {
        String motivo = edtMotivo.getText().toString().trim();

        // 1. Send Email
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{""});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "");
        emailIntent.putExtra(Intent.EXTRA_TEXT, motivo);

        File file = new File(getFilesDir(), "Inventario.xlsx");
        if (file.exists()) {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            emailIntent.putExtra(Intent.EXTRA_STREAM, uri);
            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        // 2. Delete Inventario.xlsx and clear InventarioWb
        if (file.exists()) {
            file.delete();
        }
        Methods.setInventarioWb(null);

        startActivity(Intent.createChooser(emailIntent, "Enviar Motivo de Rechazo..."));
        finish();
    }
}
