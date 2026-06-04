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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pedir_firma);

        Methods.updateActivity(this);

        // Handle Intent Extras
        Intent intent = getIntent();
        Serializable serializable = intent.getSerializableExtra("userData");
        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }
        flowType = intent.getStringExtra("flowType");
        consecutivo = intent.getStringExtra("consecutivo");
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
            Bitmap signatureBitmap = mSignaturePad.getSignatureBitmap();
            signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
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
        // 1. Load the signature as bytes to insert into the XLSX
        byte[] imageBytes = null;
        try {
            Bitmap signatureBitmap = BitmapFactory.decodeFile(signatureFile.getAbsolutePath());
            if (signatureBitmap != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                imageBytes = baos.toByteArray();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing signature for XLSX", e);
        }

        // 2. Load workbook and insert image
        XSSFWorkbook wb = null;
        File tempFile = new File(getFilesDir(), "Inventario_Temp.xlsx");
        
        try {
            if (tempFile.exists()) {
                wb = Methods.loadWorkbookFromFile(this, "Inventario_Temp.xlsx");
            } else {
                wb = Methods.getInventarioWb();
                if (wb == null) wb = Methods.loadInventarioWb(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading workbook for signature", e);
        }

        if (wb != null && imageBytes != null) {
            try {
                Sheet sheet = wb.getSheetAt(0);
                int lastRow = sheet.getLastRowNum();
                // Insert signature after the last data row
                Methods.insertImageToWorkbook(wb, imageBytes, 0, lastRow + 2, 1);
            } catch (Exception e) {
                Log.e(TAG, "Error inserting signature into workbook", e);
            }
        }

        // 3. Save the modified XLSX
        // If it was a temp inventory, it now becomes the definitive one
        Methods.saveWorkbook(this, wb, "Inventario.xlsx");
        
        // Reload global reference if it's the main inventory
        Methods.loadInventarioWb(this);

        // 4. Email process
        Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        emailIntent.setType("message/rfc822");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{""}); // Empty so the user fills it
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Inventario de Bodega - " + 
                (userData != null ? userData.getOrDefault("nombre", "") : ""));
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Adjunto reporte de inventario con firma digital.");

        ArrayList<Uri> uris = new ArrayList<>();
        File excelFile = new File(getFilesDir(), "Inventario.xlsx");
        
        if (excelFile.exists()) {
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", excelFile));
        }
        if (signatureFile.exists()) {
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", signatureFile));
        }

        emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(emailIntent, "Enviar Inventario..."));

        // 5. Cleanup
        if (signatureFile.exists()) signatureFile.delete();
        if (tempFile.exists()) tempFile.delete();

        // Final Requirement: Clear Car Inventory when new Warehouse Inventory is accepted
        Methods.saveCarInventory(this, new HashMap<>());

        Methods.showAlert(this, "Inventario de Bodega Actualizado. El inventario del carro ha sido vaciado para asegurar la sincronización.")
                .setOnDismissListener(dialog -> finish());
    }

    private void finalizarFlowGenerico(File signatureFile) {
        if (signatureFile.exists()) signatureFile.delete();
        finish();
    }
}
