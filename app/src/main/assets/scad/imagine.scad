// imagine.scad - print-friendly building blocks for small household parts.
// Units: millimetres. Every part module returns a solid already in a good print
// orientation, resting on z = 0 (no supports needed).
//
// Profile parts (hooks, clips, brackets) are drawn as a 2D side profile in X/Y
// and extruded along Z by their `width`. That prints them on their side, which is
// the strongest orientation for anything that carries load.

$fa = 6;
$fs = 0.5;

CLEARANCE = 0.4;  // extra gap so printed parts slide over real objects

// ------------------------------------------------------------------ 2D helpers

// Thick line through 2D points with rounded joints and ends.
module stroke(points, w = 4, closed = false) {
    n = len(points);
    m = closed ? n : n - 1;
    for (i = [0 : m - 1])
        hull() {
            translate(points[i]) circle(d = w);
            translate(points[(i + 1) % n]) circle(d = w);
        }
}

// Points along an arc, angles in degrees counter-clockwise from +X.
function arc_points(r, a0, a1, c = [0, 0], steps = 18) =
    [for (i = [0 : steps]) let(a = a0 + (a1 - a0) * i / steps) c + r * [cos(a), sin(a)]];

module rounded_square(size, r = 2, center = false) {
    s = is_list(size) ? size : [size, size];
    rr = max(0, min(r, s[0] / 2 - 0.01, s[1] / 2 - 0.01));
    translate(center ? [-s[0] / 2, -s[1] / 2] : [0, 0])
        if (rr <= 0) square(s);
        else offset(r = rr) offset(delta = -rr) square(s);
}

// ------------------------------------------------------------------ 3D helpers

// Box with rounded vertical edges.
module rounded_cube(size, r = 2, center = false) {
    translate(center ? [0, 0, -size[2] / 2] : [0, 0, 0])
        linear_extrude(size[2]) rounded_square([size[0], size[1]], r, center);
}

// Hollow cylinder (pipe), axis along +Z from z = 0.
module tube(d_inner, wall = 2, h = 10) {
    linear_extrude(h) difference() {
        circle(d = d_inner + 2 * wall);
        circle(d = d_inner);
    }
}

// Screw hole cutter, axis along +Z, from z = 0 up to z = h, countersink at the top.
// Use inside difference(). d = screw shank diameter.
module screw_hole(d = 4, h = 10, head_d = 8, countersink = true) {
    translate([0, 0, -0.01]) cylinder(d = d + CLEARANCE, h = h + 0.02);
    if (countersink) {
        cs = (head_d - d) / 2;
        translate([0, 0, h - cs]) cylinder(d1 = d + CLEARANCE, d2 = head_d + CLEARANCE, h = cs + 0.02);
    }
}

// Flat mounting plate lying on the bed (z = 0..t), with countersunk screw holes.
// holes: 0 = none (use double-sided tape / command strips), 1 = centred, 2 = top and bottom.
module mount_plate(w = 30, h = 50, t = 4, holes = 2, hole_d = 4, r = 3) {
    difference() {
        rounded_cube([w, h, t], r);
        if (holes == 1) translate([w / 2, h / 2, 0]) screw_hole(hole_d, t, hole_d * 2);
        if (holes >= 2) for (y = [h * 0.2, h * 0.8]) translate([w / 2, y, 0]) screw_hole(hole_d, t, hole_d * 2);
    }
}

// Extrude a 2D side profile (drawn in X/Y) by `width` along Z.
module profile_part(width = 20) {
    linear_extrude(width) children();
}

// Horizontal screw holes through a vertical back plate of a profile part.
// The plate spans x = 0..t (wall side at x = 0); holes go along X at the given
// heights (profile Y), centred across the part width, countersunk on the front.
module back_plate_holes(t, ys, width, hole_d = 4) {
    for (y = ys)
        translate([0, y, width / 2]) rotate([0, 90, 0]) screw_hole(hole_d, t, hole_d * 2);
}

// ------------------------------------------------------------------ parts

// Wall hook: vertical back plate (screwed or taped to the wall) with a J hook.
// reach = how far the hook sticks out from the wall, lip = height of the hook tip.
module wall_hook(width = 20, plate_h = 50, plate_t = 5, reach = 30, lip = 15, t = 5,
                 holes = 2, hole_d = 4) {
    ys = holes == 1 ? [plate_h * 0.7] : holes >= 2 ? [plate_h * 0.45, plate_h * 0.85] : [];
    difference() {
        profile_part(width) union() {
            rounded_square([plate_t, plate_h], 1);
            stroke([[plate_t / 2, t / 2], [reach, t / 2], [reach + t / 2, t], [reach + t / 2, lip]], t);
        }
        back_plate_holes(plate_t, ys, width, hole_d);
    }
}

