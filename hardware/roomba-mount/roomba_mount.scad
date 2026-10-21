// =====================================================================
//  Support "coque" pour Roomba 616 : téléphone Galaxy S8+ + carte FT232RL
//  (+ poche optionnelle pour batterie externe)
//
//  Pièce ADD-ON collée sur le dessus du Roomba (VHB ou Velcro), devant
//  le bouton CLEAN, sans déborder sur le pare-chocs.
//
//  Repère : origine = CENTRE du Roomba (bouton CLEAN), Z vers le haut,
//           +Y = AVANT du robot (sens de marche), +X = droite (vu de derrière).
//  Impression : base à plat sur le plateau, sans support.
//
//  Rendu :   part = "mount"    -> pièce imprimable seule (pour l'export STL)
//            part = "assembly" -> pièce + fantômes (téléphone, carte, batterie)
// =====================================================================

part = "mount";            // "mount" | "assembly"

/* ---------------- Roomba 616 ---------------- */
roomba_d          = 340;   // diamètre du robot
roomba_h          = 92;    // hauteur (pour la vue d'ensemble uniquement)
roomba_usable_r   = 155;   // rayon de la zone plate utilisable (sans le pare-chocs)
clean_keepout_r   = 32;    // zone à garder libre autour du bouton CLEAN (centré)

/* ---------------- Base ---------------- */
base_t            = 3;     // épaisseur de la semelle
base_w            = 170;   // largeur (X) avant découpe par le cercle utile
base_y0           = 36;    // bord arrière de la base (distance au centre du robot)
base_depth        = 94;    // profondeur (Y) -> bord avant à base_y0+base_depth
base_corner_r     = 12;    // rayon des coins

/* ---------------- Téléphone (Galaxy S8+) ---------------- */
phone_l           = 159.5; // longueur (horizontale, paysage)
phone_w           = 73.4;  // largeur (verticale, paysage)
phone_t           = 8.1;   // épaisseur
case_extra        = 0;     // surépaisseur de coque, PAR CÔTÉ (0 = sans coque, ex. 1.0)
phone_clear       = 0.6;   // jeu par côté
tilt_deg          = 8;     // inclinaison : caméra vers le BAS (haut du tél. penché vers l'arrière)
phone_lift        = 12;    // hauteur du bord inférieur du tél. au-dessus de la base
camera_side       = -1;    // -1 : caméra côté gauche (vu de derrière), +1 : côté droit
                           //      (le port USB-C est à l'autre extrémité)
front_margin      = 4.5;   // recul de la face avant (dos du tél.) par rapport au bord avant

/* ---------------- Berceau (2 montants d'extrémité) ---------------- */
lip_t             = 2.4;   // épaisseur des lèvres avant/arrière
lip_w             = 6;     // largeur des lèvres (recouvrement du bord du tél.)
end_t             = 2.8;   // épaisseur des butées d'extrémité
ledge_len         = 18;    // longueur des appuis bas à chaque extrémité (évite les boutons)
rear_lip_h        = 40;    // hauteur lèvre côté écran (le tél. s'y appuie)
front_lip_h       = 30;    // hauteur lèvre côté dos, extrémité USB
front_lip_h_cam   = 14;    // hauteur lèvre côté dos, extrémité CAMÉRA (doit rester sous l'objectif)
end_h_cam         = 22;    // hauteur butée extrémité caméra
end_h_usb         = 8;     // hauteur butée extrémité USB (laisse libre la prise USB-C)
buttress_len      = 25;    // longueur des contreforts arrière
buttress_h        = 32;    // hauteur des contreforts

/* ---------------- Carte FT232RL (SEENGREAT SG-232-01C) ---------------- */
ft_l              = 52;    // longueur PCB (à MESURER)
ft_w              = 26;    // largeur PCB (à MESURER)
ft_t              = 1.6;   // épaisseur PCB
ft_comp_h         = 10;    // hauteur max des composants
ft_header_len     = 12;    // dépassement du connecteur 6 broches
ft_clear          = 0.8;   // jeu par côté
tray_wall_t       = 2.4;
tray_wall_h       = 9;     // hauteur des parois au-dessus de la base
tray_rail_h       = 3;     // rails sous la carte (passage du collier + soudures)
tray_x0           = 38;    // bord gauche extérieur du bac (X)
tray_y0           = 40;    // bord arrière du bac (Y)
tray_len          = 56;    // longueur des parois (ouvert aux deux bouts)
tie_w             = 4.5;   // largeur des fentes pour colliers (colliers 2.5-3.6 mm)

/* ---------------- Batterie externe (optionnelle) ---------------- */
powerbank         = true;
bank_l            = 100;   // X
bank_w            = 65;    // Y
bank_h            = 22;
bank_clear        = 1.0;
bank_wall_t       = 2.4;
bank_wall_h       = 12;    // hauteur des parois au-dessus de la base
bank_x0           = -74;   // bord gauche extérieur de la poche
strap_w           = 22;    // largeur des fentes de sangle (Velcro 20 mm)
strap_h           = 4;     // hauteur des fentes de sangle

