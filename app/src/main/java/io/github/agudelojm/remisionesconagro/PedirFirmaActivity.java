package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.github.gcacace.signaturepad.views.SignaturePad;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Activity for capturing a digital signature and processing it based on the flow type.
 */
public class PedirFirmaActivity extends AppCompatActivity {

    private static final String TAG = "PedirFirmaActivity";
    private SignaturePad mSignaturePad;
    private HashMap<String, String> userData;
    private String flowType;
    private String consecutivo = "";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Prevent large bitmap parcel crash by removing ID temporarily
        if (mSignaturePad != null) {
            int originalId = mSignaturePad.getId();
            mSignaturePad.setId(android.view.View.NO_ID);
            super.onSaveInstanceState(outState);
            mSignaturePad.setId(originalId);
        } else {
            super.onSaveInstanceState(outState);
        }
        outState.putSerializable("userData", userData);
        outState.putString("flowType", flowType);
        outState.putString("consecutivo", consecutivo);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pedir_firma);

        Methods.updateActivity(this);

        // Handle Intent Extras or Saved State
        if (savedInstanceState != null) {
            userData = (HashMap<String, String>) savedInstanceState.getSerializable("userData");
            flowType = savedInstanceState.getString("flowType");
            consecutivo = savedInstanceState.getString("consecutivo");
        } else {
            Intent intent = getIntent();
            Serializable serializable = intent.getSerializableExtra("userData");
            if (serializable instanceof HashMap) {
                userData = (HashMap<String, String>) serializable;
            }
            flowType = intent.getStringExtra("flowType");
            consecutivo = intent.getStringExtra("consecutivo");
        }

        if (consecutivo == null) consecutivo = "";

        TextView tvwTitulo = findViewById(R.id.tvw_Titulo_Pedir_Firma);
        if ("inventario".equals(flowType)) {
            tvwTitulo.setText("Firmar Inventario");
        } else if ("remision_tecnico".equals(flowType)) {
            tvwTitulo.setText("Firma Empleado");
        } else if ("remision_cliente".equals(flowType)) {
            tvwTitulo.setText("Firma Cliente");
        }

        mSignaturePad = findViewById(R.id.signature_pad);
        Button clearButton = findViewById(R.id.btn_Borrar_Firma);
        Button saveButton = findViewById(R.id.btn_Aceptar_Firma);

        clearButton.setOnClickListener(view -> mSignaturePad.clear());
        saveButton.setOnClickListener(view -> {
            if (mSignaturePad.isEmpty()) {
                Methods.showAlert(this, "Por favor, firme antes de continuar").show();
            } else {
                procesarFirma();
            }
        });
    }

    private void procesarFirma() {
        String fileName = "firma.png";
        if ("remision_tecnico".equals(flowType)) fileName = "firma_tecnico_" + consecutivo + ".png";
        else if ("remision_cliente".equals(flowType)) fileName = "firma_cliente_" + consecutivo + ".png";

        File signatureFile = new File(getFilesDir(), fileName);
        try (FileOutputStream out = new FileOutputStream(signatureFile)) {
            // Use getTransparentSignatureBitmap() or getSignatureBitmap() with solid bg
            // getSignatureBitmap() usually provides the solid background version
            Bitmap signatureBitmap = mSignaturePad.getSignatureBitmap();
            signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            
            // CRITICAL: Clear pad immediately to avoid parcel blob crash in background
            mSignaturePad.clear();
            Log.d(TAG, "Signature saved and pad cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error saving signature", e);
            Methods.showAlert(this, "Error guardando la firma, intente nuevamente").show();
            return;
        }

        if ("inventario".equals(flowType)) {
            finalizarFlowInventario(signatureFile);
        } else if (flowType != null && flowType.startsWith("remision_")) {
            Intent data = new Intent();
            data.putExtra("flowType", flowType);
            setResult(RESULT_OK, data);
            finish();
        } else {
            finalizarFlowGenerico(signatureFile);
        }
    }

    private void finalizarFlowInventario(File signatureFile) {
        Log.d(TAG, "Starting finalizarFlowInventario");
        
        // 1. Prepare Workbook
        XSSFWorkbook wb = null;
        File tempFile = new File(getFilesDir(), "Inventario_Temp.xlsx");
        try {
            if (tempFile.exists()) {
                wb = Methods.loadWorkbookFromFile(this, "Inventario_Temp.xlsx");
            } else {
                wb = Methods.loadInventarioWb(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "ERROR loading workbook", e);
        }

        // 2. Promotion & Cache Copy
        String originalName = Methods.getLastInventoryFileName(this);
        if (originalName == null || originalName.isEmpty()) originalName = "Inventario_Aceptado.xlsx";
        if (!originalName.toLowerCase().endsWith(".xlsx")) originalName += ".xlsx";

        // Save definitively as main database
        Methods.saveWorkbook(this, wb, "Inventario.xlsx");

        // Copy to cache for sharing
        File shareFile = new File(getCacheDir(), originalName);
        try {
            if (shareFile.getParentFile() != null && !shareFile.getParentFile().exists()) shareFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                if (wb != null) wb.write(fos);
            }
        } catch (Exception e) {
            Log.e(TAG, "ERROR saving share file", e);
        }

        // 3. Email process (Multiple Attachments)
        Log.d(TAG, "Launching email intent multiple");
        Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        emailIntent.setType("*/*"); // Broadest compatibility
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{""});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Inventario de Bodega Aceptado - " + (userData != null ? userData.getOrDefault("nombre", "") : ""));
        
        // STANDARD: Most email apps expect EXTRA_TEXT as a String even in ACTION_SEND_MULTIPLE
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Adjunto reporte de inventario y firma digital por separado.");

        ArrayList<Uri> uris = new ArrayList<>();
        if (shareFile.exists()) {
            Uri uXls = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", shareFile);
            uris.add(uXls);
            Log.d(TAG, "Added XLS URI: " + uXls);
        }
        if (signatureFile.exists()) {
            Uri uSig = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", signatureFile);
            uris.add(uSig);
            Log.d(TAG, "Added SIG URI: " + uSig);
        }

        if (!uris.isEmpty()) {
            emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            // CRITICAL: Explicitly grant permissions to the list
            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        startActivity(Intent.createChooser(emailIntent, "Enviar Inventario..."));

        // Show confirmation dialog before internal data promotion/cleanup
        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmación")
                .setMessage("¿Se envió el correo correctamente? Si confirma, el inventario será guardado permanentemente y se vaciará el carro.")
                .setPositiveButton("Sí, enviado", (dialog, which) -> {
                    // SECURE DELETION: Only now we delete both sensitive files
                    if (signatureFile.exists()) signatureFile.delete();
                    if (tempFile.exists()) tempFile.delete();
                    
                    Methods.saveCarInventory(this, new HashMap<>());
                    Methods.setInventarioWbToNull(); // Force reload next time

                    // Close activity immediately after confirmation
                    finish();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    // If NO, we stay here. Signature is sensitive, so we delete it anyway.
                    if (signatureFile.exists()) signatureFile.delete();
                    mSignaturePad.clear();
                    Methods.showAlert(this, "El envío fue cancelado. Por favor, firme nuevamente si desea reintentar.").show();
                })
                .setCancelable(false)
                .show();
    }

    private void finalizarFlowGenerico(File signatureFile) {
        if (signatureFile.exists()) signatureFile.delete();
        finish();
    }
}
