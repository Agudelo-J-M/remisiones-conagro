# Feature Plan — Consecutivo de Remisión (Sequential Referral Numbering)

> **Status:** Approved design, not yet implemented.
> **Author of design:** planning session, 2026-07-08.
> **Scope:** Read this before touching `Methods.generarConsecutivo`, `IngresarRemisionActivity`,
> `CambiarConsecutivoActivity`, `PedirFirmaActivity`, or `MenuActivity`.

---

## 1. Problem

The client needs each técnico's remisiones to be delivered in a clean, gap-free sequence
(`…001, …002, …003`) so a **missing remisión is immediately visible**. The current
`generarConsecutivo` uses `tecnicoId + minutesSinceCustomEpoch`, which is unique but **not
sequential** and cannot be audited for gaps.

The hard constraint: the app is **offline-first** and used by **many técnicos**. A naive local
counter breaks on reinstall / data loss (resets to 1 → duplicates) and there is **no server** to
be the source of truth.

## 2. Confirmed constraints & decisions

| Decision | Value |
|---|---|
| Device model | **One device per técnico** |
| Connectivity | **Occasional internet** — completed remisiones are emailed as PDFs → the office mailbox is the durable, ordered record |
| Re-seed after data loss | **Técnico self-service**, gated (see §7) |
| Numbering scope | **Continuous forever** per técnico, never resets |
| ID format | **`<tecnicoId><6-digit sequence>`, no separator** — e.g. `03000001` |
| Number assignment timing | **At commit** (on successful save/send), never at screen open |
| Fresh-install rule | **No remisiones may be created until the consecutivo has been seeded** |

## 3. ID format spec

```
consecutivo = <tecnicoId, min 2 digits, zero-padded><sequence, exactly 6 digits, zero-padded>
```

- Técnico 3, sequence 1  → `03000001`
- Técnico 3, sequence 45 → `03000045`
- Técnico 105, sequence 45 → `105000045` (prefix widens if técnico number > 99)

**Parsing rule (robust):** the **last 6 characters are the sequence**; everything before is the
técnico id. Keep a single `String consecutivo` field everywhere (matches current design: filenames,
JSON keys, PDF header, intent extras).

`String formatConsecutivo(String tecnicoId, long seq)` → `String.format("%02d%06d", parseInt(tecnicoId), seq)`.

Legacy remisiones created with the old `tecnicoId+minutes` scheme are left as-is; the reconciliation
parser (§6) ignores any id that doesn't parse as the new fixed format.

## 4. Lifecycle — assign at commit, not at screen open  ← most important change

Today `IngresarRemisionActivity:103` burns a number the moment the screen opens. For a gap-detection
feature that is a bug (back out → phantom gap). New flow:

1. **Screen open:** title shows `Nueva Remisión (borrador)` — **no number yet**.
2. **During editing:** signatures are saved under a **fixed draft key**
   (`firma_tecnico_DRAFT.png` / `firma_cliente_DRAFT.png`), NOT keyed by `consecutivo`.
   Safe because it is one active remisión per técnico at a time.
3. **At final commit** (right before `generarPDFRemision` + `saveRemisionData` + email, around
   `IngresarRemisionActivity:480–523`):
   - `consecutivo = peekProximoConsecutivo(tecnicoId)`
   - generate PDF with in-memory bitmaps (`generarPDFRemision` already takes `Bitmap`s),
     writing final `Remision_<consecutivo>.pdf` and renaming/writing the draft signatures.
   - **only on a successful save/send:** `commitConsecutivo(tecnicoId)` (increment + persist).
4. **Editing** (`EditarRemisionActivity`) reuses the existing `consecutivo` from the intent and
   **never** increments.

Result: abandoned drafts cost nothing; every consumed number = one real, sent remisión.

## 5. Storage & counter helpers (in `Methods`)

Reuse the existing `app_config.json` pattern (`getLastInventoryFileName` / `setLastInventoryFileName`).

```json
{ "consecutivos": { "03": 44, "07": 5 } }
```

Value = **last used sequence** (so next = value + 1). Keyed by técnico **CEDULA** (more stable than
NUMERO if técnico numbers are ever reassigned) — but the *displayed prefix* still uses NUMERO.

New `synchronized` helpers:

| Method | Behavior |
|---|---|
| `boolean isConsecutivoInicializado(ctx, tecnicoCedula)` | true if a counter exists for this técnico. Drives the fresh-install gate (§8). |
| `String peekProximoConsecutivo(ctx, tecnicoNumero, tecnicoCedula)` | returns the next formatted id **without** incrementing; returns `null` if uninitialized. |
| `void commitConsecutivo(ctx, tecnicoCedula)` | increment + persist. Called only after a successful save. |
| `void setConsecutivoSeed(ctx, tecnicoCedula, long ultimaEnviada)` | sets stored value = `ultimaEnviada`. Called **only** after email-sent confirmation (§7). |
| `long reconcileConsecutivo(ctx, tecnicoNumero, tecnicoCedula)` | returns `max(stored, highest local Remisión suffix for this técnico)`. |
| `String formatConsecutivo(tecnicoNumero, long seq)` | `%02d%06d`. |

## 6. Automatic recovery — self-healing reconciliation

Before assigning a number, and on `commit`, compute the effective floor from the files already on
the device (enumerated the same way as `getListaRemisiones`):

```
effectiveNext = max(storedCounter, highestLocalSuffixForThisTecnico) + 1
```

This auto-corrects a corrupted/rolled-back config **as long as the Remisión JSONs survived**, and it
guarantees a new number can never collide with a file already on the device. On a truly fresh install
there are no local files, so the counter is genuinely unset → the fresh-install gate (§8) forces a seed.

