// MC4 spanner — the plastic wrench that unlocks solar connectors.
// Two open ends: coupling-nut flats and locknut flats. Measure your actual
// connectors across the flats and set slot_a/slot_b (brands differ a little).
// Print flat, PETG/ASA, 5 perimeters, 40% infill. Print two; give one away.

$fn = 48;

len        = 150;
wid        = 32;
th         = 4;
slot_a     = 15.4;   // MC4 coupling nut across flats — verify
slot_b     = 12.2;   // MC4 locknut across flats — verify
slot_depth = 24;

linear_extrude(th)
    difference() {
        hull() {
            translate([wid/2, 0])       circle(d = wid);
            translate([len - wid/2, 0]) circle(d = wid);
        }
        translate([-1, -slot_a/2])               square([slot_depth + 1, slot_a]);
        translate([len - slot_depth, -slot_b/2]) square([slot_depth + 1, slot_b]);
        for (x = [55, 75, 95]) translate([x, 0]) circle(d = 8);  // grip + lanyard
    }
