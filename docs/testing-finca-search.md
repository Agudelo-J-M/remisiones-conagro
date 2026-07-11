# Testing Protocol — Client search by Documento / Nombre / Finca (Ingresar Remisión)

> Covers the change from finca_id search to searching by **persona_doc (documento)** primarily, plus
> **persona_nombre** and **finca_nombre**, while the app resolves internally to the unique
> **finca_id**. Autocomplete suggestions display **`persona_nombre - finca_nombre`**.
>
> **Selection model (important):** typing only *filters the suggestion list* — it never selects a
> client. A client is committed **only by tapping a suggestion**. This prevents a typed prefix from
> locking onto an unintended finca (e.g. the real `9999999999` finca) and keeps typing fast (no
> per-keystroke table scan).
>
> **All the specific searches below assume the shipped `Datos App.xlsx` dataset.** If a different
> workbook is loaded, the IDs/names won't match — reimport the right file first.

Legend: type into the **Documento** field (top of Ingresar Remisión), watch the **label** next to
"Documento", the **dropdown** suggestions, and the field text.
Label states: **gray "Cliente sin registrar"** (empty) · **orange "Seleccione un cliente de la lista"**
(typing, nothing chosen) · **blue "persona_nombre - finca_nombre"** (a suggestion was tapped).

---

## 0. Preconditions
- [ ] Log in as a técnico whose consecutivo is already configured (if fresh install, seed it via **Cambiar Consecutivo** first).
- [ ] Open **Ingresar Remisión** — title **"Ingresar Remisión (Borrador)"**, field label **"Documento"**, hint **"Documento, nombre o finca"**, label **"Cliente sin registrar"** (gray).

## 1. Performance / no auto-lock while typing
- [ ] **1.1** Type `3469681` character by character → each keystroke is **snappy** (no 1–2 s freeze). Dropdown shows **"ROBERTO ARGIRO ROLDAN PEÑA - ASIENTO DE OSORIO"**.
- [ ] **1.2** Do **not** tap it. Label stays **orange "Seleccione un cliente de la lista"** — the app has **not** selected anything yet, even though only one suggestion shows.
- [ ] **1.3** Tap the suggestion → field collapses to `3469681`, label turns **blue "ROBERTO ARGIRO ROLDAN PEÑA - ASIENTO DE OSORIO"** (now selected, finca_id 451 internally).

## 2. The 9999999999 finca — prefix must NOT auto-lock
- [ ] **2.1** Clear the field, type `9` then `99`, up to `999999999` (nine 9s) → dropdown may show **"JUAN DAVID LOPERA - EL CORTIJO"** (its doc `9999999999` starts with those 9s). Label stays **orange** the whole time — no auto-lock.
- [ ] **2.2** Add an item and tap **Generar Remisión** without picking → **blocked**: *"Seleccione un cliente de la lista de sugerencias."*
- [ ] **2.3** Type the full `9999999999` and **tap** "JUAN DAVID LOPERA - EL CORTIJO" → selected (blue). Now Generar proceeds.

## 3. Shared document (one persona, several fincas)
- [ ] **3.1** Clear, type `727354` → dropdown shows **two** rows: **"RODRIGO ARANGO PEÑA - EL JARDIN"** and **"RODRIGO ARANGO PEÑA - LA BERRAQUERA"**. Label orange.
- [ ] **3.2** Tap **"... - LA BERRAQUERA"** → field shows `727354`, label blue **"RODRIGO ARANGO PEÑA - LA BERRAQUERA"** (finca_id 1591 internally, not 1592).
- [ ] **3.3** (repeat) `15272252` → **"DAVIER EDIRIO MEJIA MUÑOZ - LA MARGARITA"** / **"... - ROSARITO"**.

## 4. Search by FINCA name
- [ ] **4.1** Clear, type `LA BERRAQUERA` → single suggestion **"RODRIGO ARANGO PEÑA - LA BERRAQUERA"**; tap it → selected (blue). Resolves the right finca even though doc `727354` is shared.
- [ ] **4.2** Type `ASIENTO DE OSORIO` → single suggestion; tap → ROBERTO ARGIRO ROLDAN PEÑA selected.

