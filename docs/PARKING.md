# How Falcon BMS 4.38 numbers parking spots, and which one Ground gives you after landing

Research for the Taxi page (October 2026). Sources: the sim's own code, read statically with the debug symbol file
BMS 4.38 ships beside the program (`Bin\x64\Falcon BMS.pdb`: function names, source files and line numbers; nothing
was run and nothing written into the BMS folder); BMS's manuals (Comms & Nav Book 4.38.1, Training Manual, User
Manual); BMS's radio data (`Data\Sounds\CommFile.xml`, `EvalFile.xml`, `FragFile.xml`, `F4Talk.csv`); every
airfield's taxi network (`ObjectiveRelatedData\OCD_*`, 1,485 fields); and the BMS forum (marked **[web]**). Points
marked *inferred* are deductions, not direct readings of the code. **The in-sim test at the end has still to be run.**

## How BMS numbers a spot — and how the app numbers it now

**BMS's number is a plain count.** Ground speaks a spot's number ("park 0 4") as the count of the parking points
(types 11 and 12) **in storage order** from the network's first point, starting at **00**, the alert cell counted
(read from `ATCBrain::GetParkingPtNbr`, behind call 517).

**Each runway end has its own network and its own numbering.** BMS parks you on the half of the ramp nearest the
runway in use and numbers the ramp separately for each end: at Gunsan 69 spots for runway 18 and 77 for 36, only 12 in
the same place. **After landing, Ground counts in the taxi-in network, which is the reciprocal end's** — the network
whose line-up sits where you roll out (land on Gunsan 36 → runway 18's network). This is strongly supported by the
code but not traced end to end.

**The app numbers spots exactly so (BMS Companion 1.3.8).** `numberParking` in `tools/extractor/src/airfields.mjs`
counts each network's type 11/12 points in storage order from 00, and the app shows the number two-digit (`AfSpot.label`).
On the Taxi page, a take-off chart shows the network of the runway you pick; **Taxi in** draws the network Ground counts
in after landing (`taxiInRoute`/`landingRouteFor` in `TaxiRouting.kt`), with the pill naming the runway you landed on;
the Live taxi VR board and Upd Kneeboard's arrival and alternate charts do the same. `--taxirender` checks Gunsan
landed on 36: the stands at e2028 n1549 and e3928 n3737 are **04** and **28**.

Up to 1.3.7 the app numbered breadth first, ordered by group, which disagreed with Ground at about one spot in five:

| Theater | Spots numbered differently in 1.3.7 | Networks affected |
|---|---|---|
| KTO | 1,638 of 9,099 | 56 of 283 |
| Hellas | 440 of 4,929 | 23 of 234 |
| Balkans | 478 of 7,527 | 30 of 258 |
| Israel | 2,402 of 10,712 | 140 of 354 |

23,875 of the 119,944 spots across the theaters were renumbered; nothing else in the airfield data changed.

**The theaters' shipped parking charts** (`tools/extractor/charts/*.txt`, checked by `node src/apcverify.mjs`,
matched by position): Souda, Tirana and Skopje agree with Ground's count. Araxos 36's chart is numbered breadth first
by group and differs at six spots (12-17), listed in `KNOWN_CHART_NUMBER` — the chart is a drawing; Ground says the
count. The spot's **size** (type 11 small, 12 no limit) and the **alert cell** (`ParkingPointGroup` −1) agree with the
charts as before (103 of 103 alert, 101 of 103 size).

**Three numbers for one spot.** Ground's call counts in the taxi-in network; the HUD's "PK nn" at chocks counts in the
**active take-off runway's** network (Comms & Nav p.14). With 36 active at Gunsan, one spot is "04" in Ground's call and
"PK 35" on the HUD. The app's chart shows the network it draws: Taxi in = Ground's number, a take-off chart = the
active runway's (the HUD's, when the spot is in that network). *To be confirmed in the sim.*

## Which spot Ground gives you, as read from the code

