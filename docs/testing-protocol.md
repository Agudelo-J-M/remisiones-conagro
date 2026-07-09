# Manual Testing Protocol — Remisiones Conagro

> Run before prod. Covers the whole app, with **[NEW]** marking behavior changed in the recent
> batch (F4 PDF observaciones, F5 inventory comparison, F6 client flow, the consecutivo feature,
> and the inventory-accept atomicity fix). Tick each box; note anything that deviates.
>
> Legend: **[NEW]** = recently changed, test carefully · **[REG]** = regression (should still work).

---

## 0. Test data to prepare

- [ ] **`Datos App.xlsx`** with the 4 sheets (`Tecnicos`, `Productos`, `Clientes`, `Tarifas Mantenimiento`) and their required columns.
  - In `Tecnicos`: at least **two normal técnicos** (with `NUMERO`, `CEDULA`, `CORREO`, names), and **one técnico with an empty `NUMERO`** (for the fatal-error test).
  - In `Clientes`: a few fincas, including at least one **with** `persona_email` and one **without**.
- [ ] **First INV CSV** (`INV*.csv`) — a small valid inventory (headers `cod_suc,cod_bod,ano,referencia,nombre,saldo`), several products.
- [ ] **Second INV CSV** — a modified copy of the first that: **adds** a new product, **removes** one that existed, **changes** a quantity, and **changes one quantity by a decimal** (e.g. 10 → 10.5).
- [ ] A device/emulator email app configured to actually send (for the "Sí, enviado" confirmations).

> Tip: to reach a truly fresh state, uninstall/reinstall (clears internal storage: counters, drafts, inventories, remisiones).

---

## 1. Data import & startup

- [ ] **1.1 [REG]** Fresh install, open app with no data → fatal/error prompt telling you to open the file from email. App does not crash.
- [ ] **1.2 [REG]** Open `Datos App.xlsx` from email with the app → "Excel … actualizado con éxito", data loads without format errors.
- [ ] **1.3 [REG]** Open the **first INV CSV** from email → accepted as inventory (saved as pending temp).
- [ ] **1.4** Malformed file (missing a sheet/column) → clear error message, app closes gracefully (no silent success).

## 2. Login

- [ ] **2.1 [REG]** Correct correo + cédula → Menu, welcome shows the técnico name.
- [ ] **2.2 [REG]** Wrong credentials → "Credenciales incorrectas".
- [ ] **2.3 [REG]** Empty fields → "no puede haber campos vacíos".

## 3. Menu

- [ ] **3.1** All buttons visible **except "Inventario Carro"** — **[NEW]** it should be hidden.
- [ ] **3.2** "Cambiar Consecutivo" button is present — **[NEW]**.
- [ ] **3.3 [REG]** Each visible button opens its screen; "Cerrar Sesión" returns to login.

## 4. Consecutivo — fresh-install gate & seed screen **[NEW]**

- [ ] **4.1 Gate:** On a fresh install (no counter yet), tap **Ingresar Remisión** → blocked with *"Debe configurar el número de consecutivo antes de crear remisiones"* → **Configurar** opens Cambiar Consecutivo.
- [ ] **4.2 Cédula gate:** In Cambiar Consecutivo, tap **Cambiar** → enter a **wrong** cédula → *"Cédula incorrecta"*, no change.
- [ ] **4.3 Double-entry mismatch:** Correct cédula → type `03000044` and `03000045` (different) → *"Los números no coinciden"*.
- [ ] **4.4 Wrong prefix:** Enter a number whose prefix isn't this técnico's (e.g. `99000044`) twice → rejected with the prefix message.
- [ ] **4.5 Invalid format:** Enter `44` (too short) twice → *"Número inválido … consecutivo completo"*.
- [ ] **4.6 Happy path:** Enter `03000044` twice → "notificará a la oficina" notice → Observaciones screen → type a motivo → email opens (you fill the oficina address in the mail app) → back → **"¿Confirmó el envío?"** → **Sí** → success; the displayed "próximo" becomes `03000045`.
- [ ] **4.7 Empty reason:** Repeat 4.6 but leave the motivo blank on Observaciones → *"Debe ingresar un motivo"*, no change applied.
- [ ] **4.8 Confirm = No:** Repeat to the confirm step and tap **No** → *"Cambio cancelado"*, próximo stays unchanged.
- [ ] **4.9 "Nunca he enviado":** Check the box → fields disable → complete flow with confirm Sí → próximo becomes `NN000001`.
- [ ] **4.10 Backward guard:** With a counter already at e.g. 44, try to seed a lower number (e.g. `03000010`) → duplicate-risk warning; **No** aborts, **Sí, continuar** proceeds.
- [ ] **4.11 Missing número (fatal):** Log in as the técnico with empty `NUMERO`, go to Cambiar Consecutivo (or Ingresar) → fatal alert *"El técnico no tiene un número asignado en 'Datos App.xlsx'. Notifique a la oficina"* → app closes on dismiss.