## 5. Search by PERSONA name + accent-insensitivity
- [ ] **5.1** Type `ROBERTO ARGIRO` → single suggestion; tap → selected.
- [ ] **5.2 Accent:** type `pena` (no tilde) → suggestions include **"PEÑA"** names. Confirms accent/case-insensitive matching (and it's fast).
- [ ] **5.3 Accent:** type `londono` (no tilde) → suggestions include **"LONDOÑO"** names.

## 6. Editing after selecting drops the selection
- [ ] **6.1** Select `3469681` (ROBERTO, blue label).
- [ ] **6.2** Backspace one digit → `346968` → label immediately reverts to **orange "Seleccione un cliente de la lista"**; the selection is dropped (this is the previous stale-lock bug — must now clear).
- [ ] **6.3** Append back to `3469681` → still **orange** (typing doesn't re-select); tap the suggestion again to re-select.

## 7. Finalize resolution paths
- [ ] **7.1 Typed, not selected:** type `3469681` but **do not tap** the suggestion, add an item, **Generar** → **blocked**: *"Seleccione un cliente de la lista de sugerencias."*
- [ ] **7.2 No match typed:** type `123456789` (garbage), add item, **Generar** → **blocked** with the same message.
- [ ] **7.3 Selected:** type `3469681`, **tap** the suggestion, add item, **Generar** → **proceeds** with ROBERTO ARGIRO ROLDAN PEÑA.
- [ ] **7.4 Selected then edited:** select a client, then edit the field (drops selection), **Generar** → **blocked** (must re-select).

## 8. Anonymous / empty flow (unchanged)
- [ ] **8.1** Leave Documento **empty**, add an item, **Generar** → office reminder → **new-client form**, then firma flow.
- [ ] **8.2 Verify PDF:** header shows **`DOCUMENTO: 000`** and **`FINCA: CLIENTE POR DEFINIR`**; "NUEVO CLIENTE …" data in the Observaciones box.

## 9. In-memory draft
- [ ] **9.1** Select `3469681`, add 1–2 items → **Back** to Menu → re-open **Ingresar Remisión** → items restored **and** field shows `3469681` with blue label **"ROBERTO ARGIRO ROLDAN PEÑA - ASIENTO DE OSORIO"** (selection restored).
- [ ] **9.2** Complete a send (Sí) → re-open → draft cleared (empty field, gray "Cliente sin registrar").

## 10. PDF & email — DOCUMENTO shows the Datos App value (not typed text)
- [ ] **10.1** Full send for a client selected by **name** (e.g. type `LA BERRAQUERA`, tap it): add item → Generar → Observaciones → firmas → PDF.
- [ ] **10.2 Verify PDF header:** left line reads **`DOCUMENTO: 727354`** (the canonical persona_doc from Datos App — **not** the "LA BERRAQUERA" text that was typed), and **`FINCA: LA BERRAQUERA`** to its right. finca_id does not appear.
- [ ] **10.3** Email subject **"Remisión <consecutivo> - LA BERRAQUERA"**; confirm **Sí, enviado**.

## 11. Consecutivo smoke (unchanged, exercise end-to-end)
- [ ] **11.1** Create a second remisión for a different selected client → number is **sequential (+1)**.
- [ ] **11.2 No-burn:** open Ingresar, select a client, add items, Back without sending → re-open and send → number is the next expected (not skipped).

---

## Quick reference — verified test data (from `Datos App.xlsx`)
| Search string | Suggestions | Behavior |
|---|---|---|
| `3469681` | 1 (ROBERTO ARGIRO ROLDAN PEÑA — ASIENTO DE OSORIO, finca 451) | must tap to select |
| `9999999999` | 1 (JUAN DAVID LOPERA — EL CORTIJO, finca 2179) | prefix `9`…`999999999` does NOT auto-lock |
| `727354` | 2 (RODRIGO ARANGO PEÑA — EL JARDIN 1592 / LA BERRAQUERA 1591) | shared doc → pick the right finca |
| `15272252` | 2 (DAVIER EDIRIO MEJIA MUÑOZ — LA MARGARITA 2274 / ROSARITO 2273) | shared doc |
| `LA BERRAQUERA` | 1 (finca 1591) | select by finca name |
| `ASIENTO DE OSORIO` | 1 (finca 451) | select by finca name |
| `ROBERTO ARGIRO` | 1 (finca 451) | select by persona name |
| `pena` (no tilde) | many "PEÑA" | accent-insensitive |
| `londono` (no tilde) | many "LONDOÑO" | accent-insensitive |
| `123456789` | 0 | Generar blocked until a suggestion is selected |
