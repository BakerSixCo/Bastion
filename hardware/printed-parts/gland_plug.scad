// Blanking plug for an unused cable gland — the gland clamps the shaft like
// a wire, keeping the case's IP rating honest with no cable installed.
// Set bore_d in measurements.scad to the wire size the gland is rated to
// clamp (mid-range of its seal). ASA, solid (100% infill), it's tiny.

include <measurements.scad>
$fn = 64;

flange_d = bore_d + 8;
flange_t = 3;
shaft_l  = 25;

cylinder(h = flange_t, d = flange_d);
translate([0, 0, flange_t]) cylinder(h = shaft_l, d = bore_d);
