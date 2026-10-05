package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// Falcon BMS's campaign, TE and training saves as the Planner's Open mission window and flight picker see them
// (GET /api/campaign/files, /api/campaign/ato, /api/campaign/flight; docs/PROTOCOL.md, "Planner integration").
//
// Everything is read on the PC, from the files BMS keeps in each theater's campaign folder, and nothing here is
// ever written: the routes only list and read. Every field has a default, so a PC a version ahead or behind never
// breaks decoding. Positions are theater feet, x north and y east, as everywhere in this protocol; times are
// campaign milliseconds (day 1 00:00 = 0) unless a field says it is a file time (ms since 1970, UTC).

/**
 * Every theater's campaign folder and the saves in it, for the Open mission window.
 *
 * [current] is the theater Falcon BMS is set to (the registry's `curTheater`, a theater-definition name), and that
 * theater comes first. [briefing] says which flight the printed briefing is for, so the window can mark the files
 * that hold it. [error] is a sentence when the listing as a whole could not be made (no BMS folder); a theater or a
 * file that could not be read carries its own.
 */
@Serializable
data class CampFiles(
    val theaters: List<CampTheater> = emptyList(),
    val current: String? = null,
    val briefing: CampBriefKey? = null,
    val error: String? = null,
)

/**
 * Which flight the printed briefing (`User/Briefings/briefing.txt`) is for: its callsign ("Cyborg6"), package
 * number and flight number, and when it was printed (file time). A save "holds the briefed flight" when all three
 * agree.
 */
@Serializable
data class CampBriefKey(
    val callsign: String = "",
    val packageId: Int? = null,
    val flightId: Int? = null,
    val printed: Long = 0,
)

/**
 * One theater of `theater.lst` and the saves directly in its campaign folder.
 *
 * [name] is the theater definition's own name, trimmed ("Korea KTO", "Hellas WCP") — the key every other campaign
 * route takes as `theater`. [appTheater] is the app's own theater id for its maps and airfields, when it has one.
 * [folder] is the campaign folder relative to the BMS folder ("Data\\Add-On Hellas WCP\\Campaign"), for the window
 * to show in small type.
 */
@Serializable
data class CampTheater(
    val name: String = "",
    val appTheater: String? = null,
    val folder: String = "",
    /** the theater Falcon BMS is set to */
    val current: Boolean = false,
    val files: List<CampFile> = emptyList(),
    val error: String? = null,
)

/**
 * One save: a `.cam` (campaign), `.tac` (Tactical Engagement) or `.trn` (training mission).
 *
 * [sortTime] is the time the window sorts by by default: the later of [created] and [modified]. A file a friend
 * just copied in keeps its old modified time and gets a new created time, so it still comes out on top.
 *
 * A **start** ([start]) is one of Falcon BMS's campaign starts or templates: it has an `.obj` part, no flights, or a
 * start's name (`Save<n>`, `Te_New*`, `Instant`), so there is nothing to plan in it until BMS has run it; it is listed
 * greyed, cannot be opened and is never written. [stockWhy] is the sentence that says why (null for every other file).
 * A **stock** file ([stock]) ships with Falcon BMS — a start, or a `TE_BMS_*` / `TR_BMS_*` mission — and only decides
 * where the list shows it: a shipped mission opens and saves like the pilot's own, as it does in WDP.
 */
@Serializable
data class CampFile(
    /** the file name with its extension ("Auto Save.cam"); never a path */
    val name: String = "",
    /** one of [CampKind] */
    val kind: String = "",
    /** file times, ms since 1970 */
    val modified: Long = 0,
    val created: Long = 0,
    val sortTime: Long = 0,
    /** bytes */
    val size: Long = 0,
    val start: Boolean = false,
    val stock: Boolean = false,
    val stockWhy: String? = null,
    /** the campaign's own name from its header ("Red Herring"), when it has one */
    val title: String? = null,
    /** the campaign clock the save was made at, campaign ms */
    val clock: Long? = null,
    val flights: Int? = null,
    val packages: Int? = null,
    /** the callsign of the flight with a player slot, when there is one */
    val player: String? = null,
    /** holds the printed briefing's flight (callsign + package number + flight number) */
    val briefed: Boolean = false,
    /** the save's format version (the `.ver` part: 109, 110, …) */
    val version: Int? = null,
    /** why this file could not be read, in a sentence; the rest is then partly empty */
    val error: String? = null,
)

