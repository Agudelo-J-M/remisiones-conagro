package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.TableLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.Map;

public class InventarioCarroActivity extends AppCompatActivity {

    // UI Components
    private EditText edtID, edtCantidad;
    private Button btnAnadir, btnVaciar;
    private TableLayout tlyTabla;
    private String[] userData;

    // Data Structure: Key = Product ID, Value = Quantity
    // This handles the "merge same ID" logic automatically
    private HashMap<String, Integer> inventarioData = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_menu);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);

        AlertDialog alertDialog = null;
        userData = getIntent().getStringArrayExtra("userData");

        initViews();
        setupListeners();
    }

    /**
     * Link Java objects to XML IDs
     */
    private void initViews() {
        edtID = findViewById(R.id.edt_IDProducto_Inventario_Carro);
        edtCantidad = findViewById(R.id.edt_Cantidad_Inventario_Carro);
        btnAnadir = findViewById(R.id.btn_Anadir_Inventario_Carro);
        btnVaciar = findViewById(R.id.btn_Vaciar_Inventario_Carro);
        tlyTabla = findViewById(R.id.tly_TablaDatos_Inventario_Carro);
    }

    /**
     * Setup button click actions
     */
    private void setupListeners() {
        btnAnadir.setOnClickListener(v -> procesarEntrada());
        btnVaciar.setOnClickListener(v -> vaciarInventarioCompleto());
    }

    /**
     * Collects input, validates it, and updates the data structure
     */
    private void procesarEntrada() {
        String id = edtID.getText().toString().trim();
        String cantStr = edtCantidad.getText().toString().trim();

        if (id.isEmpty() || cantStr.isEmpty()) {
            Toast.makeText(this, "Por favor complete todos los campos", Toast.LENGTH_SHORT).show();
            return;
        }

        int nuevaCantidad = Integer.parseInt(cantStr);

        // LOGIC: If ID exists, add to current quantity. If not, create new.
        if (inventarioData.containsKey(id)) {
            int cantidadActual = inventarioData.get(id);
            inventarioData.put(id, cantidadActual + nuevaCantidad);
        } else {
            inventarioData.put(id, nuevaCantidad);
        }

        actualizarTablaUI();
        limpiarInputs();
    }

    /**
     * Refreshes the TableLayout based on the current HashMap
     */
    private void actualizarTablaUI() {
        // 1. Clear all rows except the header (Index 0)
        int childCount = tlyTabla.getChildCount();
        if (childCount > 1) {
            tlyTabla.removeViews(1, childCount - 1);
        }

        // 2. Iterate through data and create rows
        for (Map.Entry<String, Integer> entry : inventarioData.entrySet()) {
            String id = entry.getKey();
            String cantidad = String.valueOf(entry.getValue());
            String nombrePlaceholder = "Cargando..."; // Logic for DB goes here later

            TableRow row = new TableRow(this);
            row.setPadding(0, 10, 0, 10);

            // Column 1: ID
            row.addView(crearTextViewCelda(id, 1f));
            // Column 2: Name
            row.addView(crearTextViewCelda(nombrePlaceholder, 1f));
            // Column 3: Quantity
            row.addView(crearTextViewCelda(cantidad, 1f));
            // Column 4: Delete Button
            row.addView(crearBotonEliminar(id));

            tlyTabla.addView(row);
        }
    }

    /**
     * Helper to create styled TextViews for table cells
     */
    private TextView crearTextViewCelda(String texto, float weight) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight));
        tv.setPadding(12, 12, 12, 12);
        return tv;
    }

    /**
     * Helper to create the delete button for each row
     */
    private View crearBotonEliminar(String idAEliminar) {
        ImageButton btnBorrar = new ImageButton(this);

        // Using a standard Android system icon for the trash can
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
        Toast.makeText(this, "Inventario vaciado", Toast.LENGTH_SHORT).show();
    }

    private void limpiarInputs() {
        edtID.setText("");
        edtCantidad.setText("");
        edtID.requestFocus();
    }
}