/* ---------------- Attaches de câbles ---------------- */
anchor_pos        = [[78, 50], [78, 75]];  // positions [X,Y] des pontets pour colliers

$fn = 64;
eps = 0.01;

// ---------------------------------------------------------------------
// Grandeurs dérivées
// ---------------------------------------------------------------------
base_front  = base_y0 + base_depth;
pc          = phone_clear + case_extra;
L           = phone_l + 2*pc;      // longueur intérieure du berceau
W           = phone_w + 2*pc;
T           = phone_t + 2*pc;      // largeur de la fente
phone_front_y = base_front - front_margin - lip_t;   // face intérieure lèvre avant

bank_in_l   = bank_l + 2*bank_clear;
bank_in_w   = bank_w + 2*bank_clear;
bank_out_l  = bank_in_l + 2*bank_wall_t;
bank_out_w  = bank_in_w + 2*bank_wall_t;

tray_in_w   = ft_w + 2*ft_clear;
tray_out_w  = tray_in_w + 2*tray_wall_t;

// ---------------------------------------------------------------------
// Outils
// ---------------------------------------------------------------------
module box(x0, x1, y0, y1, z0, z1) {
    translate([min(x0,x1), min(y0,y1), min(z0,z1)])
        cube([abs(x1-x0), abs(y1-y0), abs(z1-z0)]);
}

// Repère du téléphone : x le long du tél., y=0 face intérieure de la lèvre
// avant (dos du tél.), y<0 vers l'écran, z=0 dessus des appuis bas.
module phone_frame() {
    translate([0, phone_front_y, base_t + phone_lift])
        rotate([tilt_deg, 0, 0]) children();
}

// Tunnel pour collier, toit en pointe (imprimable sans support), axe X
module tie_tunnel(len, w=tie_w, h=2.5) {
    rotate([90,0,90]) translate([0,0,-len/2])
        linear_extrude(len)
            polygon([[-w/2,0],[w/2,0],[w/2,h],[0,h+w/2],[-w/2,h]]);
}

// Pontet pour collier (câble posé dessus/à côté, collier dans le tunnel)
module tie_anchor() {
    difference() {
        translate([-4, -5, 0]) cube([8, 10, base_t + 7]);
        translate([0, 0, base_t]) tie_tunnel(20);
    }
}

// ---------------------------------------------------------------------
// Base
// ---------------------------------------------------------------------
module base_outline() {
    difference() {
        intersection() {
            translate([0, base_y0 + base_depth/2])
                offset(r=base_corner_r) offset(delta=-base_corner_r)
                    square([base_w, base_depth], center=true);
            circle(r=roomba_usable_r, $fn=256);
        }
        circle(r=clean_keepout_r);
    }
}

module base() { linear_extrude(base_t) base_outline(); }

// ---------------------------------------------------------------------
// Berceau téléphone
// ---------------------------------------------------------------------
module post_local(s) {
    is_cam = (s == camera_side);
    xi = s*(L/2 - lip_w);           // bord intérieur des lèvres
    xe = s*(L/2);                   // extrémité du tél.
    xo = s*(L/2 + end_t);           // extérieur de la butée
    xl = s*(L/2 - ledge_len);       // début de l'appui bas
    down = phone_lift + base_t + 12;
    // appui bas (pied jusqu'au plateau)
    box(xl, xo, -T-lip_t, lip_t, -down, 0);
    // butée d'extrémité
    box(xe, xo, -T-lip_t, lip_t, 0, is_cam ? end_h_cam : end_h_usb);
    // lèvre arrière (côté écran)
    box(xi, xo, -T-lip_t, -T, 0, rear_lip_h);
    // lèvre avant (côté dos / caméra)
    box(xi, xo, 0, lip_t, 0, is_cam ? front_lip_h_cam : front_lip_h);
}

module buttress(s) {
    xi = s*(L/2 - lip_w);
    xo = s*(L/2 + end_t);
    hull() {
        phone_frame() box(xi, xo, -T-lip_t, -T-lip_t+0.5, -phone_lift, buttress_h);
        box(xi, xo, phone_front_y - T - lip_t - buttress_len,
                    phone_front_y - T - lip_t, 0, base_t);
    }
}

module cradle() {
    intersection() {
        union() {
            for (s=[-1,1]) { phone_frame() post_local(s); buttress(s); }
        }
        box(-500, 500, -500, 500, 0, 500);
    }
}

