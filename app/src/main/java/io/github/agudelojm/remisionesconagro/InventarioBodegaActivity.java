package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.Serializable;
import java.util.HashMap;

/**
 * Activity for displaying and handling the Warehouse Inventory.
 * Adjusted to handle the CSV format headers: cod_suc, cod_bod, ano, referencia, nombre, saldo.
 */
public class InventarioBodegaActivity extends AppCompatActivity {

    private static final String TAG = "InventarioBodegaAct";
    private TableLayout tlyTabla;
    private Button btnAceptar, btnRechazar;
    private HashMap<String, String> userData;
    private XSSFWorkbook inventarioWb;
    private boolean alertShowing = false;
    private boolean isTemporaryView = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_inventario_bodega);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);
        
        // Handle User Data
        Intent intent = getIntent();
        Serializable serializable = intent.getSerializableExtra("userData");
        if (!(serializable instanceof HashMap)) {
            serializable = intent.getSerializableExtra("hashMapKey");
        }

        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        } else {
            Methods.showFatalError(this, "Error recopilando datos del usuario, pongase en contacto con un administrador");
            return;
        }
        
        setupUI();
        cargarInventario();
    }

    private void setupUI() {
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Bodega);
        btnAceptar = findViewById(R.id.btn_Aceptar_Inventario_Bodega);
        btnRechazar = findViewById(R.id.btn_Rechazar_Inventario_Bodega);

        btnAceptar.setOnClickListener(v -> {
            Intent i = new Intent(this, PedirFirmaActivity.class);
            i.putExtra("userData", userData);
            i.putExtra("flowType", "inventario");
            startActivity(i);
        });

        btnRechazar.setOnClickListener(v -> {
            Intent i = new Intent(this, RechazarInventarioBodegaActivity.class);
            i.putExtra("userData", userData);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh table in case a temp file was deleted or accepted
        tlyTabla.removeAllViews();
        // Re-add header
        TableRow header = new TableRow(this);
        header.setBackgroundColor(Color.LTGRAY);
        header.setPadding(8,8,8,8);
        header.addView(crearTextViewCelda("Ref", 1f));
        header.addView(crearTextViewCelda("Nombre", 2f));
        header.addView(crearTextViewCelda("Cant", 1f));
        tlyTabla.addView(header);
        
        cargarInventario();
    }

    private void cargarInventario() {
        try {
            // Priority 1: Temporary Inventory (New incoming file)
            inventarioWb = Methods.loadWorkbookFromFile(this, "Inventario_Temp.xlsx");
            
            if (inventarioWb != null) {
                isTemporaryView = true;
                btnAceptar.setVisibility(View.VISIBLE);
                btnRechazar.setVisibility(View.VISIBLE);
                poblarTabla(inventarioWb);
                
                if (!alertShowing) {
                    alertShowing = true;
                    AlertDialog ad = Methods.showAlert(this, "Mostrando Nuevo Inventario Pendiente de Aceptación.");
                    ad.setOnDismissListener(dialog -> alertShowing = false);
                    ad.show();
                }
                return;
            }

            // Priority 2: Definitive Inventory
            isTemporaryView = false;
            btnAceptar.setVisibility(View.GONE);
            btnRechazar.setVisibility(View.GONE);

            inventarioWb = Methods.getInventarioWb();
            if (inventarioWb == null) {
                inventarioWb = Methods.loadInventarioWb(this);
            }
            
            if (inventarioWb != null) {
                poblarTabla(inventarioWb);
            } else {
                Methods.showAlert(this, "Advertencia: El archivo 'Inventario.xlsx' no está cargado.").show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading inventory", e);
            Methods.showFatalError(this, "Error inesperado, comuniquese con un administrador");
        }
    }

    private void poblarTabla(XSSFWorkbook wb) {
        try {
            // Priotitize the "Inventario" sheet name, fallback to first sheet
            Sheet sheet = wb.getSheet("Inventario");
            if (sheet == null) sheet = wb.getSheetAt(0);

            int idxItem = -1, idxNombre = -1, idxCant = -1;
            DataFormatter df = Methods.getDataFormatter();

            Row header = sheet.getRow(0);
            if (header == null) {
                Methods.showErrorAndFinishActivity(this, "El archivo de inventario está vacío.");
                return;
            }

            for (org.apache.poi.ss.usermodel.Cell cell : header) {
                String val = df.formatCellValue(cell).toLowerCase().trim();
                // Map CSV headers to logic: referencia -> item, nombre -> nombre, saldo -> cantidad
                if (val.contains("referencia")) idxItem = cell.getColumnIndex();
                else if (val.contains("nombre")) idxNombre = cell.getColumnIndex();
                else if (val.contains("saldo")) idxCant = cell.getColumnIndex();
            }

            if (idxItem == -1 || idxNombre == -1 || idxCant == -1) {
                Methods.showErrorAndFinishActivity(this, "Error de formato en el Excel de Inventario (Faltan columnas: referencia, nombre o saldo).");
                return;
            }

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;

                // Use DataFormatter to prevent corrupted scientific notation on numeric IDs
                String id = df.formatCellValue(row.getCell(idxItem));
                String name = df.formatCellValue(row.getCell(idxNombre));
                String qtyRaw = df.formatCellValue(row.getCell(idxCant));
                
                // Format quantity as whole number
                String qty = qtyRaw;
                try {
                    double val = Double.parseDouble(qtyRaw.replace(",", "."));
                    qty = String.valueOf((int) val);
                } catch (Exception e) { /* keep raw if not numeric */ }

                TableRow tableRow = new TableRow(this);
                tableRow.setPadding(0, 10, 0, 10);
                tableRow.addView(crearTextViewCelda(id, 1f));
                tableRow.addView(crearTextViewCelda(name, 2f));
                tableRow.addView(crearTextViewCelda(qty, 1f));

                tlyTabla.addView(tableRow);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error populating table", e);
        }
    }

    private TextView crearTextViewCelda(String texto, float weight) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight));
        tv.setPadding(12, 12, 12, 12);
        return tv;
    }
}
