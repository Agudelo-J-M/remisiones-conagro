package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * Activity to manage the Vehicle Inventory. Allows adding, removing, and clearing items.
 * Validates against Warehouse Inventory (Inventario.xlsx) if available.
 */
public class InventarioCarroActivity extends AppCompatActivity {

    private EditText edtInput, edtCantidad;
    private TableLayout tlyTabla;
    private HashMap<String, Integer> inventarioData = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        
        // Error fix: Use correct layout instead of activity_menu
        setContentView(R.layout.activity_inventario_carro);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);
        initViews();
        setupListeners();

        // Initial warning if template is missing
        if (Methods.getInventarioWb() == null) {
            Methods.showAlert(this, "Advertencia: No se detectó el archivo de Inventario de Bodega. Las validaciones de stock estarán deshabilitadas.").show();
        }
    }

    private void initViews() {
        edtInput = findViewById(R.id.edt_IDProducto_Inventario_Carro);
        edtCantidad = findViewById(R.id.edt_Cantidad_Inventario_Carro);
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Carro);
    }

    private void setupListeners() {
        findViewById(R.id.btn_Anadir_Inventario_Carro).setOnClickListener(v -> procesarEntrada());
        findViewById(R.id.btn_Vaciar_Inventario_Carro).setOnClickListener(v -> vaciarInventarioCompleto());
    }

    private void procesarEntrada() {
        String inputStr = edtInput.getText().toString().trim();
        String cantStr = edtCantidad.getText().toString().trim();

        if (inputStr.isEmpty() || cantStr.isEmpty()) {
            Methods.showAlert(this, "Por favor complete todos los campos").show();
            return;
        }

        // Support for search by Name or Reference
        Map<String, String> prodDetails = Methods.getProductoDetails(this, inputStr);
        if (prodDetails == null) {
            Methods.showAlert(this, "Error: Producto '" + inputStr + "' no encontrado en el maestro de productos.").show();
            return;
        }

        String ref = prodDetails.get("referencia");
        String name = prodDetails.get("nombre");

        int nuevaCantidad;
        try {
            nuevaCantidad = Integer.parseInt(cantStr);
        } catch (NumberFormatException e) {
            Methods.showAlert(this, "Cantidad inválida").show();
            return;
        }

        // Template validation (Cumulative stock check)
        if (Methods.getInventarioWb() != null) {
            double saldo = Methods.getInventarioSaldo(ref);
            if (saldo == -1.0) {
                // Try by name if ref wasn't found in inventory
                saldo = Methods.getInventarioSaldo(name);
            }

            if (saldo == -1.0) {
                Methods.showAlert(this, "Advertencia: El producto '" + inputStr + "' no se encuentra en el inventario de bodega.").show();
            } else {
                int totalEnCarro = inventarioData.getOrDefault(ref, 0) + nuevaCantidad;
                if (totalEnCarro > saldo) {
                    Methods.showAlert(this, "Advertencia: La cantidad total en carro (" + totalEnCarro + ") supera el saldo en bodega (" + saldo + ").").show();
                }
            }
        }

        // Add to map using official Reference ID
        inventarioData.put(ref, inventarioData.getOrDefault(ref, 0) + nuevaCantidad);

        actualizarTablaUI();
        limpiarInputs();
    }

    private void actualizarTablaUI() {
        int childCount = tlyTabla.getChildCount();
        if (childCount > 1) {
            tlyTabla.removeViews(1, childCount - 1);
        }

        for (Map.Entry<String, Integer> entry : inventarioData.entrySet()) {
            String id = entry.getKey();
            String qty = String.valueOf(entry.getValue());
            String name = Methods.getProductoName(this, id);

            TableRow row = new TableRow(this);
            row.setPadding(0, 10, 0, 10);

            row.addView(crearTextViewCelda(id, 1f));
            row.addView(crearTextViewCelda(name != null ? name : "Error", 1.5f));
            row.addView(crearTextViewCelda(qty, 0.8f));
            row.addView(crearBotonEliminar(id));

            tlyTabla.addView(row);
        }
    }

    private TextView crearTextViewCelda(String texto, float weight) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight));
        tv.setPadding(12, 12, 12, 12);
        return tv;
    }

    private View crearBotonEliminar(String idAEliminar) {
        ImageButton btnBorrar = new ImageButton(this);
        btnBorrar.setImageResource(android.R.drawable.ic_menu_delete);
        btnBorrar.setBackgroundColor(Color.TRANSPARENT);
        btnBorrar.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.7f));
        btnBorrar.setOnClickListener(v -> {
            inventarioData.remove(idAEliminar);
            actualizarTablaUI();
        });
        return btnBorrar;
    }

    private void vaciarInventarioCompleto() {
        inventarioData.clear();
        actualizarTablaUI();
        Methods.showAlert(this, "Inventario vaciado").show();
    }

    private void limpiarInputs() {
        edtInput.setText("");
        edtCantidad.setText("");
        edtInput.requestFocus();
    }
}