// ---------------------------------------------------------------------
// Bac carte FT232 (ouvert aux deux extrémités, 2 colliers)
// ---------------------------------------------------------------------
module ft_tray() {
    x1 = tray_x0 + tray_out_w;
    difference() {
        union() {
            box(tray_x0, tray_x0 + tray_wall_t, tray_y0, tray_y0 + tray_len, 0, base_t + tray_wall_h);
            box(x1 - tray_wall_t, x1,           tray_y0, tray_y0 + tray_len, 0, base_t + tray_wall_h);
            // rails sous la carte
            for (xr=[tray_x0 + tray_wall_t, x1 - tray_wall_t - 3])
                box(xr, xr+3, tray_y0, tray_y0 + tray_len, 0, base_t + tray_rail_h);
        }
        // fentes de colliers à travers les parois, au ras de la base
        for (f=[0.25, 0.75]) {
            yc = tray_y0 + tray_len*f;
            box(tray_x0 - 1, x1 + 1, yc - tie_w/2, yc + tie_w/2, base_t - eps, base_t + tray_rail_h);
        }
    }
}

// ---------------------------------------------------------------------
// Poche batterie externe (sangles Velcro passant sous la batterie)
// ---------------------------------------------------------------------
module bank_pocket() {
    x1 = bank_x0 + bank_out_l;
    y0 = base_y0;
    y1 = base_y0 + bank_out_w;
    cx = (bank_x0 + x1)/2;
    difference() {
        union() {
            difference() {
                box(bank_x0, x1, y0, y1, 0, base_t + bank_wall_h);
                box(bank_x0 + bank_wall_t, x1 - bank_wall_t,
                    y0 + bank_wall_t, y1 - bank_wall_t, base_t, base_t + bank_wall_h + 1);
            }
            // rails le long de Y (la sangle passe entre eux)
            for (xr=[cx, cx - 46, cx + 46])
                box(xr - 1.5, xr + 1.5, y0 + bank_wall_t - eps, y1 - bank_wall_t + eps, 0, base_t + 3);
        }
        // fentes de sangle dans les parois avant et arrière
        for (xs=[cx - 25, cx + 25])
            box(xs - strap_w/2, xs + strap_w/2, y0 - 1, y1 + 1, base_t - eps, base_t + strap_h);
        // la poche ne doit pas mordre la zone du bouton CLEAN
        translate([0,0,-1]) cylinder(r=clean_keepout_r, h=50);
    }
}

// ---------------------------------------------------------------------
// Assemblage de la pièce imprimable
// ---------------------------------------------------------------------
module mount() {
    union() {
        base();
        cradle();
        ft_tray();
        if (powerbank) bank_pocket();
        for (p = anchor_pos) translate([p[0], p[1], 0]) tie_anchor();
    }
}

// ---------------------------------------------------------------------
// Fantômes (NON imprimés)
// ---------------------------------------------------------------------
module ghosts() {
    // téléphone
    color([0.15, 0.35, 0.9, 0.45]) phone_frame()
        translate([-phone_l/2, -pc - phone_t, pc]) cube([phone_l, phone_t, phone_w]);
    // objectif caméra (sur le dos, côté caméra)
    color([1, 0.1, 0.1, 0.9]) phone_frame()
        translate([camera_side*(phone_l/2 - 12), -pc + 0.5, pc + phone_w/2])
            rotate([-90,0,0]) cylinder(d=10, h=3);
    // prise USB-C (côté opposé)
    color([0.2, 0.2, 0.2, 0.7]) phone_frame()
        translate([-camera_side*(phone_l/2 + pc) - (camera_side > 0 ? 20 : 0), -pc - phone_t/2 - 3.5, pc + phone_w/2 - 6])
            cube([20, 7, 12]);
    // carte FT232
    color([0.1, 0.7, 0.3, 0.6])
        translate([tray_x0 + tray_wall_t + ft_clear, tray_y0 + (tray_len - ft_l)/2, base_t + tray_rail_h]) {
            cube([ft_w, ft_l, ft_t]);
            translate([3, 6, ft_t]) cube([ft_w - 6, ft_l - 14, ft_comp_h - ft_t]);
            translate([ft_w/2 - 8, -ft_header_len, 0]) cube([16, ft_header_len, 2.5]);   // connecteur 6 broches
        }
    // batterie
    if (powerbank) color([0.9, 0.6, 0.1, 0.45])
        translate([bank_x0 + bank_wall_t + bank_clear, base_y0 + bank_wall_t + bank_clear, base_t + 3])
            cube([bank_l, bank_w, bank_h]);
    // Roomba (dessus seulement)
    color([0.5, 0.5, 0.5, 0.25]) translate([0,0,-12]) cylinder(d=roomba_d, h=12, $fn=128);
    color([0.9, 0.9, 0.9, 0.6]) translate([0,0,-0.5]) cylinder(r=25, h=0.5);   // bouton CLEAN
}

if (part == "assembly") { color("Gainsboro") mount(); ghosts(); }
else mount();