// Hook that hangs over the top edge of a door, cabinet door or panel.
// door_t = measured thickness of the door/panel; drop = how far down the far
// side the hook hangs; reach / lip shape the hook at the bottom.
module over_door_hook(door_t = 18, width = 20, t = 4, grip = 25, drop = 50, reach = 25, lip = 15) {
    g = door_t + CLEARANCE;
    x0 = -t / 2;            // inside face of the back leg is at x = 0
    x1 = g + t / 2;         // inside face of the front leg is at x = g
    profile_part(width) translate([t, drop + t]) {
        stroke([[x0, -grip], [x0, t / 2], [x1, t / 2], [x1, -drop]], t);
        stroke([[x1, -drop], [x1 + reach, -drop], [x1 + reach + t / 2, -drop + t / 2], [x1 + reach + t / 2, -drop + lip]], t);
    }
}

// C-clip that slides onto the front edge of a shelf/board, with a hook hanging below.
// board_t = measured board thickness, grip = how deep the clip reaches onto the board.
module shelf_clip_hook(board_t = 18, width = 20, t = 4, grip = 30, drop = 30, reach = 25, lip = 12) {
    g = board_t + CLEARANCE;
    profile_part(width) translate([reach + t * 2, drop + t]) {
        // C around the board edge: board occupies x >= 0, y = 0..g
        stroke([[grip, g + t / 2], [-t / 2, g + t / 2], [-t / 2, -t / 2], [grip, -t / 2]], t);
        // hook hanging below, opening towards -X (the room)
        stroke([[-t / 2, -t / 2], [-t / 2, -drop], [-t / 2 - reach, -drop], [-t / 2 - reach, -drop + lip]], t);
    }
}

// S-hook that hangs on a round rail or rod (closet rod, towel bar, IKEA rail).
module rail_hook(rail_d = 25, width = 15, t = 5, drop = 40, reach = 25, lip = 15) {
    r = rail_d / 2 + CLEARANCE + t / 2;
    profile_part(width) translate([r + t, drop + r + t]) {
        stroke(arc_points(r, -90, 200), t);
        stroke([[0, -r], [0, -r - drop], [reach, -r - drop], [reach, -r - drop + lip]], t);
    }
}

// Wall-mounted ring that holds a cylindrical object (bottle, can, toothbrush, tool handle).
// d = measured object diameter. cup = true adds a floor (with drain hole).
// Screw holes sit in the back plate above the ring so a screwdriver can reach them.
module ring_holder(d = 60, wall = 3, h = 20, plate_t = 4, plate_h = 40, holes = 2, hole_d = 4, cup = false) {
    ri = d / 2 + CLEARANCE;
    ro = ri + wall;
    pw = max(ro * 1.2, 30);
    ph = holes > 0 ? max(plate_h, h + 4 * hole_d) : max(plate_h, h);
    y_back = -ro - plate_t + wall;
    difference() {
        union() {
            cylinder(r = ro, h = h);
            translate([-pw / 2, y_back, 0]) cube([pw, plate_t, ph]);
        }
        translate([0, 0, cup ? 2 : -0.01]) cylinder(r = ri, h = h + 0.02);
        if (cup) translate([0, 0, -0.01]) cylinder(d = min(10, ri), h = 3);
        if (holes > 0) for (i = [0 : holes - 1]) {
            x = holes == 1 ? 0 : (i % 2 == 0 ? -1 : 1) * pw / 4;
            translate([x, y_back, h + (ph - h) / 2]) rotate([-90, 0, 0]) screw_hole(hole_d, plate_t, hole_d * 2);
        }
    }
}