## 5. Ingresar Remisión — existing client (happy path) **[REG]**

- [ ] **5.1** After seeding, open Ingresar → title reads **"Ingresar Remisión (Borrador)"** — **[NEW]** no number shown yet.
- [ ] **5.2** Type a valid finca ID → the finca name appears (blue).
- [ ] **5.3** Add a product (autocomplete), qty 2 → row appears in the table (table renders — **[NEW]** was the blank-table area).
- [ ] **5.4** Add a maintenance item → appears.
- [ ] **5.5** Add the same product again → quantity sums (not duplicated).
- [ ] **5.6** Delete a row with the X → removed.
- [ ] **5.7** Finalizar → (email present → straight to reminder; email missing → "Email Faltante" first) → office reminder → **Observaciones** screen — **[NEW]** type observaciones → firma técnico → firma cliente.
- [ ] **5.8** PDF generates; **"¿Se envió el correo?"** → **Sí** → data saved, screen closes.
- [ ] **5.9 Verify PDF:** número `03000045` (the peeked value), correct finca block, items, totals, both signatures, **Observaciones box shows your text** — **[NEW: F4]**.
- [ ] **5.10 Verify stock:** the product quantities were **deducted** from `Inventario.xlsx`.

## 6. Consecutivo behavior — sequential & no-burn **[NEW]**

- [ ] **6.1** Immediately create a second remisión → its number is **`03000046`** (sequential, +1).
- [ ] **6.2 No-burn on abandon:** Open Ingresar, add items, press Back (to Menu) without sending → re-open Ingresar and send → number is the **next expected**, not skipped.
- [ ] **6.3 No-burn on "No":** On the send confirm dialog tap **No** → signatures cleared, items still present → re-sign & send → completes; the following remisión is still sequential (the "No" attempt did not consume a number).
- [ ] **6.4 Reconcile:** (optional) confirm two técnicos on separate installs keep independent sequences by prefix.

## 7. Ingresar Remisión — live finca resolution **[NEW: F6]**