1. **The request.** "Request taxi back" (Ground page, T-4) goes to `ATCBrain::RequestTaxiOff`.
   - If the way back crosses a runway, Ground answers "taxi back … hold short" (call 504).
   - Otherwise `ATCBrain::ManageTaxiOff`.
2. **A booking you already have is repeated.** Calling again gives the same spot.
3. **Otherwise Ground searches** (`DigitalBrain::CanReachParkingPoint`):
   - **From where:** the taxi-network point nearest your jet in a straight line as you call. Only runway, line-up,
     taxi, runway-entry and critical-taxi points count (not parking or runway-exit points). The start point is updated
     continually after touchdown.
   - **Which network:** the taxi-in network (above).
   - **How it walks:** forward only, in storage order: along the current run, then the side runs that branch off the
     start point or later; never back toward the network's root, never into side runs branching off before your
     position. It stops at a point flagged "last" (for an F-16, 12 spots on Gunsan's RWY 36 network lie beyond it and
     are never given).
   - **The first spot that qualifies is given.** A spot qualifies when its type matches the aircraft's park type
     exactly (11 for small aircraft such as the F-16; 12 only when the aircraft data says `large_park: 1` — a fighter
     never gets a type-12 spot), its MaxHeight, MaxWidth and MaxLength each exceed the aircraft's tail height, span
     and length by more than 3 ft, it is not in the alert cell, and it is not occupied (`ObjectiveClass::
     IsParkingPointOccupied`) or booked.
4. **When nothing qualifies,** BMS removes the AI aircraft parked longest on a suitable spot
   (`DespawnTheParkedBlocker`); *inferred:* you still hear "Taxi to the ramp, welcome back" that time.
5. **AI aircraft book their spot when they leave the runway**, which is why wingmen landing before you take the next
   spots.
6. **Not considered:** the spot you took off from, your squadron or group, the nearest spot in a straight line, or the
   shortest taxi.

**In one sentence:** Ground gives the first free spot that fits, in the order of a forward walk of the taxi-in network,
starting from the network point nearest the jet when you call.

