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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        cargarInventario();
    }

    private void cargarInventario() {
        tlyTabla.removeAllViews();
        try {
            // 1. Load Current Definitive Inventory as reference
            Map<String, Double> currentInventory = new HashMap<>();
            Map<String, String> currentNames = new HashMap<>();
            XSSFWorkbook definitiveWb = Methods.getInventarioWb();
            if (definitiveWb == null) definitiveWb = Methods.loadInventarioWb(this);
            if (definitiveWb != null) {
                Sheet defSheet = definitiveWb.getSheet("Inventario");
                if (defSheet == null) defSheet = definitiveWb.getSheetAt(0);

                DataFormatter df = Methods.getDataFormatter();
                int idxRef = -1, idxSaldo = -1, idxNombre = -1;
                Row h = defSheet.getRow(0);
                if (h != null) {
                    for (Cell c : h) {
                        String v = df.formatCellValue(c).toLowerCase().trim();
                        if (v.contains("referencia")) idxRef = c.getColumnIndex();
                        else if (v.contains("saldo")) idxSaldo = c.getColumnIndex();
                        else if (v.contains("nombre")) idxNombre = c.getColumnIndex();
                    }
                }

                if (idxRef != -1 && idxSaldo != -1) {
                    for (Row r : defSheet) {
                        if (r.getRowNum() == 0) continue;
                        String ref = df.formatCellValue(r.getCell(idxRef));
                        String saldoStr = df.formatCellValue(r.getCell(idxSaldo)).replace(",", ".");
                        try {
                            if (!ref.isEmpty()) {
                                currentInventory.put(ref, Double.parseDouble(saldoStr));
                                if (idxNombre != -1) currentNames.put(ref, df.formatCellValue(r.getCell(idxNombre)));
                            }
                        } catch (Exception e) {}
                    }
                }
            }

            // 2. Load Target Workbook (Temp or Definitive)
            inventarioWb = Methods.loadWorkbookFromFile(this, "Inventario_Temp.xlsx");
            boolean hasTemp = (inventarioWb != null);
            // Only compare columns when there is a previously accepted inventory to compare against.
            // On a fresh install (no definitive yet) we just show the new inventory as-is.
            boolean isComparison = hasTemp && !currentInventory.isEmpty();

            if (!hasTemp) {
                isTemporaryView = false;
                btnAceptar.setVisibility(View.GONE);
                btnRechazar.setVisibility(View.GONE);
                inventarioWb = definitiveWb;
            } else {
                isTemporaryView = true;
                btnAceptar.setVisibility(View.VISIBLE);
                btnRechazar.setVisibility(View.VISIBLE);

                if (!alertShowing) {
                    alertShowing = true;
                    String msg = isComparison
                            ? "Mostrando Nuevo Inventario Pendiente de Aceptación."
                            : "Mostrando Nuevo Inventario. No hay inventario previo para comparar.";
                    AlertDialog ad = Methods.showAlert(this, msg);
                    ad.setOnDismissListener(dialog -> alertShowing = false);
                    ad.show();
                }
            }

            if (inventarioWb != null) {
                poblarTabla(inventarioWb, isComparison ? currentInventory : null, currentNames);
            } else {
                Methods.showAlert(this, "No se ha cargado archivo de inventario de bodega").show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading inventory", e);
            Methods.showFatalError(this, "Error inesperado, comuniquese con un administrador");
        }
    }

    private void poblarTabla(XSSFWorkbook wb, Map<String, Double> currentRef, Map<String, String> currentNames) {
        try {
            Sheet sheet = wb.getSheet("Inventario");
            if (sheet == null) sheet = wb.getSheetAt(0);

            int idxItem = -1, idxNombre = -1, idxCant = -1;
            DataFormatter df = Methods.getDataFormatter();

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return;

            StringBuilder headerDump = new StringBuilder();
            for (Cell cell : headerRow) {
                String raw = df.formatCellValue(cell);
                headerDump.append("[").append(raw).append("]");
                String val = raw.toLowerCase().trim();
                if (val.contains("referencia")) idxItem = cell.getColumnIndex();
                else if (val.contains("nombre")) idxNombre = cell.getColumnIndex();
                else if (val.contains("saldo")) idxCant = cell.getColumnIndex();
            }
            if (idxItem == -1 || idxNombre == -1 || idxCant == -1) {
                Methods.showAlert(this, "No se detectaron las columnas (referencia/nombre/saldo).\nCabeceras leídas: " + headerDump).show();
                return;
            }

            // Header UI
            TableRow tableHeader = new TableRow(this);
            tableHeader.setBackgroundColor(Color.LTGRAY);
            tableHeader.setPadding(8, 8, 8, 8);
            tableHeader.addView(crearTextViewCelda("Ref", 1.2f));
            tableHeader.addView(crearTextViewCelda("Producto", 2.2f));
            if (currentRef != null) {
                tableHeader.addView(crearTextViewCelda("Anterior", 1f));
                tableHeader.addView(crearTextViewCelda("Nuevo", 1f));
            } else {
                tableHeader.addView(crearTextViewCelda("Cant", 1f));
            }
            tlyTabla.addView(tableHeader);

            List<InventoryData> list = new ArrayList<>();
            java.util.Set<String> seenRefs = new java.util.HashSet<>();
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String id = df.formatCellValue(row.getCell(idxItem));
                String name = df.formatCellValue(row.getCell(idxNombre));
                String qtyNewStr = df.formatCellValue(row.getCell(idxCant)).replace(",", ".");

                double valNew = 0;
                try { valNew = Double.parseDouble(qtyNewStr); } catch (Exception e) {}

                if (currentRef == null) {
                    // Plain view (no comparison)
                    list.add(new InventoryData(id, name, valNew, valNew, false));
                    continue;
                }

                seenRefs.add(id);
                Double oldValObj = currentRef.get(id);
                if (oldValObj == null) {
                    // Added: product not present in the previous inventory (shown as 0 -> valNew)
                    list.add(new InventoryData(id, name, 0, valNew, true));
                } else {
                    list.add(new InventoryData(id, name, oldValObj, valNew, oldValObj != valNew));
                }
            }

            // Surface removed products: present before, absent in the new file (shown as valOld -> 0)
            if (currentRef != null) {
                for (Map.Entry<String, Double> entry : currentRef.entrySet()) {
                    if (!seenRefs.contains(entry.getKey())) {
                        String name = (currentNames != null)
                                ? currentNames.getOrDefault(entry.getKey(), entry.getKey())
                                : entry.getKey();
                        list.add(new InventoryData(entry.getKey(), name, entry.getValue(), 0, true));
                    }
                }
            }

            if (list.isEmpty()) {
                Methods.showAlert(this, "El inventario no contiene filas de datos.").show();
            }

            // Sort: Differences first (isDifferent=true), then by Name
            list.sort((a, b) -> {
                if (a.isDifferent != b.isDifferent) return b.isDifferent ? 1 : -1;
                return a.name.compareToIgnoreCase(b.name);
            });

            for (InventoryData data : list) {
                TableRow row = new TableRow(this);
                row.setPadding(0, 10, 0, 10);
                row.addView(crearTextViewCelda(data.id, 1.2f));
                
                TextView tvName = crearTextViewCelda(data.name, 2.2f);
                tvName.setSingleLine(false);
                tvName.setMaxLines(3);
                row.addView(tvName);

                if (currentRef != null) {
                    TextView tvOld = crearTextViewCelda(fmtQty(data.oldQty), 1f);
                    TextView tvNew = crearTextViewCelda(fmtQty(data.newQty), 1f);

                    if (data.isDifferent) {
                        tvOld.setTextColor(Color.RED);
                        tvNew.setTextColor(Color.RED);
                    } else {
                        tvOld.setTextColor(Color.parseColor("#2E7D32")); // Dark Green
                        tvNew.setTextColor(Color.parseColor("#2E7D32"));
                    }
                    row.addView(tvOld);
                    row.addView(tvNew);
                } else {
                    row.addView(crearTextViewCelda(fmtQty(data.newQty), 1f));
                }

                tlyTabla.addView(row);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error populating table", e);
        }
    }

    private static class InventoryData {
        String id, name;
        double oldQty, newQty;
        boolean isDifferent;
        InventoryData(String id, String name, double old, double newQ, boolean isDifferent) {
            this.id = id; this.name = name; this.oldQty = old; this.newQty = newQ;
            this.isDifferent = isDifferent;
        }
    }

    /** Formats an inventory quantity, keeping decimals only when present (avoids truncation). */
    private String fmtQty(double v) {
        return new java.text.DecimalFormat("0.##").format(v);
    }

    private TextView crearTextViewCelda(String texto, float weight) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight));
        tv.setPadding(12, 12, 12, 12);
        return tv;
    }
}
