# Feature Plan — Consecutivo servidor-autoritativo (R8)

> **Status:** **COMPLETE as designed, 2026-10-26 — server, Phase 1 and Phase 2 all implemented;
> nothing has run on a device yet.** The server is live and verified (§5.1, including three
> deviations from §5 as written). 125 host tests green, debug APK builds. A code review of Phase 1
> found two HIGH bugs (§15) and a second review over Phase 2 found eight more, one of them a
> fresh-install brick (§16) — all fixed. Two deliberate
> departures from this plan, both tightening it: **§7 case 1** (a server value alone no longer
> skips the reinstall margin) and **`isConsecutivoInicializado` never gains its server clause**
> (§6 table). §12 Q1 is closed: the stored number is the last one used.
> **Author of design:** planning session, 2026-10-16. Supersedes the "no server to be the source
> of truth" premise of `feature-consecutivo-remision.md` §2 — read that doc first; everything it
> says about the ID format, the assign-at-commit lifecycle and the gated re-seed flow still holds
> except where this doc explicitly replaces it.
> **Scope:** `Methods` (consecutivo block), `InventarioSync`, `IngresarRemisionActivity`,
> `CambiarConsecutivoActivity`, `MenuActivity`.

---

## 1. What changed in the world

The client added a **`CONSECUTIVO` column to the `tecnicos` table** (live on the server since at
least 2026-10-15; `tecnicos` is now 8 columns). Every real técnico is seeded `<numero>-000001`;
only the test row (`numero` 11) is blank. The app already tolerates the column — every consumer
resolves columns by header name — so nothing is broken today, and nothing reads it yet.

That makes a server the authority for the first time, which is what this plan is for. The goal the
owner stated: **retire the manual re-seed flow and take the next consecutivo from the network.**

## 2. The problem this must solve

`app_config.json`'s `consecutivos` counter is the only authority today, and it dies with the app's
data. The self-heal (`Methods.reconcileConsecutivo`) rebuilds a floor by scanning local
`Remision_*.json` files — but `Methods.cleanOldRemisiones` (called from `initializeData`, i.e.
every app open) **deletes those after 14 days**. So a reinstall or a phone change recovers at most
the last two weeks, and today that hole is plugged by hand through `CambiarConsecutivoActivity`.

**A read-only `CONSECUTIVO` column does NOT fix this, and this is the crux of the design.** The
column is refreshed by the daily sync, so a técnico who reinstalls reads whatever the office last
typed there — a value that has no relationship to what the phone actually consumed. Next number
collides with remisiones already sent. **Duplicate consecutivos are unacceptable** (the whole
feature exists so a missing remisión is visible; duplicates make the series unauditable).

Therefore the phone must be able to **write**. There is no substitute: `get_datos_app.php` is
key-gated, `$_GET`-only, hardcoded to four table names, and returns CSV. Nothing in the current API
accepts input.

## 3. The protocol — one idempotent operation

Everything hangs off a single server operation, deliberately shaped so the offline-first client
needs no queue, no locking and no ordering guarantees:

```
claim(numero, cedula, seq)  ->  server: stored = max(stored, seq);  returns stored
```

Three properties, all load-bearing:

- **Monotonic.** The stored value only ever rises. A late, duplicated or out-of-order claim is a
  no-op, so every retry is safe and the app can fire-and-forget.
- **Authoritative response.** The call returns the value *after* the max, so the app learns the
  truth in the same round trip and never has to wait for tomorrow's daily read.
- **Idempotent max collapses the outbox to a single integer.** The app does not need a queue of
  pending consecutivos — only "highest used" vs "highest confirmed by the server". That is one
  `int` in `app_config.json`, and it is why the offline story below is simple.

## 4. The floor rule (read side)

`peekProximoConsecutivo` currently takes `max(stored, reconcileConsecutivo) + 1`. It gains one
more input and never loses one:

```
floor = max( storedLocal, reconcileConsecutivo(files), seqFromServer )
next  = floor + 1
```

- **Only ever raises.** A stale daily read (the server value can be up to a day old, and after a
  wipe it can be badly stale) can never pull a counter backwards, so it cannot mint a duplicate.
- **Adopted, not just consulted.** Whatever the floor resolves to is written back through
  `setStoredConsecutivo` immediately, so the next launch keeps it even with no network.
- **A blank cell means "never claimed"** — a supported state, exactly like `CORREO OFICINA`. It
  routes to the fresh-install gate (§7), not to an error.
- **The column being absent is not fatal.** `CONSECUTIVO` must NOT join
  `ActualizarDatos.REQUIRED_HEADERS` (a server rollback or an older stored database would then
  fire the app-closing dialog — the same reasoning locked in for `CORREO OFICINA`). Absent
  column -> `Log.w` + today's purely-local behaviour + the manual flow stays reachable.

## 5. The write endpoint as specified to the client (BUILT — see §5.1 for what shipped)

One script. It may stay GET-shaped to match the rest of their API; the app does not care.

```
GET /reportes-api/set_consecutivo.php?key=…&numero=3&cedula=71683340&seq=45
  200  {"numero":"3","seq":45,"consecutivo":"3-000045"}   stored (or already higher)
  409  {"numero":"3","seq":50,"consecutivo":"3-000050"}   refused: lower than stored
  403                                                     numero+cedula do not match a tecnicos row
  401                                                     bad/missing key
```

Non-negotiable server rules, and the reasons, because these are what make the client safe:

1. **`stored = max(stored, seq)`, never a blind overwrite.** This is what lets the phone retry
   blindly for days. A blind write would let a stale phone roll the office's counter backwards.
2. **`numero` + `cedula` must match the `tecnicos` row.** The API key is compiled into the APK
   (`BuildConfig.DATOS_APP_KEY`), so key-alone auth on a *write* endpoint would let anyone move
   any técnico's counter. See §10 — this is a new exposure and the owner should know it.
3. **Return the stored value in the body**, after the max. The app adopts it.
4. **Append-only audit row per call** (`numero`, old, new, timestamp, source/device). The cell
   alone cannot show a jump, and jumps are exactly what the office needs to see: a reinstall
   deliberately creates one (§8).
5. **The `tecnicos` CSV keeps exposing the current value** in the `CONSECUTIVO` column, so the
   daily read stays the recovery path when a claim response is lost.

Optional, cheap, and worth asking for in the same breath:

- **`usados=46,47,48`** — the full list of numbers consumed since the last successful claim, so
  the audit log holds every individual remisión number even when a batch lands after an offline
  stretch. Without it the log shows only `45 -> 48`.
- **`device=<install id>`** — lets the server flag two devices claiming for one técnico, which is
  the phone-swap failure in §9 and is otherwise invisible to everyone.

## 5.1 What was actually deployed (2026-10-26) — the authoritative record

