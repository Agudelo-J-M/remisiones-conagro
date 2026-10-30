package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.graphics.Color;
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

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModificarRemisionActivity extends AppCompatActivity {

    private TableLayout tlyTabla;
    private HashMap<String, String> userData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_modificar_remision);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);
        
        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        tlyTabla = findViewById(R.id.tly_TablaDatos_Modificar_Remision);
        cargarRemisiones();
    }

    private void cargarRemisiones() {
        List<Map<String, String>> remisiones = Methods.getListaRemisiones(this);
        
        // Sort by date descending (most recent first)
        java.util.Collections.sort(remisiones, (a, b) -> {
            long dateA = Long.parseLong(a.getOrDefault("fecha", "0"));
            long dateB = Long.parseLong(b.getOrDefault("fecha", "0"));
            return Long.compare(dateB, dateA);
        });

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        
        for (Map<String, String> rem : remisiones) {
            TableRow row = new TableRow(this);
            row.setPadding(0, 15, 0, 15); // More vertical breathing room
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            String consecutivo = rem.get("consecutivo");

            row.addView(crearTextViewCelda(Methods.visibleDeConsecutivo(consecutivo), 1.2f)); 
            row.addView(crearTextViewCelda(rem.get("finca_nombre"), 1.6f));
            
            String fechaStr = rem.get("fecha");
            String formatted = "";
            if (fechaStr != null) {
                try {
                    formatted = sdf.format(new Date(Long.parseLong(fechaStr)));
                } catch (Exception e) { formatted = fechaStr; }
            }
            row.addView(crearTextViewCelda(formatted, 1.2f));
            
            // Ultra-compact "Modificar" Button
            Button btnMod = new Button(this, null, androidx.appcompat.R.attr.buttonStyleSmall);
            btnMod.setText("Modificar");
            btnMod.setPadding(10, 0, 10, 0); // Tight internal padding
            btnMod.setMinWidth(0);
            btnMod.setMinimumWidth(0);
            btnMod.setAllCaps(false); // More compact text
            TableRow.LayoutParams lp = new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 0);
            btnMod.setLayoutParams(lp);
            
            btnMod.setOnClickListener(v -> {
                Intent intent = new Intent(this, EditarRemisionActivity.class);
                intent.putExtra("userData", userData);
                intent.putExtra("consecutivo", consecutivo);
                startActivity(intent);
            });
            row.addView(btnMod);

            tlyTabla.addView(row);
        }
    }

    private TextView crearTextViewCelda(String texto, float weight) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight));
        tv.setPadding(10, 10, 10, 10);
        tv.setSingleLine(false); // Allow multiple lines
        tv.setMaxLines(4);      // Allow up to 4 rows for long finca names
        return tv;
    }
}
