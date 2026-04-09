package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
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

public class InventarioBodegaActivity extends AppCompatActivity {

    private TableLayout tlyTabla;
    private AlertDialog alertDialog;
    private Button btnAceptar, btnRechazar;
    HashMap<String, String> userData;

    XSSFWorkbook inventarioWb;

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
        Intent intent = getIntent();
        Serializable serializable =  intent.getSerializableExtra("hashMapKey");
        if (serializable instanceof java.util.HashMap<?, ?>) {
            userData =  (HashMap<String, String>) serializable;
        } else {
            alertDialog = Methods.showAlert(this, "Error recopilando datos del usuario, pongase en contacto con un administrador");
            Methods.setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }
        
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Bodega);
        btnAceptar = findViewById(R.id.btn_Aceptar_Inventario_Bodega);
        btnRechazar = findViewById(R.id.btn_Rechazar_Inventario_Bodega);

        btnAceptar.setOnClickListener(v -> {
            Intent i = new Intent(this, PedirFirmaActivity.class);
            i.putExtra("userData", userData);
            startActivity(i);
        });

        btnRechazar.setOnClickListener(v -> {
            Intent i = new Intent(this, RechazarInventarioBodegaActivity.class);
            i.putExtra("userData", userData);
            startActivity(i);
        });

        cargarInventario();
    }

    private void cargarInventario() {
        try {
            inventarioWb = Methods.loadInventarioWb(this);
        } catch (Exception e) {
            alertDialog = Methods.showAlert(this, "Error inesperado, comuniquese con un administrador");
            Methods.setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
            return;
        }
        if(inventarioWb == null){
            return;
        }

        poblarTabla(inventarioWb);
    }

    private void poblarTabla(XSSFWorkbook wb) {
        Sheet sheet = wb.getSheetAt(1);
        int indexItem = -1;
        int indexNombre = -1;
        int indexCantidad = -1;

        Row headerRow = sheet.getRow(0);
        for (org.apache.poi.ss.usermodel.Cell cell : headerRow) {
            String header = cell.toString().toLowerCase();
            if (header.contains("item")) indexItem = cell.getColumnIndex();
            if (header.contains("nombre")) indexNombre = cell.getColumnIndex();
            if (header.contains("cantidad")) indexCantidad = cell.getColumnIndex();
        }

        if (indexItem == -1 || indexNombre == -1 || indexCantidad == -1) {
            alertDialog = Methods.showAlert(this, "Error de formato en el Excel de Inventario. Verifique las cabeceras.");
            Methods.setFinishActivityOnDismiss(alertDialog);
            alertDialog.show();
            return;
        }

        for (Row row : sheet) {
            if (row.getRowNum() == 0) continue;

            String id = row.getCell(indexItem).toString();
            String nombre = row.getCell(indexNombre).toString();
            String cantidad = row.getCell(indexCantidad).toString();

            TableRow tableRow = new TableRow(this);
            tableRow.setPadding(0, 10, 0, 10);

            tableRow.addView(crearTextViewCelda(id, 1f));
            tableRow.addView(crearTextViewCelda(nombre, 2f));
            tableRow.addView(crearTextViewCelda(cantidad, 1f));

            tlyTabla.addView(tableRow);
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
