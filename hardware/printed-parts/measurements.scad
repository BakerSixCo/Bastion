// BASTION printed parts — shared measurements
// ============================================
// MEASURE YOUR ACTUAL DEVICES WITH CALIPERS and update the PLACEHOLDER
// values before printing anything. Every part file includes this file.
// All dimensions in mm. Device boxes are [length, width, height].

// -- global fit tuning --
fit     = 0.6;   // pocket clearance per side (0.4 tight .. 0.8 loose)
wall    = 3;     // fence/pocket wall thickness
fence_h = 14;    // how tall the pocket fences stand above the tray

// -- devices (PLACEHOLDERS — measure yours) --
mini_pc = [127, 127, 45];   // typical N100 box; measure your unit
router  = [102,  68, 24];   // GL.iNet — measure your model
buck    = [ 66,  46, 26];   // buck-boost converter incl. heatsink
usb_hub = [ 95,  35, 18];   // powered USB hub

// -- deck tray --
deck = [360, 180, 4];       // tray length, width, base thickness
                            // must fit YOUR case interior — measure first

// -- small parts --
pp_cut   = [16.4, 17.0];    // Powerpole PP15/45 pair cutout — verify against
                            // your snapped-together housings before printing
cable_d  = 7;               // cable bundle diameter for cable_clip
bore_d   = 8;               // gland_plug shaft dia — match the wire size your
                            // spare gland is sized to clamp