The client handed the owner cPanel access rather than finishing the work, so the owner deployed it
himself with this session guiding. §5 above is the spec as negotiated; **this section is what the
server does.** Where they disagree, this section wins.

### The server, as it really is

- **`/home3/conagrocom/conagro.com.co/reportesbodega/` is a private FTP folder, not web-served.**
  The ERP uploads into it. `db_config.php` lives here, outside the published area.
- `datos_app/` inside it holds **four separately exported CSVs** — which is the long-standing
  explanation for why the four payloads never agreed on delimiter, quoting or BOM. **There is no
  `.xlsx` on the server at all.**
- **`/reportes-api/` is the only published folder**: `get_reporte.php`, `get_datos_app.php`
  (+ a `.bak` of the pre-merge version) and now `set_consecutivo.php`.
- `tecnicos.csv` is **ISO-8859**, CRLF, `;`-delimited, unquoted, served raw under
  `charset=utf-8`. Two surnames therefore reach the app as invalid UTF-8. Pre-existing, cosmetic,
  untouched.

### Storage

MySQL `conagrocom_remisiones`:

- **`tecnico_consecutivo`** — `numero INT UNSIGNED`, `cedula BIGINT UNSIGNED`,
  `consecutivo INT UNSIGNED`, `actualizado DATETIME`, `PRIMARY KEY (numero, cedula)`.
  `cedula` is `BIGINT` deliberately: `numero` 13's cédula is 11 digits and overflows `INT`.
  The composite PK is what stops two simultaneous first-ever claims inserting duplicate rows
  (`SELECT … FOR UPDATE` cannot lock a row that does not exist yet).
- **`consecutivo_log`** — append-only audit: `numero`, `cedula`, `anterior`, `nuevo`, `usados`,
  `device`, `ip`, `creado`. §5 rule 4, satisfied.

### Three deviations from §5

1. **§5 rule 5 is satisfied by a merge, not by storing the value in the CSV.**
   `get_datos_app.php` serves `max(CSV cell, MySQL)` in the `CONSECUTIVO` column for
   `tabla=tecnicos` **only**; the other three tables remain untouched `readfile()`. This is what
   makes an ERP re-upload of `tecnicos.csv` harmless, and it keeps the office's ability to raise a
   floor by typing a higher value into the file. **If the database is unreachable the script falls
   back to the raw file**, so the daily sync can never be broken by the counter.
2. **`set_consecutivo.php` additionally mirrors the value into the CSV cell** — the owner's call
   (2026-10-26), so the office can read the file directly. Best-effort and not the authority: a
   `.lock` file + tmp + `rename()`, replacing one field on one line so the ISO-8859 bytes and CRLF
   survive byte-for-byte. If it fails, the claim still returns 200 and the merge covers it.
   Consequence accepted by the owner: after an ERP re-upload only técnicos who claim afterwards get
   their cell repaired. A `resync_consecutivos.php` was offered and **declined**.
3. **The response body is `{"numero","seq","consecutivo"}`** — `seq` the integer, `consecutivo` the
   formatted `3-000045`. The app should read **`seq`**; it must still format locally anyway (§6.0)
   because an offline remisión gets no response at all.

### Wire contract for Phase 2 (verified against the live endpoint)

```
GET /reportes-api/set_consecutivo.php?key=…&numero=11&cedula=1&seq=5[&usados=4,5][&device=…]
  200  {"numero":"11","seq":5,"consecutivo":"11-000005"}   stored, or already higher
  409  {"numero":"11","seq":5,"consecutivo":"11-000005"}   refused: seq was lower than stored
  403  numero+cedula do not match a tecnicos row
  401  bad/missing key
  400  non-numeric numero/cedula/seq, or seq > 999999
  500  internal
```

- **409 is not a failure — it is the authoritative answer.** Adopt the returned `seq` as the floor
  exactly as on a 200. The only difference is that this phone's value was stale.