## 7. The re-seed / correction flow — `CambiarConsecutivoActivity`

Menu option **"Cambiar Consecutivo"** (add button to `MenuActivity`; the activity + layout already
exist but are disabled). This is the **single choke point** for a wrong number entering the system,
so all friction lives here. Steps, in order — **the counter is changed only if every step succeeds**:

**Step 1 — Credential gate.**
Prompt for the técnico's **cédula**; compare to `userData.get("cedula")`. Mismatch → deny, abort.

**Step 2 — Double entry.**
Two fields with the instruction:
> *"Escriba el número de la última remisión enviada desde su correo."*
Both entries must match each other and parse as a valid id. From `03000044` the system reads
sequence `44` → next will be `03000045`.
- Validate that the entered prefix matches **this** técnico's id (block seeding another técnico's series).
- Provide **"Nunca he enviado una remisión"** → seeds `ultimaEnviada = 0` → next = `…000001`
  (covers a brand-new técnico on first install).

**Step 3 — Reason (reuse `ObservacionesActivity`).**
Launch `ObservacionesActivity` with a flow flag (e.g. `flowType = "cambio_consecutivo"`). On launch,
show an alert:
> *"Este cambio será notificado a oficina por correo antes de aplicarse."*
A non-empty reason is required.

**Step 4 — Email to oficina.**
Build an `ACTION_SEND` email to the office address. Suggested content:
- Subject: `Cambio de Consecutivo - Técnico <numero> (<nombre>)`
- Body: técnico nombre + cédula, old next, entered "última enviada", resulting new next, the reason,
  and a timestamp.
Launch the chooser.

**Step 5 — Confirm the mail was sent.**
> ⚠️ Android email intents do **not** reliably report whether the message was actually sent
> (most email apps return `RESULT_CANCELED` regardless). So after returning, show an explicit dialog:
> *"¿Confirmó el envío del correo a oficina?"* — **Sí / No**.

**Step 6 — Apply.**
Only on **"Sí"**: call `setConsecutivoSeed(...)`, then re-apply reconciliation (§6) so the effective
next can never drop below a surviving local file, and append the change to a local
`consecutivo_log.json` (old → new, cédula, reason, timestamp) for traceability. Show success.
On **"No"** or any earlier cancel: **no change is made.**

### Safety notes folded into this flow
- The **email to oficina is the audit trail** — the office sees every counter change, so misuse is
  detectable rather than silent. This is why occasional connectivity matters.
- **Collision hard-block:** if the resulting series would regenerate an id for which a local
  `Remision_<id>.json` already exists, refuse (this is what the §6 reconciliation enforces).
- Moving the counter **forward** only ever creates a visible gap (recoverable). Moving it **backward**
  is the only destructive move; the credential gate + double entry + reason + office email + explicit
  confirmation are the friction proportional to that risk.

## 8. Fresh-install gate

On a fresh install `isConsecutivoInicializado` is false. **Block remisión creation** until seeded:
- Enforce at `IngresarRemisionActivity` entry (authoritative): if not initialized, show
  *"Debe configurar el consecutivo antes de crear remisiones."* and route to `CambiarConsecutivoActivity`.
- Optionally pre-check on the `btn_IngresarRemision` click in `MenuActivity` for a smoother UX.

## 9. Duplicate safety net (belt-and-suspenders)

At commit, if `Remision_<candidateId>.json` already exists locally → bump and retry. Keep a hidden
`internalId`/timestamp inside each remisión JSON so the office can disambiguate in the rare event two
ids ever coincide across a reinstall.

## 10. Optional — gap-detection report (the client's actual goal)

The numbering is the enabler; what the client asked to *do* is spot missing remisiones. Add a view
(fits `ReportesActivity` or `ModificarRemisionActivity`) that lists a técnico's `consecutivos` sorted,
highlighting gaps (e.g. *"03000007 faltante"*). Recommended as a follow-up once numbering lands.

## 11. Change map

| File | Change |
|---|---|
| `Methods.java` | Remove time-based `generarConsecutivo`; add `isConsecutivoInicializado`, `peekProximoConsecutivo`, `commitConsecutivo`, `setConsecutivoSeed`, `reconcileConsecutivo`, `formatConsecutivo`; store counters in `app_config.json`. |
| `IngresarRemisionActivity` | Remove early assignment (~103); assign at commit (~480–523); increment only on success; `(borrador)` title until then; fresh-install gate. |
| `PedirFirmaActivity` | Draft signatures keyed to `DRAFT`, not `consecutivo`. |
| `EditarRemisionActivity` | Confirm it reuses the number and never increments. |
| `CambiarConsecutivoActivity` | Re-enable as the gated re-seed flow (§7). |
| `ObservacionesActivity` | Support `flowType = "cambio_consecutivo"` + launch alert. |
| `MenuActivity` | Add "Cambiar Consecutivo" button. |
| Email subject (remisión send) | Standardize to include `consecutivo` for durable recovery. |
| (follow-up) `ReportesActivity` | Gap-detection report. |

## 12. Open / deferred items

- **Office email address** source — hardcoded constant vs. a field in `Datos App.xlsx` / `app_config.json`? (Needed for Step 4.)
- **6 digits** (max 999,999 per técnico) confirmed sufficient — yes for this domain.
- **Counter key**: plan uses CEDULA for storage stability; confirm técnico cédula is always present in `userData`.
- Legacy remisiones migration: leave old-format ids untouched; técnico seeds a starting number once.
