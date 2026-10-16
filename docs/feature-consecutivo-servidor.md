# Feature Plan — Consecutivo servidor-autoritativo (R8)

> **Status:** Designed, NOT implemented. **Blocked on the client** for one server-side write
> endpoint (§5) and two semantic answers (§12).
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

## 5. What the client has to build (the only blocking dependency)

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

## 6. App changes

**Wire format: a plain integer sequence (`seq=45`), never a formatted id.** The server displays
`3-000045` in its column, the app formats `03000045` for filenames/PDF/intents (`%02d%06d`), and
neither has to know the other's shape. On the read side, parse the column with the existing
robust rule — **the last 6 digits are the sequence** — after checking the prefix digits match this
técnico's `numero`, so `1-000001` and `01000001` both work and one técnico can never inherit
another's series.

### `Methods` (consecutivo block, ~line 914 onward)

| Member | Behaviour |
|---|---|
| `int getSeqServidor(Map userData)` | Parses `userData.get("consecutivoServidor")`: validates the prefix against `numero`, returns the last-6-digit sequence, or `-1` for blank/absent/malformed. |
| `int getConsecutivoFloor(ctx, userData)` | The §4 `max(...)`, and persists the result. The single place the three inputs meet. |
| `int getSeqConfirmado(ctx, numero)` / `setSeqConfirmado(...)` | The outbox: the highest sequence the server has acknowledged. One int in `app_config.json` under `consecutivosConfirmados`. **Must live in `app_config.json`, not in a `Remision_*` file** — `cleanOldRemisiones` would eat it. |
| `boolean hayConsecutivoPendiente(ctx, numero)` | `getStoredConsecutivo > getSeqConfirmado`. Drives the push. |
| `peekProximoConsecutivo` | Takes the floor from `getConsecutivoFloor` instead of the local-only max. Needs `userData` (or the server seq) passed in — signature change, 3 call sites. |
| `commitConsecutivo` | Unchanged as the local authority; the push is fired *after* it by the caller, never inside it. |
| `isConsecutivoInicializado` | Also true when the server carries a value. This is what retires the manual flow as the normal path. |

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
costs *read* access to employee and client data. With a write endpoint it would additionally allow
moving any técnico's counter. The §5 rules bound the damage to "push a counter forward" (monotonic,
cédula-matched), which creates visible gaps rather than duplicates or data loss — but the owner
should hear this before the client ships it. Ask the client for, in order of value:
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

## 12. Open questions — the two that block coding

1. **What does `3-000001` mean: the last used number, or the next one to use?** Every real row
   carries the same value, so it is plainly a seed and not real data — but the whole series is off
   by one if this is guessed. This plan assumes **last used** (consistent with
   `app_config.json`'s `consecutivos`, where next = value + 1). If the client means "next", the
   server must subtract one when seeding, or `getSeqServidor` must, once, forever.
2. **Does the client accept the write endpoint as specified in §5**, in particular the monotonic
   rule and the cédula check? Everything else in R8 is implementable without them; the duplicate
   elimination is not.

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

## 14. Change map & sequencing

| File | Change |
|---|---|
| `Methods.java` | `getSeqServidor`, `getConsecutivoFloor`, `get/setSeqConfirmado`, `hayConsecutivoPendiente`, `MARGEN_REINSTALACION`; `peekProximoConsecutivo` takes the server seq; `isConsecutivoInicializado` accepts a server value; `validateCredentials` exports `consecutivoServidor`. |
| `InventarioSync.java` | `pushConsecutivoIfPending` (background, no daily guard, silent) and `claimConsecutivoBlocking` (the fresh-install gate only). |
| `MenuActivity.java` | Fire the pending push at login, beside the bodega sync. |
| `IngresarRemisionActivity.java` | Push after `commitConsecutivo`; the reworded fresh-install gate. |
| `CambiarConsecutivoActivity.java` | Wire the claim in after the manual seed, so a reinstatement is complete on day one. The flow stays unreachable. |
| `activity_menu.xml` | `android:visibility="gone"` on `btn_CambiarConsecutivo` (~line 123) — the only UI change in R8. Keep the class, layout, string, manifest entry (`AndroidManifest.xml:65`) and the listener at `MenuActivity.java:99`; see §7. |
| `ActualizarDatos.java` | **No change** — `CONSECUTIVO` stays out of `REQUIRED_HEADERS`. |
| `app/build.gradle.kts`, `local.properties` | `SET_CONSECUTIVO_URL` into `BuildConfig`, same pattern as `DATOS_APP_URL`. |
| (follow-up) `ReportesActivity` | The gap report the client actually wants, now that the office holds a real series. Out of scope for R8. |

**Order of work**, so nothing waits on the client unnecessarily:

1. **Phase 1 — read side, ships without the endpoint.** `getSeqServidor` + the floor + the
   `consecutivoServidor` plumbing + host tests. Already useful on its own: the office can set a
   floor by typing in the column, and a reinstalled phone picks it up. Does **not** retire the
   manual flow on its own (§2: a read-only column cannot).
2. **Client builds §5.** Send them that section verbatim; the two §12 answers come with it.
3. **Phase 2 — write side.** The push, the outbox, the margin, the new gate, the demotion of
   `CambiarConsecutivoActivity`. This is the phase that actually retires the manual flow.

Phase 1 is maybe 60 lines and a test class. Phase 2 is the work, but it has no POI, no file
format and no UI of consequence — it is one GET, one integer of state, and five call sites.
