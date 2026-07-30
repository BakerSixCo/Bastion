// Anderson Powerpole panel mount (PP15/45 pair)
// Bolts over a rectangular hole in the case wall or a bulkhead plate; the
// snapped-together housing pair slides in from behind, the collar keeps it
// square. Verify pp_cut against your actual housings — brands vary slightly.
// Print flat, ASA, 6 perimeters / 50% infill (it lives at the wall).

include <measurements.scad>
$fn = 48;

plate       = [42, 42, 3];
collar_h    = 8;
collar_wall = 3;
hole_d      = 3.4;   // M3 clearance
hole_inset  = 5;

difference() {
    union() {
        translate([0, 0, plate[2]/2]) cube(plate, center=true);
        translate([0, 0, plate[2]])
            linear_extrude(collar_h)
                difference() {
                    square([pp_cut[0] + 2*collar_wall, pp_cut[1] + 2*collar_wall], center=true);
                    square(pp_cut, center=true);
                }
    }
    translate([0, 0, -1]) linear_extrude(plate[2] + 2) square(pp_cut, center=true);
    for (sx = [-1, 1], sy = [-1, 1])
        translate([sx*(plate[0]/2 - hole_inset), sy*(plate[1]/2 - hole_inset), -1])
            cylinder(h = plate[2] + 2, d = hole_d);
}
