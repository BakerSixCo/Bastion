// Battery corner spacer — L-blocks that locate the packs in the foam cradle
// so the straps do restraint and the foam does cushioning. Print 4 per
// battery (8 total). ASA, 4 perimeters, 40% infill.
//
// LOCATE, DON'T CONTAIN: printed plastic positions the battery; the straps
// hold it. Never print a battery enclosure — prismatic cells need rigid
// compression and printed plastic creeps under load and softens in car heat.

leg = 60;   // leg length along each battery face
h   = 40;   // block height
t   = 8;    // wall thickness

cube([leg, t, h]);
cube([t, leg, h]);