- `usados` and `device` (both optional, §5's extras) are implemented and land in the audit log.
- **Probing with no `numero`/`cedula`/`seq` returns 400**, so liveness can be checked without
  moving anybody's counter. Keep doing it that way.
- Same API key as the other two endpoints, i.e. `BuildConfig.DATOS_APP_KEY`'s value. Phase 2 adds
  `SET_CONSECUTIVO_URL` to `local.properties`/`BuildConfig` (§14).

### Three bugs fixed in the client's draft before it went live

- **`fgetcsv($fh)` used the default comma delimiter on a `;`-delimited file.** Every line came back
  as one field, `count($fila) >= 2` was never true, and the endpoint **403'd every técnico** — the
  whole feature would have been dead on arrival. It now detects the delimiter from the header line,
  because that export format has already drifted twice.
- **No cap on `seq`.** The rule is monotonic, so a single call with `seq=999999999` would burn a
  técnico's series permanently, recoverable only by hand in phpMyAdmin. Now `400` above 999999.
- **`require __DIR__ . "/db_config.php"`** broke when the script moved into the published folder.
  Now an absolute path into the private folder.

### Verification, 2026-10-26, test row only (`numero` 11, `cedula` 1)

Guards: no params → 400 · bad key → 401 · wrong cédula → **403** · `seq=1000000` → 400. Claim
`seq=5` → 200 `11-000005` · replay `seq=3` → **409**, still `11-000005` · replay `seq=5` → 200,
idempotent. Served `tecnicos` changed **exactly one of 34 lines**; all rows still 8 fields; the
accented row byte-identical; `tarifas`/`clientes`/`productos` unchanged (50 / 2 423 / 4 863 rows).

The wrong-cédula 403 is the proof the delimiter fix landed: with the original script a *valid*
call 403'd too, so 403 alone proves nothing — it has to be paired with a successful claim.

**The test row is parked at 5 on purpose.** `numero` 11's first real claim must exceed it.

### Handed to the client (owner is relaying)

- **Keep `CONSECUTIVO` in the export.** If it disappears the row stops matching 8 fields, the
  mirror silently stops writing, and the app falls back to local-only numbering — which §4
  tolerates by design, so the failure is invisible.
- `rename()` leaves `tecnicos.csv` owned by the PHP user; the ERP's FTP upload must still work.
- `tecnicos.csv.lock` now lives in `datos_app/` and is recreated if deleted.

## 6. App changes

**Wire format: a plain integer sequence (`seq=45`), never a formatted id.** The server stores and
displays `3-000045`; the app sends only `45` and lets the server compose the string, so the
definitive format is written in exactly one place and the two sides cannot drift. On the read
side, parse the column with the robust rule — **the last 6 digits are the sequence** — after
checking the prefix digits match this técnico's `numero`, so `1-000001` and `01000001` both work
and one técnico can never inherit another's series.

### 6.0 The visible format is `NUMERO-NNNNNN` (owner's call, 2026-10-16)

**This is a defect in today's build, not a new requirement, and it is independent of the write
endpoint — it can ship with Phase 1.** The owner states that `3-000045` (unpadded prefix, hyphen,
6-digit sequence) is the definitive consecutivo: it is how the office seeded the `CONSECUTIVO`
column, how they want it on the remisión PDF, and how it must read everywhere a human sees it.

The app does not produce that string anywhere today. `Methods.formatConsecutivo`
(`Methods.java:934`) returns `format2Digitos(numero) + "%06d"` → **`03000045`**, and that is what
`generarPDFRemision` prints as `REMISIÓN: 03000045` (`Methods.java:1634`).

**The trap: that same string is the storage key.** It names `Remision_03000045.json` and
`firma_{tecnico,cliente}_03000045.png`, and `Methods.reconcileConsecutivo` (`Methods.java:1087`)
rebuilds the local floor by parsing those filenames as `<2-digit prefix><exactly 6 digits>` —
`id.length() == fPrefix.length() + 6`. Reformatting the shared string would silently break the
14-day self-heal on every phone that already has saved remisiones.

So: **split the internal id from the visible format.**

| | value | used by |
|---|---|---|
| internal id (unchanged) | `03000045` | `Remision_*.json`, `firma_*_*.png`, `reconcileConsecutivo`, `commitConsecutivo`, the `consecutivo` intent extra |
| visible (new) | `3-000045` | the PDF (`REMISIÓN:`), the email subject, the PDF attachment filename, the `CONSECUTIVO` column |

**Scope widened by the owner, 2026-10-26, to every human-facing display**, which gives one rule
worth stating plainly: **files and keys keep the internal id; everything a human reads goes through
`Methods.visibleDeConsecutivo`.** Beyond the four above that means the Ingresar Remisión draft
title, the Editar Remisión screen title, Cambiar Consecutivo's "próximo consecutivo actual", the
Modificar Remisión list, and both the Reportes table and its consecutivo autocomplete. The
reasoning: an edited old remisión already regenerates its PDF in the new shape, so leaving any
display on the old one is the *inconsistent* choice, not the conservative one.

Two consequences that had to be handled, both easy to miss:

- **Reportes searches by consecutivo.** Its autocomplete now offers `3-000046` while the stored id
  is `03000046`, so matching only the stored form would make picking a suggestion find nothing.
  `ReportesActivity.coincideConsecutivo` matches either shape, substring semantics intact.
- **Cambiar Consecutivo asks the técnico to *type* a consecutivo.** It displays the visible shape,
  so that is what gets typed back; its validation rejected the hyphen (`matches("\\d+")`) and
  compared the prefix as a string against `%02d`. It now strips the hyphen and compares the prefix
  numerically, so `3-000044`, `03000044` and `3000044` are all accepted, and the on-screen example
  in `activity_cambiar_consecutivo.xml` was updated to `3-000044`.

`visibleDeConsecutivo` ended up taking **just the id**, not the session map: the internal id already
carries the prefix as `%02d`, so no `userData` is needed — which is what lets Reportes (which has no
session map at all) format one.

- New `Methods.formatConsecutivoVisible(tecnicoNumero, seq)` → `numero.trim() + "-" + %06d`, with
  the **unpadded** prefix (the server's seed is `3-000001`, not `03-000001`).
- `formatConsecutivo` stays exactly as it is. Nothing that touches a filename changes.
- A `Methods.seqDe(String consecutivo)` reads the sequence back out of either shape (last 6
  digits), so a visible string and an internal id are interchangeable as input.
- Call sites: `generarPDFRemision`'s header draw, the `EXTRA_SUBJECT` in `IngresarRemisionActivity`
  (~line 619) and `EditarRemisionActivity`, the attachment filename, and the `seq` the claim sends.

**Already-sent remisiones keep `03000045` (owner's call).** There is no migration and no PDF is
regenerated — they are already in the office's mailbox. The office will see both shapes side by
side for a while; the cutover is the build date. The *files on disk* keep the old shape forever by
design, which is the whole point of the split.

### `Methods` (consecutivo block, ~line 914 onward)

| Member | Behaviour |
|---|---|
| `int getSeqServidor(Map userData)` | Parses `userData.get("consecutivoServidor")`: validates the prefix against `numero`, returns the last-6-digit sequence, or `-1` for blank/absent/malformed. |
| `int getConsecutivoFloor(ctx, userData)` | The §4 `max(...)`, and persists the result. The single place the three inputs meet. |
| `int getSeqConfirmado(ctx, numero)` / `setSeqConfirmado(...)` | The outbox: the highest sequence the server has acknowledged. One int in `app_config.json` under `consecutivosConfirmados`. **Must live in `app_config.json`, not in a `Remision_*` file** — `cleanOldRemisiones` would eat it. |
| `boolean hayConsecutivoPendiente(ctx, numero)` | `getStoredConsecutivo > getSeqConfirmado`. Drives the push. |
| `peekProximoConsecutivo` | Takes the floor from `getConsecutivoFloor` instead of the local-only max. Needs `userData` (or the server seq) passed in — signature change, 3 call sites. |
| `commitConsecutivo` | Unchanged as the local authority; the push is fired *after* it by the caller, never inside it. |
| `isConsecutivoInicializado` | ~~Also true when the server carries a value.~~ **NOT BUILT, and must not be** — see the AS BUILT note in §7. It stays local-evidence-only; Phase 2 retired the manual flow through the gate's blocking claim instead. Adding the server clause would let a wiped phone skip that claim and take `server + 1`, re-minting whatever it consumed offline. |

### `Methods.validateCredentials`

Resolve `CONSECUTIVO` exactly as R6 resolved `CORREO OFICINA`: not in the required-columns throw,
`Log.w` when `columnIndex == -1`, trimmed value into the returned map as `consecutivoServidor`.
Free plumbing — `userData` already rides to every screen as a Serializable extra.

### `InventarioSync` (owns HTTP and the executor)

New `pushConsecutivoIfPending(activity, numero, cedula)`:

- Returns immediately when `!hayConsecutivoPendiente`. Otherwise one GET on the background
  executor, ~200 bytes, `catch (Throwable)` at the boundary like the other two syncs.
- **No daily guard.** An unsent counter must retry at *every* app open; the cost is a single
  tiny request. (The daily guard stays on the four-table download, untouched.)
- On `200`/`409`: `setSeqConfirmado(response.seq)` **and** feed `response.seq` back through the
  floor, so a claim from another install is adopted at once.
- On any failure: silent. Log only. **It must never block, alert, or fail a remisión** — the
  técnico's send already succeeded; the office learning about it is asynchronous by design.
- Also exposes `claimConsecutivoBlocking(...)` for the one case that genuinely has to wait: the
  fresh-install gate (§7).

### Call sites

- `MenuActivity.onCreate` — `pushConsecutivoIfPending(...)` beside the existing bodega sync
  (`numero` **and** `cedula` are both in `userData` here; `MainActivity` runs pre-login and has
  neither, which is why the push cannot live there).
- `IngresarRemisionActivity` — immediately after `Methods.commitConsecutivo` in the
  "Sí, enviado" branch (~line 643), so a connected técnico's number reaches the office in seconds.
- `CambiarConsecutivoActivity` — after the manual seed (~line 282), so a hand re-seed is claimed
  and audited like any other value. The office email it already sends stays as the human record.
- `EditarRemisionActivity` — **nothing.** It reuses the existing number and never increments.

## 7. Offline behaviour, and retiring the manual flow

**Offline is the normal case, not the exception, and nothing about it may depend on the network.**

- The **local counter stays the authority at the moment of use.** `peekProximoConsecutivo` /
  `commitConsecutivo` never touch the network. A técnico offline for a week keeps numbering
  `…046, …047, …048` with no change in behaviour whatsoever.
- The gap between "used" and "confirmed" is the pending state, and it is **one integer**: the
  push just claims the highest. Ordering, retries and duplicate pushes are all no-ops (§3).
- Pushes drain at the next connected app open, or right after the next send.

### A first install always has network (owner, 2026-10-16)

This closes the one hole the design was worried about. **The app cannot function on a fresh
install without connectivity anyway** — it needs the `productos` and `clientes` tables to write a
remisión at all, and on a phone with no database `InventarioSync` blocks behind the "Descargando
base de datos" dialog and ignores the daily guard precisely because `initializeData` would
otherwise reach its app-closing fatal dialog. So "fresh install, no network, needs a number" is
not a state a técnico can usefully be in: they would have no products to sell.

The one residual path is a técnico who imports `Datos App.xlsx` from the correo with no
connectivity (that route skips the web sync by design). Narrow — receiving the mail implies they
had a network — and it resolves itself the moment any connectivity appears, since the claim then
lands. Not worth designing for.

### `CambiarConsecutivoActivity`: button removed, code kept (owner's call, 2026-10-16)

The flow is **retired from the UI but left intact in the codebase**, so it can be reinstated
without rebuilding it:

- **Remove the entry point only.** `android:visibility="gone"` on `btn_CambiarConsecutivo` in
  `activity_menu.xml` (~line 123) is the whole change — a `gone` view is excluded from the
  `layout_weight` distribution exactly as a deleted one would be, so reinstating the flow is a
  one-attribute edit. Note the remaining menu buttons grow to fill the space; that is expected.
- **Keep** the activity class, its layout, its `strings.xml` entry, its manifest entry
  (`AndroidManifest.xml:65`) and the `setOnClickListener` at `MenuActivity.java:99` — all of it
  compiles and works, it is simply unreachable. Do **not** delete the manifest entry: a
  reinstatement would then crash, and nothing is gained by removing it.
- **Still wire the claim into it** (§6), so a reinstated flow is complete on day one and a
  hand-seeded value would be pushed and audited like any other.
- Its `ObservacionesActivity` integration (`flowType = "cambio_consecutivo"`, the mandatory-motivo
  validation from the R7 review pass) stays as-is and keeps its unit tests.

### The fresh-install gate (replaces `feature-consecutivo-remision.md` §8)

At `IngresarRemisionActivity` entry (the authoritative gate, ~line 120):

1. A floor exists locally or from the server -> proceed, no prompt. **This is the new normal, and
   with the server authoritative it is what every reinstall hits.**
2. No floor and **network available** -> `claimConsecutivoBlocking` behind a short spinner; on
   success proceed silently. The técnico sees nothing but a brief wait.
3. No floor and **no network** -> block, with a message that now has no self-service escape:
   "Conéctese a internet para obtener su consecutivo." Per the above this is close to
   unreachable; the office's recourse is to type a value into the `CONSECUTIVO` column, which the
   next sync picks up as a floor (§4).

**AS BUILT (2026-10-26) — case 1 was deliberately tightened, and this supersedes the list above.**
The gate asks only **"does this device have a *local* counter?"** (`isConsecutivoInicializado`,
which is local evidence only — the stored key or surviving `Remision_*.json` files):

- **Local counter exists** -> proceed untouched. Unchanged.
- **No local counter** -> the blocking claim **with the §8 margin**, whether or not the server has
  a value, behind "Obteniendo su consecutivo...". On success, proceed silently.
- **The claim fails** -> a dialog offering **"Configurar manualmente"** (which launches
  `CambiarConsecutivoActivity` directly) or "Salir". **This is the only remaining entry point to
  that screen** now that its menu button is `gone`, and it is why the class is kept rather than
  deleted. A second review caught the first cut of this offering only "Salir": combined with the
  hidden button that **bricked a fresh install** — no remisión could ever be written — and not
  only without a network. A blank `SET_CONSECUTIVO_URL` (the default from a fresh clone, so any
  CI or new-machine build), a rotated key (401) or a `numero`/`cédula` drift (403) all land here,
  and none of them is something the técnico can wait out.

Why: case 1 above would let a wiped phone take the server's value `+ 1`, and having no local
history is *precisely* the state in which the numbers consumed offline before the wipe are
unknowable — which is the entire premise of §8. Taking `+ 1` there re-mints them. A gap on
reinstall is recoverable and legible; a duplicate is neither. The técnico's experience is what
cases 1–2 promised either way (a brief wait, no prompt, no manual screen), so the only cost is up
to 99 burned numbers on a reinstall, which §8 already accepted.

## 8. The reinstall margin — turning a possible duplicate into a visible gap

A reinstall can only ever read a value that is *at best* current and *at worst* missing every
number consumed offline since the last successful claim. Taking `server + 1` would re-mint those.
So on a **fresh claim with no local history**, the app does not ask for `server + 1`; it asks the
server to **jump to the next multiple of 100** (`seq = (floor/100 + 1) * 100`).

- Burns up to 99 numbers, creating a gap the office can see and explain: a jump to a round hundred
  is the signature of a wiped/replaced phone. Legible by construction, no decoding needed.
- 6 digits allow 999 999 per técnico; the busiest is in the low thousands today, so the cost is
  irrelevant for decades.
- This is the design doc's own rule applied honestly: *"moving the counter forward only ever
  creates a visible gap (recoverable); moving it backward is the only destructive move."* A margin
  trades a recoverable gap for the elimination of an unrecoverable duplicate.
- The constant lives in one place (`Methods.MARGEN_REINSTALACION`) so the owner can change the
  policy without touching logic. A flat `+20` is the alternative if round hundreds look wasteful
  to the office; the server-side variant (margin derived from `days_since_last_claim`) is noted in
  §12 and deliberately not asked for in v1.

## 9. Phone swap / two live installs

`feature-consecutivo-remision.md` §2 fixed "one device per técnico", and R8 relies on it. The
residual hazard: the **old** phone still has the app and un-pushed numbers when the new one claims.
What the design gives us:

- The new phone's claim jumps to the next hundred (§8), so it does not re-mint anything the old
  phone sent.
- The old phone, on its next connected launch, reads/claims and is **pulled forward** to the
  server's value, because the floor only raises. It cannot continue the old series.
- Numbers it consumed *between* the swap and that launch were already minted on both devices —
  the one window R8 cannot close from the app side.

Operational rule to hand the owner: **when a técnico changes phones, open the app once on the old
device while online (or wipe it).** The optional `device=` parameter (§5) is what makes a violation
visible to the office instead of silent.

## 10. Security note — this is a new exposure, say it out loud

`DATOS_APP_KEY` is compiled into the APK and the APK is on técnicos' phones. Today a leaked key
costs *read* access to employee and client data. **Since 2026-10-26 the write endpoint is live**, so a leaked
key additionally allows moving any técnico's counter. The §5 rules bound the damage to "push a
counter forward" (monotonic, cédula-matched, and now also capped at 999999 — §5.1), which creates
visible gaps rather than duplicates or data loss. The owner was told this before deploying and
accepted it; note that rotating the key now costs a new APK release plus `local.properties` plus
all three PHP files, since all three share one key. Ask the client for, in order of value:
per-técnico token or HMAC over the parameters > rate limiting per `numero` > the audit log (already
required). None of them changes the app's design.

## 11. Decisions to lock before coding

- **The local counter remains the authority at the moment of use.** The server is a floor on read
  and a mirror on write. No remisión ever waits on the network. (Exception: the very first claim on
  a device with no history, §7 case 2.)
- **`max()` everywhere, both directions.** No operation in this feature ever lowers a counter.
- **The counter never lives in `datos_app/*.csv`.** That store is a disposable cache of the
  server, overwritten by every daily sync and discarded wholesale when corrupt. Local state goes
  in `app_config.json`, which no sync path touches.
- **`CONSECUTIVO` is a superset column, not a required one** (§4).
- **Push is fire-and-forget and silent.** No alert, ever, on a push failure. The técnico cannot
  act on it and the state is self-healing.
- **The manual flow keeps its code but loses its button** (§7). Decided by the owner on
  2026-10-16, not an open recommendation: a first install always has network (it cannot bootstrap
  `productos`/`clientes` without it), so the no-network escape it existed for is unreachable in
  practice. Nothing is deleted, so reinstating it is one XML attribute.
- **No per-remisión upload.** The app does not post remisión contents anywhere; only a counter.
  (The PDFs keep going to the office by email, which stays the record of *what* was sent.)
- **`NUMERO-NNNNNN` is the visible format; `%02d%06d` stays the storage key** (§6.0, owner's call
  2026-10-16). The split exists because `reconcileConsecutivo` parses the filename, not because
  two formats are desirable. Already-sent remisiones are **not** migrated or regenerated.
- **The margin stays at the next round hundred — do NOT shrink it** (owner raised this 2026-10-26:
  the consecutivo doubles as the office's count of remisiones sent, so skipping is a real cost).
  Established while answering it: **normal use never skips** — `peekProximoConsecutivo`
  (`Methods.java:1117`) only reads, the number is consumed by `commitConsecutivo` on "Sí, enviado"
  (`IngresarRemisionActivity.java:643`), an abandoned draft reuses its number, and the claim is a
  `max()` so retries cost nothing. The **only** skip is §8's margin, and only on a device with no
  local history — where today's build does something strictly worse (next number = **1**, i.e.
  duplicates, unless the office hand-seeds through the flow we are retiring). The reason not to
  shrink it: a jump of 7 is indistinguishable from 7 lost remisiones, while a jump to a round
  hundred cannot be mistaken for anything but a wiped phone. Shrinking trades gap *size* for gap
  *ambiguity*, and ambiguity is the expensive one — a gap is supposed to mean "a remisión is
  missing". **The office's real count comes from `consecutivo_log`** (§5.1), which records every
  claim as `anterior → nuevo` with timestamp and device and therefore separates a reinstall jump
  from genuine loss; the bare series never can. And no margin is fully safe: a margin of N only
  protects you if fewer than N remisiones were made offline since the last claim — zero skips
  *and* zero duplicates would require every number to be confirmed online before use, which the
  first bullet of this section refuses on purpose.

## 12. Open questions — the one that still blocks coding

1. **CLOSED 2026-10-26 (owner): the stored number is the LAST NUMBER USED**, which is what this
   plan already assumed and what `app_config.json`'s `consecutivos` means, so next = value + 1 and
   no adjustment is needed anywhere. For the record, its blast radius was narrower than this
   section implied: the floor is `max(storedLocal, reconcile, server)`, so on any phone already in
   use the local counter dominates the seed — the answer only decided whether a **fresh** device's
   first remisión was `3-000001` or `3-000002`.
2. **CLOSED 2026-10-26 — the endpoint is deployed and verified.** The client accepted the §5 spec
   on 2026-10-22, then handed the owner cPanel access instead of finishing; the owner deployed it
   himself. **§5.1 is the record of what is actually running**, including the three deviations.
   Phase 2 no longer waits on anybody. There is no `get_consecutivo.php` and none is needed — the
   value comes back inside `tabla=tecnicos`.

Non-blocking, ask when convenient:

- The test row (`numero` 11, `NOMBRES` = `nombre`, login `test`) is still on the server, now with
  a blank `CONSECUTIVO`. Already flagged to the owner; the client was going to remove it.
- Margin policy: next-hundred (recommended) vs flat `+20` vs server-computed from
  `days_since_last_claim`.
- Whether the office wants the optional `usados=` list (§5) for per-number auditing, or is happy
  with the mailbox as the record of individual remisiones.

## 13. Tests

**Host suite** (extends the existing 87; the `ValidateCredentialsOfficeTest` pattern already
installs tables through the real `Methods.adoptDatosApp`, so no new seam is needed):

- `getSeqServidor`: `3-000045` -> 45; `03000045` -> 45; blank -> -1; absent column -> -1;
  non-numeric -> -1; **wrong prefix** (`7-000045` read by técnico 3) -> -1, the anti-inheritance
  guard; more than 6 trailing digits; the `numero > 99` prefix-widening case.
- Floor: `max` of the three inputs; a *lower* server value never lowers the stored counter; the
  resolved floor is persisted; `isConsecutivoInicializado` true on a server value alone.
- Outbox: `hayConsecutivoPendiente` transitions; a confirmed value equal to stored clears it;
  a claim response **higher** than local raises the floor.
- §6.0 format: `formatConsecutivoVisible(3, 45)` -> `3-000045` (prefix **unpadded**);
  `(11, 1)` -> `11-000001`; `(3, 999999)`; a non-numeric `numero` falls back to the trimmed raw
  value. `seqDe` reads 45 out of `3-000045`, `03000045` and `11-000045`, and returns -1 for blank,
  null and non-numeric. And the invariant that matters: `formatConsecutivo` is **byte-for-byte
  unchanged** for the same inputs, since the saved filenames depend on it.
- Margin: next-multiple-of-100 from 45, 99, 100, 101, 4 850; and that it is applied **only** when
  there is no local history.

**Device checks** (the host suite cannot reach any of these — they need a `Context`, a network and
a wipe):

1. **Reinstall with network, the headline case.** Send a few remisiones, note the last number,
   clear app data, log in: **no re-seed prompt**, and the first new remisión lands at the next
   hundred above the server's value. Verify no number is ever reused.
2. **Reinstall with no network.** Expected to be a dead end now, and that is the design: the
   gate blocks with "Conéctese a internet". Confirm it blocks *cleanly* (no crash, no half-state,
   the técnico can still browse everything else) and that plugging in a network then lets the
   first remisión through with no prompt. Also confirm the menu no longer offers Cambiar
   Consecutivo and that the remaining buttons lay out sensibly without it.
3. **Offline streak.** Airplane mode, send 3 remisiones -> they number normally and silently;
   restore network, reopen the app -> logcat shows one claim, the `CONSECUTIVO` column catches up
   to the highest number on the next daily read.
4. **Push failure is invisible.** Kill the network mid-send: the remisión completes, no alert, and
   the counter is claimed on the next launch.
5. **Server value behind the phone.** Hand-set the column to a lower number: login must not lower
   the local counter, and the next claim pulls the server back up.
6. **Server value ahead of the phone** (simulates a swap): login adopts it and the next number
   continues the server's series.
7. **Column absent** (older stored database, or a server rollback): login succeeds, numbering is
   purely local, no fatal dialog — i.e. exactly pre-R8 behaviour.
8. **The visible format** (§6.0) — create a new remisión: the PDF must read
   `REMISIÓN: 3-000045` with the hyphen, the email subject the same, and the attachment must be
   named that way too. On the same phone, `Modificar Remisión` must still open every older
   remisión (whose files are named `Remision_03000044.json`) — that list and `reconcileConsecutivo`
   read the internal id, which did not change. Also confirm the next number is still correct after
   a relaunch, which is `reconcileConsecutivo` proving it can still parse the filenames.

## 14. Change map & sequencing

| File | Change |
|---|---|
| `Methods.java` | `getSeqServidor`, `getConsecutivoFloor`, `get/setSeqConfirmado`, `hayConsecutivoPendiente`, `MARGEN_REINSTALACION`; `peekProximoConsecutivo` takes the server seq; `isConsecutivoInicializado` accepts a server value; `validateCredentials` exports `consecutivoServidor`. |
| `Methods.java` (§6.0) | `formatConsecutivoVisible(numero, seq)` and `seqDe(String)`; `formatConsecutivo` and every filename untouched. Ships with Phase 1. |
| `IngresarRemisionActivity` / `EditarRemisionActivity` (§6.0) | Visible format in `EXTRA_SUBJECT` and the PDF attachment name. |
| `Methods.generarPDFRemision` (§6.0) | `REMISIÓN: ` prints the visible format (`Methods.java:1634`). |
| `InventarioSync.java` | `pushConsecutivoIfPending` (background, no daily guard, silent) and `claimConsecutivoBlocking` (the fresh-install gate only). |
| `MenuActivity.java` | Fire the pending push at login, beside the bodega sync. |
| `IngresarRemisionActivity.java` | Push after `commitConsecutivo`; the reworded fresh-install gate. |
| `CambiarConsecutivoActivity.java` | Wire the claim in after the manual seed, so a reinstatement is complete on day one. The flow stays unreachable. |
| `activity_menu.xml` | `android:visibility="gone"` on `btn_CambiarConsecutivo` (~line 123) — the only UI change in R8. Keep the class, layout, string, manifest entry (`AndroidManifest.xml:65`) and the listener at `MenuActivity.java:99`; see §7. |
| `ActualizarDatos.java` | **No change** — `CONSECUTIVO` stays out of `REQUIRED_HEADERS`. |
| `app/build.gradle.kts`, `local.properties` | `SET_CONSECUTIVO_URL` into `BuildConfig`, same pattern as `DATOS_APP_URL`. |
| (follow-up) `ReportesActivity` | The gap report the client actually wants, now that the office holds a real series. Out of scope for R8. |

**Order of work**, so nothing waits on the client unnecessarily:

1. ~~**Phase 1 — read side, ships without the endpoint.**~~ **DONE 2026-10-26.** Compiling, 118
   host tests green (was 87), **not yet run on a device.** What landed:
   - `Methods.formatConsecutivoVisible`, `seqDe`, `visibleDeConsecutivo` — §6.0. `formatConsecutivo`
     and every filename that feeds `reconcileConsecutivo` are byte-for-byte unchanged.
   - The visible format now feeds the PDF header, both email subjects, and the **PDF filename**
     (`Methods.java`, `Remision_3-000045.pdf`) — which is the attachment name the office receives.
     Safe because nothing looks that file up by name and `cleanOldRemisiones` sweeps it by prefix;
     the `.json` keeps the internal id, which is what the self-heal parses.
   - **Plus every other human-facing display**, per the owner's 2026-10-26 widening (see §6.0):
     the Ingresar draft title, the Editar screen title, Cambiar Consecutivo's display *and* its
     input validation + on-screen example, the Modificar list, and the Reportes table and
     autocomplete with `coincideConsecutivo` so searching still works across both shapes.
   - `Methods.getSeqServidor(userData)` — the prefix-checked parse, `-1` for blank/absent/foreign.
   - `Methods.getConsecutivoFloor(context, userData)` — the §4 `max(...)`, adopted via
     `setStoredConsecutivo`. `peekProximoConsecutivo` now takes `userData` and goes through it;
     there is **no overload left that can bypass the floor**. `CambiarConsecutivoActivity`'s
     below-floor warning uses the same helper.
   - `validateCredentials` exports `consecutivoServidor` (superset column, `Log.w` when absent,
     blank is a supported state) — same treatment `CORREO OFICINA` got in R6.
   - New tests: `ConsecutivoVisibleTest` (23) and `ValidateCredentialsConsecutivoTest` (8, through
     the real `adoptDatosApp` → `validateCredentials` path).

   **`isConsecutivoInicializado` was deliberately NOT given its server clause.** §6's table has a
   server value counting as "initialized", and that is correct *with* Phase 2 — but Phase 2 is what
   makes the column trustworthy. Today every real row still holds the office's seed
   `<numero>-000001`, so trusting it would let a reinstalled phone skip the manual gate and mint
   from 2, duplicating that técnico's entire history. The floor is safe without Phase 2 because it
   only ever raises; this is not. The one-line change is written out in the method's javadoc and
   must land **with** the push and the §8 margin, never before.
2. ~~**Client builds §5.**~~ **DONE 2026-10-26** — deployed and verified by the owner; read §5.1
   before writing Phase 2, because the wire contract there is the tested one.
3. ~~**Phase 2 — write side.**~~ **DONE 2026-10-26.** Compiling, debug APK builds, 125 host tests
   green, **not yet on a device.** What landed:
   - **Outbox:** `Methods.get/setSeqConfirmado` + `hayConsecutivoPendiente`, in `app_config.json`
     under `consecutivosConfirmados`. One int, as §3 promised.
   - **Margin:** `Methods.MARGEN_REINSTALACION = 100` + `seqConMargen(floor)`, clamped to 6 digits.
     Covered by `ConsecutivoMargenTest` (7), whose real assertion is the property that the result
     is **always strictly greater than the floor** — if it could ever equal it, a reinstall would
     re-mint a number the office already holds.
   - **Push:** `InventarioSync.pushConsecutivoIfPending(context, numero, cedula)` — background
     executor, **no daily guard**, `catch (Throwable)`, silent on every failure. Call sites:
     `MenuActivity.onCreate` (login), `IngresarRemisionActivity` right after `commitConsecutivo`,
     and `CambiarConsecutivoActivity` after a manual seed so a re-seed is audited too.
   - **Blocking claim:** `InventarioSync.claimConsecutivoConMargen(activity, userData, listener)`,
     used only by the gate. It is the one path allowed to *establish* a local counter, because the
     number it stores came from the server.
   - **Its own HTTP**, not `httpGet`: that one maps anything unexpected to `NETWORK_ERROR` and
     discards the body, but **409 is an authoritative answer whose body carries the value to
     adopt** — and a 409 body arrives on the *error* stream, not the input stream. 200 and 409 are
     treated identically. 400/401/403 are logged and retried next open, never shown.
   - **`device=`** an 8-hex per-install id (`Methods.getInstallId`, in `app_config.json`), and
     **`usados=`** the individual numbers since the last confirmation, capped at 40 to stay under
     the server's 255-char column.
   - **Config:** `SET_CONSECUTIVO_URL` in `local.properties` → `BuildConfig`, same pattern as
     `DATOS_APP_URL`; blank turns the push off and numbering stays purely local (pre-R8 behaviour).
     The key is the existing `DATOS_APP_KEY`.
   - **UI:** `android:visibility="gone"` on `btn_CambiarConsecutivo`. Class, layout, string,
     manifest entry and the `MenuActivity` listener all stay, so reinstating it is one attribute.

Phase 1 is maybe 60 lines, plus §6.0 (another ~15 and five call sites), plus a test class. Phase 2
is the work, but it has no POI, no file format and no UI of consequence — it is one GET, one
integer of state, and five call sites.

---

## 15. Code review, 2026-10-26 — what it caught

A review of Phase 1 (`/code-review high`) found two HIGH bugs **in the floor**, both fixed before
Phase 2 was built on top. Recorded because they were the same mistake twice: the floor is a *read*,
and making it write is what broke the guards around it.

1. **`getConsecutivoFloor` silently satisfied the fresh-install gate.** It persisted through
   `setStoredConsecutivo`, which *creates* the `consecutivos.<numero>` key, and
   `isConsecutivoInicializado` tests key presence. So on a fresh install, opening Cambiar
   Consecutivo and cancelling at any later dialog left the key behind; the gate then passed and the
   phone minted from the office's seed + 1 — the exact duplicate scenario the Phase 1 carve-out was
   written to prevent. **Fix:** the private `Methods.tieneContadorLocal` now gates the write, so the
   floor **raises an existing counter and never creates one**. That predicate is deliberately
   separate from `isConsecutivoInicializado`, so a later widening of that method cannot make the
   floor self-authorising again.
2. **The floor reverted a deliberate below-floor re-seed, and the UI lied about it.** `applyChange`
   stored the typed number, then `refreshDisplay()` recomputed the floor and raised it straight
   back, while the "Éxito" dialog announced the number that had not stuck. The floor winning is
   correct per §11 (nothing ever lowers a counter); the lie was not. **Fix:** the dialog now reports
   the *effective* next consecutivo and says explicitly when the office already holds a higher one.

Two more, also fixed: Cambiar Consecutivo's **office email and success dialog still printed the
internal `03000044`** on the one screen otherwise fully converted to §6.0's format (plus a bare
integer in the below-floor warning); and `DatosAppStore.save`'s javadoc **claimed a group atomicity
that four separate renames cannot provide** — corrected to state the real guarantee (writing all
four tmp files first prevents a *mismatched* store; a failed rename leaves a file missing, which
`exists()` reports as "no store" and the bootstrap download then rebuilds).

One finding was **closed without a code change**: with §12 Q1 answered as "last used", the seed
`<numero>-000001` means number 1 is already spent, so the 32 seeded técnicos never produce a
`-000001` remisión — their first is `-000002`. That follows from the owner's own answer. **Worth
telling the office**, so a series starting at 2 is not read as a missing remisión.

## 16. Second code review, 2026-10-26 — Phase 2 included

`/code-review high` again, over Phase 1 + the §15 fixes + Phase 2. Eight findings, all fixed. It
confirmed the two riskiest Phase 1 claims hold: nothing reconstructs the PDF path by name, and
`reconcileConsecutivo`'s `.json` filter cannot be confused by `Remision_13-000045.pdf`.

**HIGH — the fresh-install gate was a dead end.** Covered in §7 above: the gate offered only
"Salir" while `btn_CambiarConsecutivo` was `gone`, so any non-network claim failure left a fresh
install unable to write a remisión, forever. The planning note "a first install always has network"
covered one of four triggers. Fixed by making the manual screen the gate's explicit fallback.

**MEDIUM — the blocking claim was queued behind the daily downloads.** It shared the single-thread
`executor` with the 4-request database sync and the bodega sync (15 s connect + 20 s read each), so
on a slow link the técnico could sit behind a **non-cancelable** dialog for minutes before the claim
even started. Fixed with a dedicated `claimExecutor`; the silent push still shares the old one,
where queuing is harmless.

**MEDIUM — `usados=` reported numbers the phone never issued.** It enumerated the integer gap
`confirmado+1..stored`, but the floor can raise `stored` with no remisión sent — an office override
typed into the server column (a recourse §5.1 deliberately preserves) would have filled the audit
log with up to 40 invented numbers, in the one record that is supposed to prove what this device
did. Fixed: `Methods.getConsecutivosUsados` reads the actual `Remision_*.json` sequences. Bounded by
the 14-day sweep, which is the right trade — a short accurate list beats a long invented one.

**LOW — no guard against a second claim.** Activity recreation ("don't keep activities", low
memory) re-fires the gate; if the first claim had already adopted, the second would see the new
floor and ask for the **next** margin block — a 200-number gap from one recreation. Fixed by
re-checking `isConsecutivoInicializado` inside the executor task, which also reports the right
answer to the second activity instead of just suppressing it.

**LOW — `getErrorStream()` can be null** on a 409 with no body; `readAll(null)` NPEs, and
`catch (IOException)` does not catch it, so the "unusable body" diagnostic never fired and the NPE
escaped to the caller. Guarded.

**LOW — the 6-digit clamp broke the margin's own invariant.** `seqConMargen(999999)` returned
999999 — *equal* to the floor, i.e. a re-mint — and `ConsecutivoMargenTest` asserted that equality,
enshrining the hole it was written to prevent. Now it falls back to `floor + 1` while that fits and
returns **-1 once the series is spent**, which the blocking claim treats as a failure. Unreachable
for decades, but it was the one case where the test actively protected a duplicate.

**LOW — a below-floor re-seed left the confirmed mark high.** Nothing lowered
`consecutivosConfirmados`, so `hayConsecutivoPendiente` stayed false and remisiones 51–100 after a
re-seed to 50 would be sent with **no push at all** while the server kept 100. Fixed with
`Methods.bajarSeqConfirmado`, called from `applyChange`. Only reachable with the button re-enabled —
which is exactly why the guard lives in `Methods` and not in the screen.

> **SUPERSEDED by §17.** The third review found this fix made the push unreachable in the very
> case it was written for, and that the below-floor re-seed could not be honoured at all. The
> below-floor path is now refused and `bajarSeqConfirmado` is deleted.

**LOW — `formatConsecutivoVisible` used `Locale.getDefault()`.** Its output is now a PDF filename
and an email subject, so a locale with non-ASCII decimal digits would produce an attachment name and
an outbound identifier nobody could read back, `seqDe` included. Now `Locale.US`.
**`formatConsecutivo` deliberately keeps `getDefault()`**: it names the stored `Remision_*.json` and
`firma_*.png` files that `reconcileConsecutivo` parses, so changing its locale would stop it
recognising files an affected device had already written — dropping the floor, which is the
duplicate case. The pre-existing exposure there is left alone on purpose.

## 17. Third code review, 2026-10-26 — ten findings (this session finished them, 2026-10-30)

The session that ran this review hit its usage limit **mid-fix**, leaving the tree uncompilable
(four `cannot find symbol` errors in `IngresarRemisionActivity` — the gate had been rewritten to
call three helpers that were never added). Picked up and completed 2026-10-30; the whole set is
below so nobody re-derives it.

### The one that changed the design, not just the code

**MEDIUM ×2, resolved together — a seed below the floor cannot be honoured at all, so it must not
be offered.** Review #2 had said a downward re-seed left `consecutivosConfirmados` high, so the
re-minted range would never be pushed; #3 found that the fix for that (`bajarSeqConfirmado`) made
the push *unreachable* in exactly the case it was written for — `applyChange` set `stored` and
`confirmado` to the same value, so `hayConsecutivoPendiente` was false and nothing was sent — **and**
that confirming the below-floor warning did nothing anyway: `applyChange` stored the typed number
and the next `peekProximoConsecutivo` recomputed `max(local, archivos, servidor)` and persisted the
old floor straight back. The dialog also blamed the server unconditionally, sending the técnico to
call the office about a number the office may not hold.

Both are symptoms of one thing: the server is monotonic and §11 locks "nothing ever lowers a
counter", so **"Sí, continuar" was the bug**. The below-floor case is now **refused** ("Número no
permitido", with the effective number and the instruction to ask the office to correct it in the
server). `Methods.bajarSeqConfirmado` is **deleted** — it existed only to serve a path that no
longer exists, so the §16 entry describing it is superseded by this one.

### Fixed as found

- **MEDIUM — the blocking claim reported success without proving a counter exists.** The gate
  treated any `seq >= 0` as success, but `adoptClaim` only writes when the value is higher and
  `setStoredConsecutivo` swallows IO failures — so a `{"seq":0}` answer or a failed
  `app_config.json` write opened the screen with no counter, and the first remisión would be minted
  as `…000001`, duplicating the técnico's whole history. `claimConsecutivoConMargen` now re-checks
  `isConsecutivoInicializado` after adopting and reports -1 (→ the manual fallback) if the counter
  did not land.
- **LOW — `adoptClaim`'s compare-then-write was not atomic**, contradicting its own comment: two
  separate `synchronized` accessors, so a `commitConsecutivo` landing between them was overwritten
  by a value already stale when read — i.e. the counter could be *lowered*. It only survived
  because `reconcileConsecutivo` re-reads the remisión files. Now one critical section,
  `Methods.subirStoredConsecutivo`.
- **LOW — `usados=` was capped by count, not length.** 40 six-digit sequences plus commas is 279
  characters against the server's 255-char column, so the margin vanished as the series grew and
  the server would truncate mid-number. Now capped at 240 **characters**, newest first.
- **LOW — `formatConsecutivoVisible` did not normalize the número**, while `visibleDeConsecutivo`
  parses it as an int. A Tecnicos cell reading `03` would have printed `03-000045` in the Cambiar
  Consecutivo dialogs and the office email while the PDF, subject and Reportes printed `3-000045` —
  two shapes for one number, in the feature whose premise is a single office format.
- **LOW — claim parameters went into the URL unencoded.** A rotated `DATOS_APP_KEY` containing `+`
  is decoded by PHP's `$_GET` as a space → permanent 401 on the write endpoint only, whose symptom
  ("the counter is never confirmed") is indistinguishable from the endpoint not being deployed. All
  values now percent-encoded.
- **LOW — the blocking claim leaked its dialog on rotation.** `claimExecutor` holds the `Activity`
  for up to connect+read timeout and the listener is skipped once it is destroyed, so nothing
  dismissed "Obteniendo su consecutivo..." → `WindowLeaked`. The activity now holds the dialog in a
  field and dismisses it in `onDestroy`. (The double-spend of a margin block was already guarded by
  the re-check inside the executor task — see §16.)
- **LOW — "blank URL = exactly the pre-R8 behaviour" was not true for the gate.** With
  `SET_CONSECUTIVO_URL` empty (the default from a fresh clone or CI) a fresh install was shown
  "No se pudo obtener su consecutivo del servidor. Conéctese a internet…", blaming a server that
  was never configured. `InventarioSync.claimConfigurado()` now distinguishes "feature off" from
  "claim failed" and goes straight to the manual-seed dialog, which is the pre-R8 path.

### Closed with no code change

**The server seed is read as "last used", so a técnico still at zero skips number 1.** The review
flagged that `getConsecutivoFloor` folds `getSeqServidor` in as a last-used value, so with the live
column seeded `<numero>-000001` the first remisión is `…000002`. That is §12 Q1, already closed:
the stored number **is** the last number used. The consequence is real but it is an office
communication item, not a bug — a series starting at 2 reads as a missing remisión unless the
office is told. Same note as §16's closing item.