**Why calling from the EOR (end-of-runway / de-arm) pad works.** The pad sits beside the network's first stretch, from
the line-up point to the first junction, so the whole ramp is ahead of you and the answer is close to "the lowest free
number that fits". Further along, only the spots ahead count, and when none of them is free you get "welcome back".
*Inferred:* this is also why 4.38 behaves differently from 4.37, since 4.38 keeps moving the start point with the jet.
The manuals agree: the Comms & Nav Book (p.28) and the Training Manual (p.88) send you to the EOR before the call, and
their example gets spot "05"; the Daegu walkthrough (Comms & Nav p.140) calls from the runway exit and is cleared
"without any specific instructions". A pilot's test at Gunsan **[web]** got a spot from the EOR pad and the generic
answer from the taxi lines (<https://forum.falcon-bms.com/topic/30976/no-longer-assigned-to-a-parking-spot-when-coming-back>),
and a crash report a BMS developer explained **[web]** shows the same call chain
(<https://forum.falcon-bms.com/topic/31252/ctd-after-landing-isparkingpointoccupied>).

### Worked example (Gunsan, F-16, empty ramp; positions in field feet, e = east, n = north)

| Landed | Called from | Ground says | Lowest-number rule would say | Nearest-spot rule would say |
|---|---|---|---|---|
| 36 | north EOR | **04** | 04 | — |
| 36 | east ramp taxiway, e4154 n3227 | **28** | 04 | 23 |
| 18 | south EOR | **00** | 00 | — |
| 18 | parallel taxiway, e132 n-842 | **14** (about 2,400 ft away) | 00 | 11 (387 ft away) |

## What Ground says

| Call (`CommFile.xml`) | Words |
|---|---|
| 517 | "<callsign>, Taxi back to the ramp, <up to 4 taxiway letters>, park <3 digits>" — the assigned spot |
| 391 | "<callsign>, Taxi to the ramp, welcome back" — the generic answer |
| 504 | "Taxi back <letters> and hold short <runway>" — the way back crosses a runway |
| 305 | Tower: "taxi clear of the runway" |

The word "park" is recorded only for the pilot voices, not the controllers', so it is probably silent when Ground
speaks: "Alpha, Charlie, 1-4" then means taxiways A and C, spot 14 (inferred from the voice tables). BMS never radios
a turn-by-turn route: the word "taxiway" is never said, so the Taxi page's turn-by-turn wording is the app's own.

**The generic answer comes when:** you called away from the EOR or runway-end area (the main cause in 4.38); no spot
ahead of you that fits is free; or the runway end has no parking in its network (almost never: only 2 runway ends of
849 fields — the Falklands strips La Junra 19 and Chile Chico 31 — and Novo Mesto and Korce have no network at all).

**There is no "magic zone" in the airport data.** No point type or flag marks an EOR or a "call from here" place (the
types: 1 runway end, 2 line-up, 3 taxi, 11/12 parking, 15 runway entry/crossing lane, 21 a point on the runway
centreline where an exit lane meets it), and the EOR pads are not points: at Gunsan nothing lies within 600 ft of
either pad except the first stretch of that runway end's own route, 60-100 ft away. Only 11 KTO fields ship EOR
charts (Cheongju, Daegu, Gangneung, Gunsan, Gwangju, Jungwon, Osan, Sacheon, Seosan, Suwon, Yecheon).

## What the app could show next (not built)

The rule is deterministic, so the app could predict Ground's answer, given which spots are taken:
- **A "Taxi back" card** once on the field below 150 kt: "Call Ground (T-4) from here → park 04; if taken: 05, 06,
  07", the spot highlighted and the route drawn; high confidence when no other traffic landed recently, medium when
  wingmen landed ahead (each probably takes the next spot).
- **Where to call from:** the taxi points shaded where Ground gives a spot and where it gives the generic answer, with
  "call from the EOR" when the jet is in the second.
- **Both numbers on a spot**, e.g. "ATC 04 · HUD PK 35".

It would need, per network point in `af-*.json`, BranchIdx, RootIdx, the "last" flag, the three maximum sizes and the
group; per aircraft, the park type and its three dimensions; and the occupied spots from the Tacview feed. Its limits:
spots AI aircraft booked as they left the runway are invisible (in single-player mostly your wingmen), and Tacview may
not show an AI taxiing out that still holds its spot.

## In-sim test still to run (about ten minutes)

**Set-up:** a Gunsan TE with one F-16, a runway start, no other flights at Gunsan; wind from the north, so 36 is
active.

1. Take off, fly a closed pattern, land on 36 and exit.
2. Stop on the east-ramp taxiway at about e4154 n3227 and press T-4. **Expected:** "park 2 8" ("0 4" would mean
   "lowest free number", "2 3" "nearest spot", and "welcome back" would contradict the rule). The app's **Taxi in**
   chart for 36 shows 28 on that stand.
3. Taxi to the north EOR and press T-4 again. **Expected:** "2 8" again (the booking is kept).
4. Park on the assigned spot and put the chocks in. **Expected:** HUD "RWY 36 … PK 71": that spot is not in the RWY 36
   network, so the HUD names the nearest one, about 1,560 ft away.
5. **Optional second flight:** land on 36, call from the north EOR, park, check the chocks HUD. **Expected:** "0 4"
   from Ground, then "PK 35" on the HUD.
6. Repeat once at a Balkans base, which has no EOR pad: from the runway exit, and from the first stretch of the
   rollout end's network.

If step 2 or 5 disagrees, the numbering (`numberParking`) or the taxi-in network choice (`taxiInRoute`) is what to look
at. The research files (call maps for Gunsan, Osan and Araxos, the disassembly notes, a re-implementation of the walk)
were kept outside the repository.
