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
 */
public class InventarioCarroActivity extends AppCompatActivity {

    private EditText edtID, edtCantidad;
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
    }

    private void initViews() {
        edtID = findViewById(R.id.edt_IDProducto_Inventario_Carro);
        edtCantidad = findViewById(R.id.edt_Cantidad_Inventario_Carro);
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Carro);
    }

    private void setupListeners() {
        findViewById(R.id.btn_Anadir_Inventario_Carro).setOnClickListener(v -> procesarEntrada());
        findViewById(R.id.btn_Vaciar_Inventario_Carro).setOnClickListener(v -> vaciarInventarioCompleto());
    }

    private void procesarEntrada() {
        String id = edtID.getText().toString().trim();
        String cantStr = edtCantidad.getText().toString().trim();

        if (id.isEmpty() || cantStr.isEmpty()) {
            Methods.showAlert(this, "Por favor complete todos los campos").show();
            return;
        }

        String productName = Methods.getProductoName(this, id);
        if (productName == null) {
            Methods.showAlert(this, "Error: Producto con ID " + id + " no encontrado").show();
            return;
        }

        int nuevaCantidad;
        try {
            nuevaCantidad = Integer.parseInt(cantStr);
        } catch (NumberFormatException e) {
            Methods.showAlert(this, "Cantidad inválida").show();
            return;
        }

        // Add to map (merging quantities if ID exists)
        inventarioData.put(id, inventarioData.getOrDefault(id, 0) + nuevaCantidad);

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
            row.addView(crearTextViewCelda(name != null ? name : "Error", 1f));
            row.addView(crearTextViewCelda(qty, 1f));
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
        btnBorrar.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f));
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
        edtID.setText("");
        edtCantidad.setText("");
        edtID.requestFocus();
    }
}
