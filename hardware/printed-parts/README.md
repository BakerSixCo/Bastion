# BASTION Printed Parts

Parametric OpenSCAD sources for everything on the build worth printing. One shared
config (`measurements.scad`) drives every part — **measure your actual devices with
calipers, edit that one file, and re-export.** The `stl/` folder holds STLs rendered
from the placeholder dimensions: fine for the dimension-independent parts (spanner,
organizer, spacers), **previews only** for anything fitted (deck tray, Powerpole
mount, clips) — re-export those after you set real numbers.

## Workflow

1. Open `measurements.scad`, replace every PLACEHOLDER with caliper measurements.
2. Preview a part in OpenSCAD (F5), check the pockets against the real device.
3. Export STL: F6 then Export, or CLI: `openscad -o stl/deck_tray.stl deck_tray.scad`
4. Slice and print per the table below.

## Parts

| File | Part | Replaces on the build sheet | Material | Settings |
|---|---|---|---|---|
| `deck_tray.scad` | Electronics deck with fitted pocket fences + zip-tie slots | Deck plate (~$20) and most of the VHB/velcro spend | ASA (PETG ok) | 4 perimeters, 40% infill |
| `battery_spacer.scad` | Corner L-blocks locating the packs in the cradle (8×) | Battery cradle (~$20) | ASA | 4 perimeters, 40% |
| `powerpole_mount.scad` | Panel mount for the EXT Powerpole pair | Bought housing (~$12) | ASA | 6 perimeters, 50% |
| `cable_clip.scad` | Screw-down saddle clips, print per bundle size | Zip-tie mounts (~$8) | PETG | 3 perimeters, 25% |
| `mc4_spanner.scad` | Solar connector wrench (print 2) | Nothing — buy this nowhere | PETG/ASA | 5 perimeters, 40% |
| `fuse_organizer.scad` | Blade-fuse block for the spares bag | Nothing — organization | PETG | 3 perimeters, 20% |
| `gland_plug.scad` | Blanking plug for unused glands | Nothing — keeps IP rating honest | ASA | solid |

Printed-parts savings against the sheet: roughly **$60**, plus fit quality money
can't buy.

## Material rules (this box lives in heat and cold)

- **No PLA anywhere.** It creeps under constant load and deforms around 50°C — a
  parked car in summer exceeds that. §6 of the guide is explicit about vehicle heat.
- **ASA** for anything structural, near the walls, or that might see a vehicle or
  sun. **PETG** acceptable for interior organizers.
- Structural parts: more perimeters beats more infill.

## The two safety rules

1. **Printed parts locate, straps and fasteners restrain.** A fence keeps a device
   from sliding; it does not survive a drop as the only restraint. The zip-tie slots
   and battery straps are the restraint.
2. **Never print a battery enclosure or compression fixture.** Prismatic LiFePO4
   cells need rigid, sustained compression; thermoplastic creeps under exactly that
   load and softens in exactly the heat a deployed case sees. Batteries stay in
   their manufacturer cases; printed spacers only ever position them.
