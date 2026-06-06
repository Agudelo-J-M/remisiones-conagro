package io.github.agudelojm.remisionesconagro;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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

    private AutoCompleteTextView edtInput;
    private EditText edtCantidad;
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

        // Load persisted inventory
        inventarioData = Methods.getCarInventory(this);
        actualizarTablaUI();

        // Initial warning if template is missing
        if (Methods.getInventarioWb() == null) {
            Methods.showAlert(this, "Advertencia: No se detectó el archivo de Inventario de Bodega. Las validaciones de stock estarán deshabilitadas.").show();
        }
    }

    private void initViews() {
        edtInput = findViewById(R.id.edt_IDProducto_Inventario_Carro);
        edtCantidad = findViewById(R.id.edt_Cantidad_Inventario_Carro);
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Carro);

        // Set up Autocomplete
        ArrayAdapter<String> productAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, Methods.getListaProductos());
        edtInput.setAdapter(productAdapter);
    }

    private void setupListeners() {
        findViewById(R.id.btn_Anadir_Inventario_Carro).setOnClickListener(v -> procesarEntrada());
        findViewById(R.id.btn_Vaciar_Inventario_Carro).setOnClickListener(v -> vaciarInventarioCompleto());
    }

    private void procesarEntrada() {
        String query = edtInput.getText().toString().trim();
        String cantStr = edtCantidad.getText().toString().trim();

        if (query.isEmpty() || cantStr.isEmpty()) {
            Methods.showAlert(this, "Por favor complete todos los campos").show();
            return;
        }

        // Support for search by Name or Reference (handles "Ref - Name" from autocomplete)
        Map<String, String> prodDetails = Methods.buscarProducto(query);
        if (prodDetails == null) {
            Methods.showAlert(this, "Error: Producto '" + query + "' no encontrado.").show();
            return;
        }

        String ref = prodDetails.get("id");
        String name = prodDetails.get("nombre");

        int nuevaCantidad;
        try {
            nuevaCantidad = Integer.parseInt(cantStr);
        } catch (NumberFormatException e) {
            Methods.showAlert(this, "Cantidad inválida").show();
            return;
        }

        // Rule 1: Validation against Warehouse Stock with "Add or Return" options
        if (Methods.getInventarioWb() != null) {
            double saldo = Methods.getInventarioSaldo(ref);
            if (saldo == -1.0) {
                // Try by name if ref wasn't found in inventory
                saldo = Methods.getInventarioSaldo(name);
            }

            if (saldo == -1.0) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Advertencia")
                        .setMessage("El producto '" + ref + "' no se encuentra en el inventario de bodega. ¿Desea añadirlo de todos modos?")
                        .setPositiveButton("Añadir", (dialog, which) -> commitEntrada(ref, nuevaCantidad))
                        .setNegativeButton("Volver", (dialog, which) -> dialog.dismiss())
                        .show();
                return;
            } else {
                int totalEnCarro = inventarioData.getOrDefault(ref, 0) + nuevaCantidad;
                if (totalEnCarro > saldo) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Advertencia")
                            .setMessage("La cantidad total en carro (" + totalEnCarro + ") supera el saldo en bodega (" + (int)saldo + "). ¿Desea añadirlo de todos modos?")
                            .setPositiveButton("Añadir", (dialog, which) -> commitEntrada(ref, nuevaCantidad))
                            .setNegativeButton("Volver", (dialog, which) -> dialog.dismiss())
                            .show();
                    return;
                }
            }
        }

        commitEntrada(ref, nuevaCantidad);
    }

    private void commitEntrada(String ref, int cantidad) {
        // Add to map using official Reference ID
        inventarioData.put(ref, inventarioData.getOrDefault(ref, 0) + cantidad);

        // Persist changes
        Methods.saveCarInventory(this, inventarioData);

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
            row.setPadding(8, 8, 8, 8);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            row.addView(crearTextViewCelda(id, 1.1f));
            
            TextView tvNombre = crearTextViewCelda(name != null ? name : "Error", 1.8f);
            tvNombre.setSingleLine(false);
            tvNombre.setMaxLines(3);
            row.addView(tvNombre);

            row.addView(crearTextViewCelda(qty, 0.5f));
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
            Methods.saveCarInventory(this, inventarioData);
            actualizarTablaUI();
        });
        return btnBorrar;
    }

    private void vaciarInventarioCompleto() {
        inventarioData.clear();
        Methods.saveCarInventory(this, inventarioData);
        actualizarTablaUI();
        Methods.showAlert(this, "Inventario vaciado").show();
    }

    private void limpiarInputs() {
        edtInput.setText("");
        edtCantidad.setText("");
        edtInput.requestFocus();
    }
}