- [ ] **7.1** Empty field → label reads **"Cliente sin registrar"**.
- [ ] **7.2** Type a valid ID digit by digit; when it exactly matches a finca, its name shows; keep typing/deleting and the name updates live (e.g. `1822` → name, backspace to `182` → 182's name, clear → "Cliente sin registrar").
- [ ] **7.3** Pick from the autocomplete dropdown → field collapses to just the ID and the name shows.

## 8. Ingresar Remisión — anonymous / placeholder client **[NEW: F6]**

- [ ] **8.1** Leave finca **empty**, add items, Finalizar → office reminder → **new-client form** (IngresarDatosClienteActivity).
- [ ] **8.2** Fill some new-client fields (or leave blank) → continue → firma flow → PDF.
- [ ] **8.3 Verify PDF:** finca block shows placeholders **`CLIENTE POR DEFINIR` / `000`**, and the **Observaciones box contains the "NUEVO CLIENTE …" data** the office needs.
- [ ] **8.4 Unknown ID block:** Type a finca ID that is **not** in the database, Finalizar → **"Cliente no encontrado. Revise el ID de la finca."** — it does **not** proceed with placeholders.

## 9. In-memory draft **[NEW: F6]**

- [ ] **9.1** Add items + a finca → press Back to Menu → re-open Ingresar → items and finca **restored**.
- [ ] **9.2** Complete a send (Sí) → re-open Ingresar → draft is **cleared** (empty).
- [ ] **9.3 App close:** Add items → fully close the app (swipe from recents) → reopen → draft is **gone** (expected: memory-only).
- [ ] **9.4 New-client form persistence:** Enter anonymous flow, type new-client data, back out to the form again → data still prefilled (same session).

## 10. PDF layout — observaciones & overflow **[NEW: F4]**

- [ ] **10.1** Generate a remisión with **10+ items** (fills a page) → Observaciones box sits **above the footer**, no overlap.
- [ ] **10.2** Long observaciones text → wraps inside the box, doesn't spill over the footer.
- [ ] **10.3** Multi-page remisión (e.g. 15 items) → page numbering "PÁGINA X DE Y" correct; totals/signatures/observaciones on the last page.
- [ ] **10.4** Empty observaciones → box shows **"Sin observaciones"**.

## 11. Modificar / Editar Remisión **[REG]** + stock zero-out **[NEW: #5]**

- [ ] **11.1** Modificar → list shows saved remisiones (most recent first), table renders.
- [ ] **11.2** Tap Modificar on one → Editar opens with its número (unchanged) and items loaded.
- [ ] **11.3** Add/remove items, Finalizar → observaciones → signatures → PDF → **Sí**.
- [ ] **11.4 Number unchanged:** the edited remisión keeps its **original número** (no new number consumed).
- [ ] **11.5 Stock net-correct:** stock reflects the edit (old items restored, new items deducted).
- [ ] **11.6 [#5] Zero-out:** Edit so a product's warehouse saldo lands on **exactly 0** → confirm no crash and the row is handled correctly (removed/zeroed) in `Inventario.xlsx`.
- [ ] **11.7 Negative stock:** Deduct more than available → saldo goes **negative** (allowed), no crash.

## 12. Reportes **[REG]**

- [ ] **12.1** Mostrar Todo → all remisión line-items listed, newest first, header row intact.
- [ ] **12.2** Search by **Producto ID** → only matching lines.
- [ ] **12.3** Search by **Remisión (número)** → matching remisión(es).
- [ ] **12.4** Search by **Finca ID** → matching lines.
- [ ] **12.5** No match → "No se encuentran remisiones…". Re-search → previous rows cleared (header kept).

## 13. Inventario Bodega — fresh-install plain view **[NEW: F5]**

- [ ] **13.1** Fresh install (no accepted inventory yet), load the **first INV CSV**, open Inventario Bodega → alert *"…No hay inventario previo para comparar."* and the table shows **all products in a single "Cant" column** (renders, not blank — this was the bug).
- [ ] **13.2** Aceptar/Rechazar buttons are visible (pending inventory).

## 14. Inventario Bodega — comparison **[NEW: F5]**

- [ ] **14.1** With a previously **accepted** inventory in place, load the **second INV CSV** → open Inventario Bodega → **Anterior / Nuevo** columns appear.
- [ ] **14.2 Added** product → row shows `0 → qty`, **red**, near the top.
- [ ] **14.3 Removed** product → row shows `qty → 0`, **red**, near the top.
- [ ] **14.4 Changed** quantity → red, both values shown.
- [ ] **14.5 Decimal change** (10 → 10.5) → values show the **decimal** (not truncated) and the row is red.
- [ ] **14.6 Unchanged** rows → **green**, below the red ones; overall sorted by name within each group.

## 15. Inventario Bodega — Accept (atomic commit) **[NEW: #1]**

- [ ] **15.1** Load a new INV (temp pending) → Aceptar → firma inventario → email opens → confirm **No** → **old inventory unchanged** (`Inventario.xlsx` NOT replaced), temp still pending on re-open. ← key fix.
- [ ] **15.2** Aceptar again → firma → email → **Sí, enviado** → new inventory becomes definitive; temp cleared; comparison gone on re-open.
- [ ] **15.3** Confirm stock deductions from later remisiones use the **newly accepted** inventory.

## 16. Inventario Bodega — Reject **[REG]**

- [ ] **16.1** Load a new INV (temp) → Rechazar → require a motivo (empty blocked) → email opens with the **motivo in the body** (you confirmed body works) → confirm Sí → temp discarded, previous definitive intact.

## 17. Cross-cutting / regression sweep

- [ ] **17.1** Rotate device / background-foreground during firma → no crash, signature flow resumes.
- [ ] **17.2** Loading dialogs at startup behave (no stuck "OK"-less dialogs where inappropriate).
- [ ] **17.3** All emails: PDF/xlsx attachments open correctly from the sent mail.
- [ ] **17.4** Repeated create → send → create cycles: numbers stay strictly sequential per técnico.
- [ ] **17.5** No leftover signature files (`firma_*_DRAFT.png`) after a completed or cancelled send.

---

## Sign-off
- [ ] All **[NEW]** sections pass.
- [ ] All **[REG]** sections pass.
- [ ] Issues found are logged and triaged before prod.
