package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity to capture additional comments (Observaciones) for the remission document.
 * Reused by other flows (e.g. Cambiar Consecutivo) that need a free-text reason instead.
 *
 * Optional intent extras, all additive — a caller that passes none gets the original remisión
 * behavior unchanged:
 * <ul>
 *   <li>{@code titulo} — overrides the screen title.</li>
 *   <li>{@code subtitulo} — overrides the info text.</li>
 *   <li>{@code botonTexto} — overrides the button label. Needed because the default
 *       "Siguiente (Firmar)" is a lie in any flow whose next step is not a signature.</li>
 *   <li>{@code prellenado} — pre-fills the field, still editable.</li>
 *   <li>{@code obligatorio} — the field may not be left empty. Enforced HERE, on the screen
 *       that owns the input, so the caller never has to reject the text afterwards and send
 *       the técnico back through its own earlier steps.</li>
 * </ul>
 */
public class ObservacionesActivity extends AppCompatActivity {

    /**
     * Placeholder returned for an empty optional field. Callers that pass {@code obligatorio}
     * never receive it — this screen rejects it as input, so no caller has to re-validate it.
     */
    static final String SIN_OBSERVACIONES = "Sin observaciones";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_observaciones);

        Methods.updateActivity(this);

        TextView tvwTitulo = findViewById(R.id.tvw_Titulo_Observaciones);
        TextView tvwInfo = findViewById(R.id.tvw_Info_Observaciones);
        EditText edtObs = findViewById(R.id.edt_Observaciones);
        Button btnNext = findViewById(R.id.btn_Siguiente_Observaciones);

        String titulo = getIntent().getStringExtra("titulo");
        if (titulo != null && !titulo.isEmpty()) {
            tvwTitulo.setText(titulo);
        }

        String subtitulo = getIntent().getStringExtra("subtitulo");
        if (subtitulo != null && !subtitulo.isEmpty()) {
            tvwInfo.setText(subtitulo);
        }

        String botonTexto = getIntent().getStringExtra("botonTexto");
        if (botonTexto != null && !botonTexto.isEmpty()) {
            btnNext.setText(botonTexto);
        }

        boolean obligatorio = getIntent().getBooleanExtra("obligatorio", false);
        if (obligatorio) {
            // The layout's default hint ("Sin observaciones...") invites leaving the field
            // empty, which is exactly what a mandatory field must not suggest.
            edtObs.setHint("Escriba aquí...");
        }

        String prellenado = getIntent().getStringExtra("prellenado");
        if (prellenado != null && !prellenado.isEmpty()) {
            edtObs.setText(prellenado);
            // Read the length back from the field, not from prellenado: the layout caps this
            // EditText at 200 chars, so a longer value is silently truncated by setText and
            // seeking to prellenado.length() would land past the end and throw.
            edtObs.setSelection(edtObs.getText().length());
        }

        btnNext.setOnClickListener(v -> {
            String obs = edtObs.getText().toString().trim();

            // Both rejections stay on this screen, so the técnico keeps what they typed and the
            // caller's earlier steps are never discarded. Rejecting the PLACEHOLDER here too is
            // what keeps callers from having to reject it afterwards: a caller that bounced the
            // técnico back would cost them every step it had already collected.
            if (obligatorio && obs.isEmpty()) {
                Methods.showAlert(this, "Este campo es obligatorio, por favor complételo.").show();
                return;
            }
            if (obligatorio && SIN_OBSERVACIONES.equalsIgnoreCase(obs)) {
                Methods.showAlert(this, "Escriba un motivo real, no \"" + SIN_OBSERVACIONES + "\".").show();
                return;
            }

            if (obs.isEmpty()) obs = SIN_OBSERVACIONES;

            Intent result = new Intent();
            result.putExtra("observaciones", obs);
            setResult(RESULT_OK, result);
            finish();
        });
    }
}
