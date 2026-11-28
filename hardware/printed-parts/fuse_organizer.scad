// Blade-fuse organizer block for the TOOLS pocket / field spares bag.
// Standard ATO/ATC fuses stand blade-down in a labeled grid; ~6mm of fuse
// stands proud to grab. PETG, 3 perimeters, 20% infill.

$fn = 24;

rows       = 2;
cols       = 5;
fuse       = [19.6, 5.8];   // ATO body + clearance; use [11.6, 4.4] for Mini fuses
slot_depth = 12;
pitch_x    = 26;
pitch_y    = 14;

block = [cols*pitch_x + 8, rows*pitch_y + 10, slot_depth + 3];

difference() {
    translate([0, 0, block[2]/2]) cube(block, center=true);
    for (r = [0:rows-1], c = [0:cols-1])
        translate([(c - (cols-1)/2) * pitch_x,
                   (r - (rows-1)/2) * pitch_y,
                   block[2] - slot_depth])
            linear_extrude(slot_depth + 1)
                square(fuse, center=true);
}
