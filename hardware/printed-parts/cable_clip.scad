// Screw-down saddle clip for wire runs in the foam channels and on the deck.
// Set cable_d in measurements.scad to the bundle diameter; print a batch at
// each size you use. PETG, 3 perimeters, 25% infill, prints without support.

include <measurements.scad>
$fn = 48;

w       = 10;    // clip width along the cable
t       = 3;     // wall thickness
ear     = 12;    // screw-ear length each side
screw_d = 3.6;   // M3 clearance

block_w = cable_d + 2*t;
h       = cable_d + t;

difference() {
    linear_extrude(w)
        union() {
            translate([-block_w/2, 0])        square([block_w, h]);
            translate([-block_w/2 - ear, 0])  square([ear, t + 1]);
            translate([ block_w/2, 0])        square([ear, t + 1]);
        }
    translate([0, 0, -1]) linear_extrude(w + 2) circle(d = cable_d);
    for (s = [-1, 1])
        translate([s*(block_w/2 + ear/2), -1, w/2])
            rotate([-90, 0, 0])
                cylinder(h = t + 3, d = screw_d);
}
