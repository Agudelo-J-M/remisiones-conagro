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
        boolean yaConfigurado = Methods.isConsecutivoInicializado(this, userData);
        if (yaConfigurado) {
            tvwDisplayActual.setText(Methods.visibleDeConsecutivo(Methods.peekProximoConsecutivo(this, userData)));
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
            // The screen now displays the office's format ("3-000044"), so the técnico will copy
            // that shape back in. Both are accepted: the hyphen is dropped and the prefix is
            // compared numerically, so "3-000044", "03000044" and "3000044" all mean the same.
            String norm = s1.replace("-", "").trim();
            if (!norm.matches("\\d+") || norm.length() < 7) {
                Methods.showAlert(this, "Número inválido. Escriba el consecutivo completo (ej. 3-000044).").show();
                return;
            }

            // Split into prefix + 6-digit sequence and check the prefix belongs to this técnico
            String suffix = norm.substring(norm.length() - 6);
            String prefix = norm.substring(0, norm.length() - 6);
            try {
                if (Integer.parseInt(prefix) != Integer.parseInt(tecnicoNumero.trim())) {
                    Methods.showAlert(this, "El número no corresponde a su usuario (debe empezar por "
                            + tecnicoNumero.trim() + ").").show();
                    return;
                }
            } catch (Exception e) {
                Methods.showAlert(this, "Número inválido.").show();
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
        // The shared §4 floor, so the warning accounts for the server's value too — a hand re-seed
        // below what the office already holds is exactly the case that creates duplicates.
        int floor = Methods.getConsecutivoFloor(this, userData);
        final int seedSeq = lastSeq;
        if (lastSeq < floor) {
            // REFUSED, not warned. This used to offer "Sí, continuar" — which could not be
            // honoured: the floor is max(local, archivos, servidor) and nothing in R8 may lower a
            // counter, so the very next peek raised the counter straight back and the técnico was
            // told their number had been applied when it had not. Worse, if the server held the
            // higher value the phone would then re-issue that whole range and get yanked forward
            // by a 409 mid-remisión. A choice the app cannot deliver must not be offered.
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Número no permitido")
                    .setMessage("Esta aplicación ya tiene registrado el consecutivo "
                            + Methods.formatConsecutivoVisible(tecnicoNumero, floor)
                            + ", que es mayor al que usted ingresó. No es posible reiniciar la "
                            + "numeración por debajo de un número ya usado, porque crearía "
                            + "remisiones duplicadas que la oficina no podría diferenciar.\n\n"
                            + "Si la oficina necesita un número menor, debe solicitarlo a la "
                            + "oficina para que lo corrijan en el servidor.")
                    .setPositiveButton("Entendido", null)
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
        // The office's format, like every other human-facing consecutivo. This mail exists purely
        // to tell them which number to expect, so it must not speak the internal id.
        String ultima = (pendingSeedSeq <= 0) ? "Ninguna"
                : Methods.formatConsecutivoVisible(tecnicoNumero, pendingSeedSeq);
        String proximo = Methods.formatConsecutivoVisible(tecnicoNumero, pendingSeedSeq + 1);

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

        // Claim the hand-typed seed, so a re-seed is audited server-side (anterior -> nuevo, with
        // this device's id) instead of being invisible to the office. The email above stays the
        // human record; this is the machine one. validateEntry refuses anything below the floor,
        // so the seed is always >= it and this always has something new to tell the server —
        // unless the seed equals the confirmed value, where there is nothing to say.
        InventarioSync.pushConsecutivoIfPending(this, tecnicoNumero,
                userData.getOrDefault("cedula", ""));

        refreshDisplay();

        // The floor can legitimately override a below-floor re-seed: R8's rule is that no
        // operation ever lowers a counter, and the server's value is one of the floor's inputs.
        // So report what will ACTUALLY be used. Announcing the typed number while storage already
        // holds something higher is how this dialog used to lie to the técnico.
        String pedido = Methods.formatConsecutivoVisible(tecnicoNumero, pendingSeedSeq + 1);
        String efectivoInterno = Methods.peekProximoConsecutivo(this, userData);
        String esperadoInterno = Methods.formatConsecutivo(tecnicoNumero, pendingSeedSeq + 1);

        String mensaje;
        if (efectivoInterno == null || efectivoInterno.equals(esperadoInterno)) {
            mensaje = "Consecutivo actualizado. El próximo será " + pedido + ".";
        } else {
            mensaje = "Consecutivo guardado, pero la oficina ya tiene registrado un número mayor, "
                    + "así que el próximo será " + Methods.visibleDeConsecutivo(efectivoInterno)
                    + " y no " + pedido + ". Nunca se reutiliza un número ya usado.";
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Éxito")
                .setMessage(mensaje)
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }
}