// Clip that holds one or more cables (or a pipe) against a surface.
// cable_d = cable diameter; count = how many side by side; the base is screwed or taped down.
module cable_clip(cable_d = 6, count = 1, width = 10, t = 2, base_t = 3, hole_d = 0) {
    pitch = cable_d + t + 1;
    total = count * pitch + 2 * t;
    difference() {
        profile_part(width) {
            square([total, base_t]);
            for (i = [0 : count - 1]) {
                cx = t + pitch / 2 + i * pitch;
                translate([cx, base_t + cable_d / 2]) difference() {
                    circle(d = cable_d + 2 * t);
                    circle(d = cable_d);
                    // snap-in opening, ~70% of the cable diameter
                    translate([-cable_d * 0.35, 0]) square([cable_d * 0.7, cable_d]);
                }
            }
        }
        if (hole_d > 0)
            translate([total / 2, base_t + 0.01, width / 2]) rotate([90, 0, 0]) cylinder(d = hole_d + CLEARANCE, h = base_t + 0.1);
    }
}

// Open organizer tray with optional grid of dividers. size = outer [x, y, z].
module tray(size = [100, 60, 30], wall = 2, floor_t = 2, r = 4, div = [1, 1]) {
    difference() {
        rounded_cube(size, r);
        translate([wall, wall, floor_t]) rounded_cube([size[0] - 2 * wall, size[1] - 2 * wall, size[2]], max(0.5, r - wall));
    }
    if (div[0] > 1) for (i = [1 : div[0] - 1])
        translate([i * size[0] / div[0] - wall / 2, 0, 0]) cube([wall, size[1], size[2] * 0.85]);
    if (div[1] > 1) for (j = [1 : div[1] - 1])
        translate([0, j * size[1] / div[1] - wall / 2, 0]) cube([size[0], wall, size[2] * 0.85]);
}

// L bracket (e.g. a small shelf support). a = vertical leg, b = horizontal leg.
module l_bracket(a = 40, b = 40, width = 20, t = 4, gusset = true, hole_d = 4) {
    difference() {
        profile_part(width) union() {
            square([t, a]);
            square([b, t]);
            if (gusset) polygon([[t, t], [t + min(a, b) * 0.45, t], [t, t + min(a, b) * 0.45]]);
        }
        if (hole_d > 0) {
            translate([0, a * 0.7, width / 2]) rotate([0, 90, 0]) translate([0, 0, -0.01]) cylinder(d = hole_d + CLEARANCE, h = t + 0.02);
            translate([b * 0.7, 0, width / 2]) rotate([-90, 0, 0]) translate([0, 0, -t - 0.01]) cylinder(d = hole_d + CLEARANCE, h = t + 0.02);
        }
    }
}

// Wedge (door stop, shim, levelling block). Rises from `tip` at the thin end to `height`.
// Printed on its side so the slope comes out smooth. For a door stop make `height`
// clearly larger than the gap under the door and `tip` smaller than it.
module wedge(length = 120, width = 40, height = 25, tip = 2) {
    profile_part(width) polygon([[0, 0], [length, 0], [length, tip], [0, height]]);
}

// Door stop that jams under a door. gap = measured gap between the door bottom and the floor.
// The thin end slides under the door; it tightens well before the thick end.
module door_stop(gap = 14, width = 40, length = 120) {
    wedge(length = length, width = width, height = gap + 12, tip = max(2, gap - 6));
}

// Plug that pushes into the open end of a tube (table or chair leg, curtain rod), with a
// flange that covers the end. outer = tube outside size (square side or round diameter),
// wall = tube wall thickness. Flange down on the bed, plug pointing up.
module tube_plug(outer = 25, wall = 1.5, round = false, depth = 12, flange_t = 3, fit = 0.2) {
    inner = outer - 2 * wall - fit;
    module section(s) {
        if (round) circle(d = s); else rounded_square([s, s], 1, center = true);
    }
    linear_extrude(flange_t) section(outer + 1);
    translate([0, 0, flange_t - 0.01]) difference() {
        linear_extrude(depth, scale = (inner - 0.8) / inner) section(inner);   // slight taper for a tight fit
        translate([0, 0, 1.5]) linear_extrude(depth) section(inner - 4);       // hollow so it can flex
    }
}

// Round (or polygonal) container: cup, pen pot, dish, saucer, plant pot.
// d = inner diameter, h = outer height, sides = 0 for round, 6 for hexagonal, etc.
module cup(d = 60, h = 80, wall = 2, floor_t = 2, sides = 0, drain = 0) {
    fn = sides >= 3 ? sides : 96;
    difference() {
        cylinder(d = d + 2 * wall, h = h, $fn = fn);
        translate([0, 0, floor_t]) cylinder(d = d, h = h, $fn = fn);
        if (drain > 0) translate([0, 0, -0.01]) cylinder(d = drain, h = floor_t + 0.02);
    }
}
