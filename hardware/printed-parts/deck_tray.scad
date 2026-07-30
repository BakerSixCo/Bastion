// BASTION electronics deck tray
// Replaces the bought deck plate: base plate with a fitted pocket fence per
// device and zip-tie slots so every device is strapped, not just seated.
// Print flat, ASA or PETG, 4 perimeters, 40% infill. Large-bed printer or
// split the tray in two along x and bolt with a splice plate.

include <measurements.scad>
$fn = 32;

// pocket centers on the tray, [x, y] from tray center — rearrange to taste,
// keep fences clear of each other and the tray edge
pos_mini_pc = [-95,  20];
pos_router  = [ 40,  45];
pos_buck1   = [135,  45];
pos_buck2   = [135, -40];
pos_hub     = [ 40, -45];

corner_hole_d = 5;     // M5 bolts or heavy zip ties into the case frame/foam
slot          = [5, 2.5];  // zip-tie slot size

module fence(dev, at) {
    translate([at[0], at[1], deck[2]])
        linear_extrude(fence_h)
            difference() {
                square([dev[0] + 2*(fit+wall), dev[1] + 2*(fit+wall)], center=true);
                square([dev[0] + 2*fit,        dev[1] + 2*fit],        center=true);
            }
}

// axis=0: slots left/right of the pocket; axis=1: slots above/below
module tie_slots(dev, at, axis=0) {
    for (s = [-1, 1])
        translate(axis == 0
            ? [at[0] + s*(dev[0]/2 + fit + wall + 4.5), at[1], -1]
            : [at[0], at[1] + s*(dev[1]/2 + fit + wall + 4.5), -1])
            linear_extrude(deck[2] + 2)
                square(axis == 0 ? slot : [slot[1], slot[0]], center=true);
}

difference() {
    translate([0, 0, deck[2]/2]) cube(deck, center=true);
    for (sx = [-1, 1], sy = [-1, 1])
        translate([sx*(deck[0]/2 - 10), sy*(deck[1]/2 - 10), -1])
            cylinder(h = deck[2] + 2, d = corner_hole_d);
    tie_slots(mini_pc, pos_mini_pc, 0);
    tie_slots(router,  pos_router,  1);
    tie_slots(buck,    pos_buck1,   1);
    tie_slots(buck,    pos_buck2,   1);
    tie_slots(usb_hub, pos_hub,     0);
}

fence(mini_pc, pos_mini_pc);
fence(router,  pos_router);
fence(buck,    pos_buck1);
fence(buck,    pos_buck2);
fence(usb_hub, pos_hub);
