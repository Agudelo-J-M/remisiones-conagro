package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.github.gcacace.signaturepad.views.SignaturePad;

import java.io.File;

public class PedirFirmaActivity extends AppCompatActivity {

    private SignaturePad mSignaturePad;
    private Button mClearButton;
    private Button mSaveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pedir_firma);

        mSignaturePad = findViewById(R.id.signature_pad);
        mClearButton = findViewById(R.id.btn_Borrar_Firma);
        mSaveButton = findViewById(R.id.btn_Aceptar_Firma);

        mClearButton.setOnClickListener(view -> mSignaturePad.clear());

        mSaveButton.setOnClickListener(view -> {
            // Logic to handle signature if needed (saving as bitmap etc)
            // For now, as per request, we proceed to send email and clean up
            enviarEmailYFinalizar();
        });
    }

    private void enviarEmailYFinalizar() {
        // 1. Save InventarioWb as InventarioBodegaActual.xlsx
        Methods.saveWorkbook(this, Methods.getInventarioWb(), "InventarioBodegaActual.xlsx");

        // 2. Send Email
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{""});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "");
        emailIntent.putExtra(Intent.EXTRA_TEXT, "");

        File file = new File(getFilesDir(), "Inventario.xlsx");
        if (file.exists()) {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            emailIntent.putExtra(Intent.EXTRA_STREAM, uri);
            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        // 3. Delete Inventario.xlsx and clear InventarioWb
        if (file.exists()) {
            file.delete();
        }
        Methods.setInventarioWb(null);

        startActivity(Intent.createChooser(emailIntent, "Enviar Inventario Aceptado..."));
        finish();
    }
}
