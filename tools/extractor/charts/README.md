Parking charts transcribed from the tables BMS's theaters ship in their docs folders
(Data/Add-On Hellas/Docs/01 Hellas Charts/.../<field>_APC_RWY<nn>.png).

Each line is: <spot number> <size letter> <latitude> <longitude>, exactly as the chart prints it.
They are ground truth for spot numbering, and src/apcverify.mjs checks every generated chart against them.

Not every chart agrees with the data this install has — Yenihesir's is drawn for a layout the theater no longer
carries (only 13 of its 25 spots fall within 120 ft of anything in the field's own data, and the error grows
along the chart, which is a redraw rather than an offset). Those are left out on purpose: where a chart and the
field's own points disagree, the points win, because they are what the sim flies.
