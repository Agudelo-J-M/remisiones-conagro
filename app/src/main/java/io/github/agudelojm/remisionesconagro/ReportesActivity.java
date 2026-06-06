package io.github.agudelojm.remisionesconagro;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReportesActivity extends AppCompatActivity {

    private Spinner spnTipo;
    private AutoCompleteTextView edtInput;
    private TableLayout tlyTabla;
    private TextView tvwMensaje;
    private final SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_reportes);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);
        initViews();
    }

    private void initViews() {
        spnTipo = findViewById(R.id.spn_TipoBusqueda_Reportes);
        edtInput = findViewById(R.id.edt_InputBusqueda_Reportes);
        tlyTabla = findViewById(R.id.tly_TablaDatos_Reportes);
        tvwMensaje = findViewById(R.id.tvw_Mensaje_Reportes);
        Button btnBuscar = findViewById(R.id.btn_Buscar_Reportes);
        Button btnMostrarTodo = findViewById(R.id.btn_MostrarTodo_Reportes);

        String[] options = {"Producto ID", "Remisión (Número)", "Finca ID"};
        ArrayAdapter<String> spnAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        spnAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnTipo.setAdapter(spnAdapter);

        // Update autocomplete suggestions based on selection
        spnTipo.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                edtInput.setText("");
                actualizarAutocomplete(position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnBuscar.setOnClickListener(v -> ejecutarBusqueda());
        btnMostrarTodo.setOnClickListener(v -> mostrarTodo());
    }

    private void actualizarAutocomplete(int type) {
        List<String> suggestions = new ArrayList<>();
        if (type == 0) { // Producto
            suggestions = Methods.getListaProductos();
        } else if (type == 1) { // Remision
            List<Map<String, String>> rems = Methods.getListaRemisiones(this);
            for (Map<String, String> r : rems) {
                suggestions.add(r.get("consecutivo"));
            }
        } else if (type == 2) { // Finca
            suggestions = Methods.getListaFincas();
        }
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, suggestions);
        edtInput.setAdapter(adapter);
    }

    private void ejecutarBusqueda() {
        String query = edtInput.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) {
            tvwMensaje.setText("Ingrese un término de búsqueda");
            tvwMensaje.setVisibility(View.VISIBLE);
            return;
        }

        int type = spnTipo.getSelectedItemPosition(); // 0: Prod, 1: Rem, 2: Finca
        
        // Clean query if it came from autocomplete "ID - Name"
        String finalQuery = query.split(" - ")[0].trim();

        List<JSONObject> all = Methods.getAllRemisionesData(this);
        
        // Sort by date desc
        Collections.sort(all, (a, b) -> Long.compare(b.optLong("fecha"), a.optLong("fecha")));

        limpiarTabla();
        boolean found = false;

        for (JSONObject rem : all) {
            try {
                String consecutivo = rem.getString("consecutivo");
                JSONObject finca = rem.getJSONObject("fincaData");
                String fincaId = finca.optString("finca_id", "").toLowerCase();
                String fincaNom = finca.optString("finca_nombre", finca.optString("direccion", "N/A"));
                long fecha = rem.getLong("fecha");
                JSONArray items = rem.getJSONArray("items");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String prodId = item.getString("id").toLowerCase();
                    String prodNom = item.getString("nombre");
                    String cantRaw = item.getString("cantidad");
                    
                    // Format quantity as whole number
                    String cant = cantRaw;
                    try {
                        double v = Double.parseDouble(cantRaw.replace(",", "."));
                        cant = String.valueOf((int) v);
                    } catch (Exception e) { /* ignore */ }

                    boolean matches = false;
                    if (type == 0 && prodId.contains(finalQuery)) matches = true;
                    else if (type == 1 && consecutivo.toLowerCase().contains(finalQuery)) matches = true;
                    else if (type == 2 && fincaId.contains(finalQuery)) matches = true;

                    if (matches) {
                        agregarFila(consecutivo, fincaNom, prodNom, cant, fecha);
                        found = true;
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        if (!found) {
            tvwMensaje.setText("No se encuentran remisiones para el término ingresado.");
            tvwMensaje.setVisibility(View.VISIBLE);
        } else {
            tvwMensaje.setVisibility(View.GONE);
        }
    }

    private void mostrarTodo() {
        List<JSONObject> all = Methods.getAllRemisionesData(this);
        Collections.sort(all, (a, b) -> Long.compare(b.optLong("fecha"), a.optLong("fecha")));

        limpiarTabla();
        tvwMensaje.setVisibility(View.GONE);

        for (JSONObject rem : all) {
            try {
                String consecutivo = rem.getString("consecutivo");
                JSONObject finca = rem.getJSONObject("fincaData");
                String fincaNom = finca.optString("finca_nombre", finca.optString("direccion", "N/A"));
                long fecha = rem.getLong("fecha");
                JSONArray items = rem.getJSONArray("items");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String cantRaw = item.getString("cantidad");
                    String cant = cantRaw;
                    try {
                        double v = Double.parseDouble(cantRaw.replace(",", "."));
                        cant = String.valueOf((int) v);
                    } catch (Exception e) {}
                    
                    agregarFila(consecutivo, fincaNom, item.getString("nombre"), cant, fecha);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void agregarFila(String rem, String finca, String prod, String cant, long fecha) {
        TableRow row = new TableRow(this);
        row.setPadding(4, 4, 4, 4);

        row.addView(crearTextViewCelda(rem));
        row.addView(crearTextViewCelda(finca));
        row.addView(crearTextViewCelda(prod));
        row.addView(crearTextViewCelda(cant));
        row.addView(crearTextViewCelda(sdf.format(new Date(fecha))));

        tlyTabla.addView(row);
    }

    private TextView crearTextViewCelda(String texto) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setPadding(16, 16, 16, 16);
        return tv;
    }

    private void limpiarTabla() {
        int count = tlyTabla.getChildCount();
        if (count > 1) tlyTabla.removeViews(1, count - 1);
    }
}
