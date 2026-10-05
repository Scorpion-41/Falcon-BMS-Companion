// What of Weapon Delivery Planner's forms the Planner leaves out, shared by wdplayout.mjs (layouts) and
// wdpimages.mjs (their pictures), so a re-run of either does not bring back what the app no longer opens.

// Whole forms. WDP's Threats and Munition pages: the app's own Threat Guide and Arsenal cover the same ground from
// BMS's own data. The rest are windows nothing in the Planner opens: WDP's main window and its start-up, about,
// settings, registry-key and user-name windows (the Planner is a tab of the app, and 4.38.1 only); the kneeboard
// windows (the app never paints BMS's kneeboard textures); the save timer; and windows WDP itself opens from pages
// the Planner does not have (the map's lines, PPT, parking, side, FED, tool tip, wait). WDP's ATO target list is the
// Planner's own window now (AtoTargetListWindow.kt), drawn in the app's look, not from WDP's layout.
export const DROPPED_FORMS = new Set([
  'cntThreats', 'cntMunition',
  'fclsMain', 'fclsStartPicture', 'fclsAbout', 'fclsSettings', 'fclsRegistryKey', 'fclsUserName',
  'fclsKneeboard', 'fclsKneeboardMulti', 'fclsTimer',
  'fclsLines', 'fclsPPT', 'fclsParking', 'fclsSide', 'fclsFed', 'fclsAtoTargetList', 'fclsToolTip', 'fclsWait',
]);

// Pictures of controls the Planner takes off a page it does keep: the "Mission.ini saved" lamps of a Tactical
// Engagement's second cartridge, on the DTC page and the DataCard (see WdpPage.hidden in the app).
export const REMOVED_ART = /^(cntDTC\.pnl(Stpt|Tgt|Line|Ppt|Open|Hpn)Mission(Green|Red)|cntDataCard\.pnlMission(Green|Red))$/;
