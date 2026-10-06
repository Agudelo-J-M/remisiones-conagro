package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.Serializable;
import java.util.HashMap;

/**
 * Lets a técnico (re)seed their consecutivo counter after a reinstall / data loss.
 * Guarded flow: cédula re-auth -> double entry (or "never sent") -> reason (Observaciones)
 * -> email to oficina -> confirm sent -> apply. See docs/feature-consecutivo-remision.md.
 */
public class CambiarConsecutivoActivity extends AppCompatActivity {

    private HashMap<String, String> userData;
    private String tecnicoNumero = "";
    private TextView tvwDisplayActual;
    private EditText edtNuevo, edtConfirmar;
    private CheckBox chkNuncaEnviado;

    // Carried across the async reason/email steps
    private int pendingSeedSeq = -1;
    private String pendingReason = "";

    private final ActivityResultLauncher<Intent> observacionesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    onReasonEntered(result.getData()
                            .getStringExtra(ObservacionesActivity.RESULT_OBSERVACIONES));
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_cambiar_consecutivo);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);

        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (!(serializable instanceof HashMap)) {
            serializable = getIntent().getSerializableExtra("hashMapKey");
        }
        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }
        if (userData == null) {
            Methods.showFatalError(this, "Error de sesión. Contacte a un administrador.");
            return;
        }

        tecnicoNumero = userData.getOrDefault("numero", "");
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) {
            Methods.showFatalError(this, "El técnico no tiene un número asignado en 'Datos App.xlsx'. "
                    + "Notifique a la oficina. La aplicación se cerrará.");
            return;
        }

        setupUI();
    }

    private void setupUI() {
        tvwDisplayActual = findViewById(R.id.tvw_ConsecutivoActualDisplay);
        edtNuevo = findViewById(R.id.edt_IngresarConsecutivoNuevo);
        edtConfirmar = findViewById(R.id.edt_ConfirmarConsecutivo);
        chkNuncaEnviado = findViewById(R.id.chk_NuncaEnviado);
        Button btnCambiar = findViewById(R.id.btn_Cambiar);
        Button btnSalir = findViewById(R.id.btn_salirCambiarConsecutivo);

        refreshDisplay();

        chkNuncaEnviado.setOnCheckedChangeListener((b, checked) -> {
            edtNuevo.setEnabled(!checked);
            edtConfirmar.setEnabled(!checked);
        });

        btnCambiar.setOnClickListener(v -> promptCedula());
        btnSalir.setOnClickListener(v -> finish());
    }

    private void refreshDisplay() {
        boolean yaConfigurado = Methods.isConsecutivoInicializado(this, tecnicoNumero);
        if (yaConfigurado) {
            tvwDisplayActual.setText(Methods.peekProximoConsecutivo(this, tecnicoNumero));
        } else {
            tvwDisplayActual.setText("No configurado");
        }

        // "Nunca he enviado una remisión" only makes sense before this técnico has a consecutivo
        // at all; once one exists, checking it would contradict the app's own records. Disabled
        // rather than hidden: a vanished control reads as a bug, and the técnico who truly never
        // sent one still needs to see that the option exists and why it is closed to them.
        chkNuncaEnviado.setEnabled(!yaConfigurado);
        if (yaConfigurado) {
            chkNuncaEnviado.setChecked(false);
            chkNuncaEnviado.setText("Nunca he enviado una remisión (ya tiene consecutivo configurado)");
        }
    }

    /** Step 1: cédula re-authentication. */
    private void promptCedula() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Cédula");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Verificación")
                .setMessage("Ingrese su cédula para confirmar el cambio.")
                .setView(input)
                .setPositiveButton("Continuar", (d, w) -> {
                    String entered = input.getText().toString().trim();
                    String real = userData.getOrDefault("cedula", "");
                    if (!entered.isEmpty() && entered.equals(real)) {
                        validateEntry();
                    } else {
                        Methods.showAlert(this, "Cédula incorrecta. El cambio fue cancelado.").show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Step 2: validate the double entry (or the "never sent" checkbox). */
    private void validateEntry() {
        int lastSeq;
        boolean nuncaEnviado = chkNuncaEnviado.isChecked();

        if (nuncaEnviado) {
            lastSeq = 0; // next will be ...000001
        } else {
            String s1 = edtNuevo.getText().toString().trim();
            String s2 = edtConfirmar.getText().toString().trim();

            if (s1.isEmpty() || s2.isEmpty()) {
                Methods.showAlert(this, "Escriba el número en ambos campos.").show();
                return;
            }
            if (!s1.equals(s2)) {
                Methods.showAlert(this, "Los números no coinciden. Vuelva a intentarlo.").show();
                return;
            }
            if (!s1.matches("\\d+") || s1.length() < 7) {
                Methods.showAlert(this, "Número inválido. Escriba el consecutivo completo (ej. 03000044).").show();
                return;
            }

            // Split into prefix + 6-digit sequence and check the prefix belongs to this técnico
            String suffix = s1.substring(s1.length() - 6);
            String prefix = s1.substring(0, s1.length() - 6);
            String fullZero = Methods.formatConsecutivo(tecnicoNumero, 0);
            String expectedPrefix = fullZero.substring(0, fullZero.length() - 6);
            if (!prefix.equals(expectedPrefix)) {
                Methods.showAlert(this, "El número no corresponde a su usuario (prefijo " + expectedPrefix + ").").show();
                return;
            }
            try {
                lastSeq = Integer.parseInt(suffix);
            } catch (Exception e) {
                Methods.showAlert(this, "Número inválido.").show();
                return;
            }
        }

        // Guard: going below the known floor risks duplicates -> require explicit confirmation
        int floor = Math.max(Methods.getStoredConsecutivo(this, tecnicoNumero),
                Methods.reconcileConsecutivo(this, tecnicoNumero));
        final int seedSeq = lastSeq;
        if (lastSeq < floor) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Advertencia")
                    .setMessage("El número ingresado es menor al último registrado (" + floor + "). "
                            + "Esto puede crear remisiones duplicadas que la oficina no podrá diferenciar. ¿Desea continuar?")
                    .setPositiveButton("Sí, continuar", (d, w) -> proceedToReason(seedSeq, nuncaEnviado))
                    .setNegativeButton("No", null)
                    .show();
        } else {
            proceedToReason(seedSeq, nuncaEnviado);
        }
    }

    /** Step 3: reason via Observaciones, after warning that the office will be notified. */
    private void proceedToReason(int seedSeq, boolean nuncaEnviado) {
        pendingSeedSeq = seedSeq;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Aviso")
                .setMessage("Este cambio será notificado a la oficina por correo antes de aplicarse.")
                .setPositiveButton("Entendido", (d, w) ->
                        launchReasonScreen(nuncaEnviado ? "primer vez" : null))
                .setCancelable(false)
                .show();
    }

    /**
     * Opens the shared Observaciones screen as a reason prompt. It enforces the mandatory field
     * itself, and its title/button are overridden because the next step in THIS flow is an
     * email to the oficina, not a signature.
     */
    private void launchReasonScreen(String prellenado) {
        Intent intent = new Intent(this, ObservacionesActivity.class);
        intent.putExtra(ObservacionesActivity.EXTRA_TITULO, "Motivo del cambio");
        intent.putExtra(ObservacionesActivity.EXTRA_SUBTITULO,
                "Indique el motivo del cambio de consecutivo (obligatorio):");
        intent.putExtra(ObservacionesActivity.EXTRA_BOTON_TEXTO, "Siguiente (Notificar)");
        intent.putExtra(ObservacionesActivity.EXTRA_OBLIGATORIO, true);
        if (prellenado != null) intent.putExtra(ObservacionesActivity.EXTRA_PRELLENADO, prellenado);
        observacionesLauncher.launch(intent);
    }

    private void onReasonEntered(String reason) {
        // Unreachable backstop. We pass "obligatorio", so ObservacionesActivity rejects both an
        // empty field and the "Sin observaciones" placeholder on its own screen, with the text
        // preserved. Rejecting a reason HERE would send the técnico back to step 1 — cédula
        // re-auth, double entry and the below-floor warning all over again — so this must never
        // become the normal way an invalid reason is handled.
        if (reason == null || reason.trim().isEmpty()
                || ObservacionesActivity.SIN_OBSERVACIONES.equalsIgnoreCase(reason.trim())) {
            Methods.showAlert(this, "Debe ingresar un motivo para el cambio.").show();
            return;
        }
        pendingReason = reason;
        sendEmailToOffice();
    }

    /** Step 4: email the office, then apply only if the técnico confirms the send. */
    private void sendEmailToOffice() {
        String nombre = userData.getOrDefault("nombre", "");
        String cedula = userData.getOrDefault("cedula", "");
        String ultima = (pendingSeedSeq <= 0) ? "Ninguna" : Methods.formatConsecutivo(tecnicoNumero, pendingSeedSeq);
        String proximo = Methods.formatConsecutivo(tecnicoNumero, pendingSeedSeq + 1);

        String body = "Cambio de consecutivo solicitado.\n\n"
                + "Técnico: " + nombre + "\n"
                + "Cédula: " + cedula + "\n"
                + "Número: " + tecnicoNumero + "\n"
                + "Última remisión enviada indicada: " + ultima + "\n"
                + "Próximo consecutivo será: " + proximo + "\n"
                + "Motivo: " + pendingReason;

        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("text/plain");
        // Office address from the Tecnicos sheet ("CORREO OFICINA"); left empty for the técnico
        // to type when the server has no value for them.
        emailIntent.putExtra(Intent.EXTRA_EMAIL,
                Methods.buildEmailRecipients(Methods.getCorreoOficina(userData)));
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Cambio de Consecutivo - Técnico " + tecnicoNumero + " (" + nombre + ")");
        emailIntent.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(emailIntent, "Notificar a oficina..."));

        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmación")
                .setMessage("¿Confirmó el envío del correo a la oficina? El cambio se aplicará solo si confirma.")
                .setPositiveButton("Sí, enviado", (d, w) -> applyChange())
                .setNegativeButton("No", (d, w) ->
                        Methods.showAlert(this, "Cambio cancelado. No se realizó ningún cambio.").show())
                .setCancelable(false)
                .show();
    }

    private void applyChange() {
        Methods.setStoredConsecutivo(this, tecnicoNumero, pendingSeedSeq);
        refreshDisplay();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Éxito")
                .setMessage("Consecutivo actualizado. El próximo será "
                        + Methods.formatConsecutivo(tecnicoNumero, pendingSeedSeq + 1) + ".")
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }
}
