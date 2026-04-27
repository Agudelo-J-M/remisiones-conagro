package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.Serializable;
import java.util.HashMap;

/**
 * Activity for displaying and handling the Warehouse Inventory.
 */
public class InventarioBodegaActivity extends AppCompatActivity {

    private static final String TAG = "InventarioBodegaAct";
    private TableLayout tlyTabla;
    private AlertDialog alertDialog;
    private HashMap<String, String> userData;
    private XSSFWorkbook inventarioWb;

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
            alertDialog = Methods.showAlert(this, "Error recopilando datos del usuario, pongase en contacto con un administrador");
            Methods.setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }
        
        setupUI();
        cargarInventario();
    }

    private void setupUI() {
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Bodega);
        Button btnAceptar = findViewById(R.id.btn_Aceptar_Inventario_Bodega);
        Button btnRechazar = findViewById(R.id.btn_Rechazar_Inventario_Bodega);

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

    private void cargarInventario() {
        try {
            inventarioWb = Methods.loadInventarioWb(this);
            if (inventarioWb != null) {
                poblarTabla(inventarioWb);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading inventory", e);
            alertDialog = Methods.showAlert(this, "Error inesperado, comuniquese con un administrador");
            Methods.setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }
    }

    private void poblarTabla(XSSFWorkbook wb) {
        try {
            Sheet sheet = wb.getSheetAt(1);
            int idxItem = -1, idxNombre = -1, idxCant = -1;

            Row header = sheet.getRow(0);
            for (org.apache.poi.ss.usermodel.Cell cell : header) {
                String val = cell.toString().toLowerCase();
                if (val.contains("item")) idxItem = cell.getColumnIndex();
                else if (val.contains("nombre")) idxNombre = cell.getColumnIndex();
                else if (val.contains("cantidad")) idxCant = cell.getColumnIndex();
            }

            if (idxItem == -1 || idxNombre == -1 || idxCant == -1) {
                alertDialog = Methods.showAlert(this, "Error de formato en el Excel de Inventario.");
                Methods.setFinishActivityOnDismiss(alertDialog);
                alertDialog.show();
                return;
            }

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;

                String id = row.getCell(idxItem).toString();
                String name = row.getCell(idxNombre).toString();
                String qty = row.getCell(idxCant).toString();

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