/** The three kinds of save, as [CampFile.kind] names them. */
object CampKind {
    const val CAMPAIGN = "campaign"
    const val TE = "te"
    const val TRAINING = "training"
}

/**
 * Which flight of which save: what the flight picker hands to the Planner, and what Populate from Planner sends so the PC
 * can attach the flight itself. [theater] is a [CampTheater.name], [file] a [CampFile.name], and [flight] the
 * flight's [CampFlightRow.id] ("num/creator").
 */
@Serializable
data class CampRef(
    val theater: String = "",
    val file: String = "",
    val flight: String = "",
)

/**
 * A save's whole air tasking order, for the flight picker: its teams and every package with its flights.
 *
 * A package is listed once, under its [CampPackage.owner]; the picker also shows it under every other team that has
 * a flight in it. [notes] are the sentences the Planner shows about the file (another theater, a start, older than
 * the printed briefing, …).
 */
@Serializable
data class CampAto(
    val theater: String = "",
    val file: String = "",
    /** file time */
    val modified: Long = 0,
    /** campaign ms */
    val clock: Long = 0,
    val version: Int = 0,
    val title: String? = null,
    val teams: List<CampTeam> = emptyList(),
    val packages: List<CampPackage> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** One of the campaign's teams (1-8), and whether it is on the player's side (allied or friendly in the team table). */
@Serializable
data class CampTeam(val n: Int = 0, val name: String = "", val allied: Boolean = false)

/**
 * One package: its number ("Package 7288"), the team that owns it, its mission and take-off time, its target, and
 * its flights. [tanker], [awacs], [jstars] and [ecm] are the [CampFlightRow.id]s of its support flights, when it has
 * them.
 */
@Serializable
data class CampPackage(
    val id: String = "",
    val number: Int = 0,
    /** [CampTeam.n] of the team that owns it */
    val owner: Int = 0,
    val mission: String? = null,
    /** campaign ms */
    val takeoff: Long? = null,
    val target: String? = null,
    val flights: List<CampFlightRow> = emptyList(),
    val tanker: String? = null,
    val awacs: String? = null,
    val jstars: String? = null,
    val ecm: String? = null,
)

/**
 * One flight as a line of the flight picker.
 *
 * [id] is "num/creator", the key [CampRef.flight] carries. [number] is the flight number the briefing prints. [team]
 * is the flight's own team, which may differ from its package's owner. [f16] is false for any other aircraft, which
 * the picker dims (the attack pages and the DTC are for the F-16) but still lets the pilot pick. [target] (added by
 * the PC's reader, for the picker's ATO target list) is the name of what the flight is tasked against: the target of
 * its first waypoint that has one other than its bases, null for a flight with none (a CAP).
 */
@Serializable
data class CampFlightRow(
    val id: String = "",
    val number: Int = 0,
    val callsign: String = "",
    val mission: String = "",
    val task: String? = null,
    val aircraft: String = "",
    val count: Int = 0,
    val squadron: String? = null,
    val base: String? = null,
    val team: Int = 0,
    /** campaign ms */
    val takeoff: Long = 0,
    val tot: Long = 0,
    /** has a player slot */
    val player: Boolean = false,
    /** is the printed briefing's flight */
    val briefed: Boolean = false,
    val f16: Boolean = false,
    val target: String? = null,
)

/**
 * Everything the Planner uses of one flight of a save.
 *
 * [route] is the flight's waypoints from the save (each at its cell's middle). [loadouts] has one entry per aircraft,
 * or one for all when they are the same. [fuelLb] and [laser] are per aircraft. [packageFlights] are the other
 * flights of the package, [support] the tanker and AWACS tracks it is tied to. [missionIni] says whether the mission
 * file beside the save (`<save>.ini`) is for this flight.
 *
 * [briefing] is the same flight made into a [Briefing] on the PC ([Briefing.origin] = "save"), so the Planner and the
 * Mission views need no second model: overview, steerpoint rows, package and ordnance are filled, and so are the
 * texts BMS words from the campaign itself — the situation, the rules of engagement and the emergency procedures of
 * a campaign flight, the situation of a TE (its team's motto), the station or target area — built the way BMS's own
 * briefing scripts build them (docs/PROTOCOL.md). What only a printed briefing has (weather, comm ladder, the threats
 * it picked along the route, TACANs, the pilots' names) stays empty. There is deliberately no TACAN here: the save's
 * value and the briefing's disagree, and the briefing is the one that has been checked.
 * [notes] are the sentences the Planner shows about this flight.
 *
 * [kind] is the save's [CampKind] ("campaign", "te", "training"): a TE's printed briefing has no situation and no
 * rules of engagement, so a page knows not to send the pilot to PRINT for them. [intel] is what WDP's Briefing page
 * lists as the intelligence for this flight, from the whole save (added by the PC in 1.3.8; null from an older PC).
 *
 * [airDefences] are the enemy's air-defence battalions in the save (hostile to the flight's team or at war with it,
 * not destroyed, unit sub-type 1) and [ships] its task forces, each with where it is and whether the flight's side has
 * spotted it ([CampSite.spotted]); the Planner's DTC page and Map page offer the spotted ones as PPTs and steerpoints.
 * Added by the PC in 1.3.8; empty from an older PC. [bullseyeX]/[bullseyeY] are the save header's bullseye (north and
 * east theater feet, read as the mission file's is: [MissionRoute.bullseyeX]), null when the header sets none.
 *
 * [clock] is the campaign clock the save was made at (campaign ms, the header's current time: WDP's DataCard prints it
 * as its **Current Time**), [currentWp] the waypoint the flight is flying to as the save holds it (WDP prints "----"
 * for the take-off fuel of a flight whose take-off is already behind the clock), and [departures] WDP's **Airport
 * Schedule** of the field the flight departs from ([CampDepartures]). Added by the PC in 1.3.8; null from an older PC.
 *
 * [packageRoutes] are the routes of the package's other flights ([packageFlights]), each by its [CampFlightRow.id]: what
 * WDP's Coordination Card reads for every flight of the package (`FillPackages`, `FillCommCardPackages`: take-off, push,
 * target and holding point, with their times and altitudes). Only the waypoint times, altitudes, actions and positions
 * are filled (no targets). [bullseyeName] is what the save header calls its bullseye, as WDP words it (`Bullseye()`:
 * "Bullseye" for the header's name 1, "Rose" otherwise); null when the header sets no bullseye. Both added by the PC in
 * 1.3.8; empty and null from an older PC.
 */
@Serializable
data class CampFlight(
    val row: CampFlightRow = CampFlightRow(),
    val packageNumber: Int = 0,
    val route: List<CampWaypoint> = emptyList(),
    val loadouts: List<CampLoadout> = emptyList(),
    val fuelLb: List<Int> = emptyList(),
    val laser: List<Int> = emptyList(),
    val home: CampPlace? = null,
    val landing: CampPlace? = null,
    val alternate: CampPlace? = null,
    val packageFlights: List<CampFlightRow> = emptyList(),
    val support: List<CampSupport> = emptyList(),
    val missionIni: CampIniCheck? = null,
    val briefing: Briefing? = null,
    val notes: List<String> = emptyList(),
    val kind: String? = null,
    val intel: CampIntel? = null,
    val airDefences: List<CampSite> = emptyList(),
    val ships: List<CampSite> = emptyList(),
    val bullseyeX: Double? = null,
    val bullseyeY: Double? = null,
    val clock: Long? = null,
    val currentWp: Int? = null,
    val departures: CampDepartures? = null,
    val packageRoutes: List<CampPackageRoute> = emptyList(),
    val bullseyeName: String? = null,
    val sideSupport: List<CampSupport> = emptyList(),
    /**
     * The fuel the flight has burnt so far as the save holds it (`fuel_burnt`, lb; 0 before take-off), which WDP's
     * Performance page takes off the block fuel of the flight's own jet (`SetFuel`). Added by the PC in 1.3.8; null
     * from an older PC.
     */
    val fuelBurnt: Int? = null,
)

/** One of the package's other flights' routes ([CampFlight.packageRoutes]): [id] is its [CampFlightRow.id]. */
@Serializable
data class CampPackageRoute(val id: String = "", val route: List<CampWaypoint> = emptyList())

/**
 * WDP's Airport Schedule for a flight of a save (`fclsAptSchedule.CreateAptSchedule`): every flight of the save, of
 * any team, whose first waypoint is tasked against the same place as the planned flight's first waypoint (its
 * departure field; WDP compares the ids' numbers), earliest departure first and in the save's own order among equal
 * times. A flight whose first waypoint has no departure time is not listed, and WDP looks at no more than the first 100.
 * [field] is the field as WDP titles the window ("Departures from Osan AB (RKSO)"): its name with "Airbase",
 * "Highwaystrip" and "Airstrip" taken out.
 */
@Serializable
data class CampDepartures(
    val field: String = "",
    val rows: List<CampDeparture> = emptyList(),
)

/**
 * One line of the Airport Schedule, WDP's six columns: [depart] (campaign ms: the first waypoint's departure, printed
 * "1, 06:46:32"), [aircraft] (the flight's vehicle as the class tables name it), [callsign] ("Satan7"), [squadron] (the
 * squadron's number with its ordinal, "36th"; "" when the flight has none), [packageNumber] (the number the briefing
 * prints) and [mission] (Strings.txt 300 + the flight's mission, "HAVCAP"). [own] marks the planned flight, which WDP
 * underlines.
 */
@Serializable
data class CampDeparture(
    val depart: Long = 0,
    val aircraft: String = "",
    val callsign: String = "",
    val squadron: String = "",
    val packageNumber: Int? = null,
    val mission: String = "",
    val own: Boolean = false,
)

/**
 * An enemy unit of a save the Planner can put in the cartridge: an air-defence battalion or a task force.
 *
 * [system] is what the unit fields as the save's unit table names it (its main vehicle: "SA-2", "SA-6"), which is what
 * the threat reference and the theater's PPT table are matched on; [name] the unit as the briefing names it ("500th Air
 * Defense Battalion"). [x] north and [y] east in theater feet (the unit's cell middle). [spotted]: the flight's side
 * has seen it — the save's spotted bit of the team that **controls** the flight's team (in Korea ROK controls the U.S.
 * team, and the U.S. team's own bit is never set), or of the flight's own team.
 */
@Serializable
data class CampSite(
    val system: String = "",
    val name: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val spotted: Boolean = false,
)

/**
 * The intelligence WDP's Briefing page shows for a flight of a save (`FillMissionBriefing`: `GndThreads`,
 * `AirThreads`, `AirFighterBomberThreads`, `AirBomberThreads`, `AirSupportThreads`, `AirHeloThreads`): the distinct
 * vehicle names of the units that are hostile to the flight's team or at war with it (the save's own team table) and
 * not destroyed, sorted — [ground] the air-defence battalions (unit sub-type 1), and the squadrons by what they fly:
 * [fighters] (sub-type 8), [fighterBombers] (9), [bombers] (3, 6), [support] (5, 7, 10, 11, 13) and [helos] (4, 12, 14).
 * The whole theater, not the route: that is WDP's picture; the threats BMS picks along the route are in its printed
 * briefing.
 */
@Serializable
data class CampIntel(
    val ground: List<String> = emptyList(),
    val fighters: List<String> = emptyList(),
    val fighterBombers: List<String> = emptyList(),
    val bombers: List<String> = emptyList(),
    val support: List<String> = emptyList(),
    val helos: List<String> = emptyList(),
)

/**
 * One waypoint of a flight, as the save holds it.
 *
 * [x] north and [y] east in theater feet (the save's cell plus half a cell). [action] is BMS's waypoint action (the
 * same codes as the cartridge: 1 take-off, 7 land, 17 strike, …) and [desc] its word from the campaign's
 * `Strings.txt` (string 350 + action), which is what the briefing prints. [target] is what the waypoint is tasked
 * against; [designated] is, per seat (1-4), the target that aircraft is given — present from save version 104 on,
 * null for a seat with none.
 */
@Serializable
data class CampWaypoint(
    val n: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val altFt: Double = 0.0,
    val arriveMs: Long = 0,
    val departMs: Long = 0,
    val action: Int = 0,
    val desc: String? = null,
    val routeAction: Int = 0,
    val formation: Int = 0,
    val spacing: Int = 0,
    val target: CampPlace? = null,
    val designated: List<CampPlace?> = emptyList(),
)

/**
 * A place a flight is tied to: an airbase, a target objective or unit, or one building of it. [kind] says which
 * ("airbase", "objective", "unit", "feature"); [campId] is the campaign's own id and [building] the feature index
 * inside an objective. [x]/[y] are theater feet when the place has a position.
 */
@Serializable
data class CampPlace(
    val kind: String = "",
    val campId: Int? = null,
    val name: String? = null,
    val building: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
)

/** One aircraft's stores (or all of them, when the flight carries one loadout), in hardpoint order. */
@Serializable
data class CampLoadout(val stores: List<Store> = emptyList())

/**
 * A tanker or AWACS the flight's package is tied to, and the track it is planned to fly.
 *
 * In [CampFlight.sideSupport] (added by the PC in 1.3.8; empty from an older PC) it is every support flight of the
 * flight's own side in the whole save, as WDP's DataCard lists them (`fclsMain.Tankers`, `Awacs`, `JSTAR`): each
 * tanker (mission 28), AWACS (26) and JSTARS (27) whose team is the flight's side or allied to it, in the save's order.
 * [aircraft] is its vehicle as the class tables name it ("KC-135R", "E-3"), [tacan] the channel the save gives it
 * ("123Y": the tanker's own, which the card turns into the receiver's as WDP does; null when the save has none) and
 * [leg] the index in [track] of the waypoint its station starts at — WDP's: the first tanker (24) or ELINT (20)
 * action; -1 when the route has none. The three are also filled in [CampFlight.support].
 */
@Serializable
data class CampSupport(
    val role: String = "",
    val callsign: String = "",
    val track: List<TrackPoint> = emptyList(),
    val aircraft: String? = null,
    val tacan: String? = null,
    val leg: Int = -1,
)

/**
 * A save's objectives, as WDP's Target Selection window lists them (`fclsTargetSelection.FillObjectives`) for the
 * DataCard's DMPI box: `GET /api/campaign/objectives?theater=&file=` (added by the PC in 1.3.8). [types] are the
 * objective types the list holds, in BMS's order (Strings.txt 501-531: "Airbase", "Airstrip", "Army Base", …).
 */
@Serializable
data class CampObjectives(
    val types: List<String> = emptyList(),
    val objectives: List<CampObjective> = emptyList(),
)

/**
 * One objective of a save's start file: [id] its "num/creator" (what `/api/campaign/features` takes), [campId] the
 * campaign's own number, [name] as BMS names it ("Changbaishan Airport (ZYBS)"), [type] its objective type
 * ([CampObjectives.types]), [x] north and [y] east in theater feet, [elevFt] the terrain there (BMS's height map, as
 * WDP reads it; null when the PC has none), [control] the team holding it as the start file has it ("PRC(5)").
 */
@Serializable
data class CampObjective(
    val id: String = "",
    val campId: Int = 0,
    val name: String = "",
    val type: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val elevFt: Int? = null,
    val control: String = "",
)

/**
 * An objective's buildings (its features: BMS's `ObjectiveRelatedData/OCD_nnnnn/FED_nnnnn.XML`), as WDP's Target
 * Selection and `fclsFed` list them: `GET /api/campaign/features?theater=&file=&objective=<id>` (1.3.8).
 */
@Serializable
data class CampFeatures(
    val objective: String = "",
    val features: List<CampFeature> = emptyList(),
)

/**
 * One building: [n] its index in the objective (the waypoint's `building`, WDP's `TgtBld`), [name] the feature's
 * name ("ZYBS Rwy Sec 19"), [x] north and [y] east in theater feet — the objective's position plus the feature's
 * offset, as WDP places it (`FillPriTarget`) — [elevFt] the terrain there, [value] BMS's worth of it (WDP lists only
 * those above 0 unless Show all is ticked, and words it "Very Low" … "Very High").
 */
@Serializable
data class CampFeature(
    val n: Int = 0,
    val name: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val elevFt: Int? = null,
    val value: Int = 0,
)

/**
 * The mission file beside a save (`<campaign folder>/<save>.ini`), checked against the flight: [matches] when its
 * route is this flight's route. [reason] is the sentence when it is not ("for another flight", "missing").
 */
@Serializable
data class CampIniCheck(
    val file: String = "",
    /** file time */
    val modified: Long = 0,
    val matches: Boolean = false,
    val reason: String? = null,
)

/**
 * WDP's **ATO Target List** of a save (`fclsAtoTargetList`): the targets the flights of the pilot's side are tasked to
 * attack, `GET /api/campaign/atotargets?theater=&file=[&team=]` (1.3.8). [side] is the team the list is for — the one
 * asked for (the team of the flight the Planner has open), else the team of the flight BMS briefed, of a player
 * flight or of the player's squadron; 0 when none is known, and then the list is empty. The side is that team and
 * every team the team controlling it is allied or friendly with ([sideTeams], their names), as WDP's
 * `CheckOwnSideFltToTeam` counts it.
 *
 * [units] are the targets that are units of the save (battalions, brigades, task forces, flights), [objectives] those
 * that are objectives of its campaign start: one row for each flight, sorted by target as WDP opens the window. [clock]
 * is the save's campaign clock (WDP's Current Time): a row whose flight took off before it is airborne, a TOT before
 * it has passed. [notes] are sentences the window shows (a list that could not be made whole, no side).
 */
@Serializable
data class CampAtoTargets(
    val theater: String = "",
    val file: String = "",
    /** campaign ms */
    val clock: Long = 0,
    val side: Int = 0,
    val sideTeams: List<String> = emptyList(),
    val units: List<CampAtoTarget> = emptyList(),
    val objectives: List<CampAtoTarget> = emptyList(),
    val notes: List<String> = emptyList(),
)

/**
 * One row of the ATO Target List: one flight and what it is tasked against — the target of its first waypoint after
 * the first that names one other than a take-off or a landing (WDP's rule).
 *
 * [nr] is the target's place in WDP's own table (the save's units in their order, or the start file's objectives), the
 * list's "Nr". [target] is named as WDP names it ("500th SA-2 AD Battalion", "Pyongyang Airbase"); [x] north and [y]
 * east in theater feet (null when the save does not hold it), so the window prints its latitude and longitude with the
 * Planner's projection. [tot] is the target waypoint's arrival and [takeoff] the first waypoint's departure, both
 * campaign ms. [flightId] is the flight's [CampFlightRow.id]; [team] its team.
 */
@Serializable
data class CampAtoTarget(
    val nr: Int = -1,
    val target: String = "",
    val tot: Long = 0,
    val x: Double? = null,
    val y: Double? = null,
    val packageNumber: Int? = null,
    val flight: String = "",
    val flightId: String = "",
    val aircraft: String = "",
    val mission: String = "",
    val squadron: String = "",
    val airbase: String = "",
    val takeoff: Long = 0,
    val team: Int = 0,
)
