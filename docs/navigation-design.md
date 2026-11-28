# Navigation design (draft for owner sign-off, 2026-11-25)

Goal: one way to get anywhere — road, trail or cross-country — that a tired, cold person can use with one
hand, and that people used to ATAK and Meshtastic find familiar: simple on the surface, deep underneath.

## Principles

1. **One button: Go.** Pick a destination, press Go. BASTION works out the whole trip and how to travel each part.
2. **Everything computed, nothing guessed.** Routes, times, distances and bearings come from map data and
   code, never from the AI (same rule as `nav/Nav.kt`). The AI may only turn spoken words into a destination.
3. **Say what it does not know** on every leg: closures and traffic (roads); rivers, cliffs, deadfall,
   snow (off road); private land and gates (both).
4. **Works with gloves, in the sun, at night**: big targets, few words, a red night mode, landscape too.
5. **Offline, always.** Nothing in navigation touches the network.

## The trip: one route, mixed legs

A trip is a chain of legs, chosen automatically from the vehicle setting (Car, 4x4, On foot):

- **Drive** on the road network (OpenStreetMap roads in the map pack): turn rules, one-way streets, car vs
  4x4 tracks, seasonal/vehicle limits on Forest Service roads (MVUM).
- **Hike** on trails (Forest Service trails in the pack).
- **Cross-country** over the elevation model (`TerrainRoute`, Tobler walking speed), only where no trail goes.

Example card: "Drive 42 mi (58 min) → park at Fourth of July trailhead → hike 3.1 mi trail (1 h 40) →
0.4 mi cross-country (25 min, steep)". The handover point (trailhead, end of road) is chosen by the
router, not the user.

## Choosing where to go

All of these lead to the same Go button:
- long-press the map, or tap a pin;
- a team member (from TAK/Meshtastic positions);
- "Nearest …" (fuel, water, hospital, ranger station) from the pack's places;
- an MGRS grid or lat/lon typed in (ATAK users think in grids);
- a pin received over the mesh (`+Name lat,lon`).

## Navigating

- **Top banner:** the next instruction in big type — "Left onto Main St · 0.3 mi" on roads,
  "Bear 045° NE · 600 m to the creek crossing" off road — with an arrow.
- **Bottom strip:** distance left, ETA, speed; one large **Blocked** button.
- **Blocked:** one tap blocks the road or trail just ahead of you and reroutes at once (today: long-press).
- **Off route:** recalculates automatically from your GPS position; on foot off road it shows the bearing
  back to the route instead of nagging.
- **Alternatives:** up to three, shown as you pick the destination; switch with one tap.
- **Breadcrumbs and "Take me back":** the track you actually travelled, and a one-tap return along it.
- **Voice:** spoken turns using the phone's offline text-to-speech (can be turned off).
- **Screen on** while navigating; works sideways.

## Team (the ATAK/Meshtastic part)

- **Share route:** send the route to the team as a TAK route (ATAK draws it) and as a short mesh text.
- **ETA to team:** optional, low-rate position + ETA over the mesh so others see where you are on the route.
- Mesh use stays opt-in and private-channel by default (existing rules in CLAUDE.md).

## Build order (each step tested on the emulator with real data, then reviewed by a second model)

1. Driving: turn rules, car/4x4, no U-turns — **done 2026-11-25** (unit tests + Boise data).
2. Navigation mode for driving: turn-by-turn banner, live GPS follow, auto-reroute, one-tap Blocked, voice.
3. Mixed trips: drive → trailhead → trail → cross-country as one route; On foot vehicle setting.
4. Breadcrumbs and Take me back.
5. Forest Service road rules (MVUM vehicle classes and season dates) merged onto the road network.
6. Share route / ETA to team over TAK and mesh.
7. State packs: build one, measure size, slim if needed; routes across two packs.
8. Independent review of the routing code (Fable, after the limit resets).
