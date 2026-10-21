# Support Roomba 616 – Galaxy S8+ + FT232RL

Support à coller sur le dessus du Roomba 616 : il tient le Galaxy S8+ en paysage, caméra arrière vers l'avant, et il porte la carte USB-série FT232RL. Il y a aussi une poche optionnelle pour une batterie externe.

| Fichier | Contenu |
|---|---|
| `roomba_mount.scad` | Modèle OpenSCAD paramétrique (tous les réglages sont en haut du fichier) |
| `roomba_mount.stl` | Pièce à imprimer (une seule pièce, 170 × 94 × 53 mm) |
| `preview.png`, `top.png` | La pièce seule |
| `assembly.png`, `assembly_front.png`, `assembly_side.png` | Vue d'ensemble avec le téléphone (bleu), l'objectif (rouge), la prise USB-C (noir), la carte FT232 (vert), la batterie (orange) |

## Impression
- **Matériau** : PETG de préférence (il tient mieux à la chaleur et aux chocs), sinon PLA.
- **Couche** de 0,2 mm, **remplissage** de 20 à 25 %, 3 périmètres.
- **Orientation** : la base posée à plat sur le plateau, comme dans le STL.
- **Pas de support.** Seuls les petits ponts au-dessus des fentes de colliers et de sangle s'impriment dans le vide (22 mm au maximum).
- Il faut environ **70 à 75 g** de filament (le volume plein est de 83 cm³).
- La pièce fait 170 × 94 mm et tient sur un plateau de 220 × 220.

## À mesurer avant d'imprimer
1. **La carte FT232** : longueur, largeur et position du connecteur. Réglez `ft_l` et `ft_w`, et si besoin `ft_clear`.
2. **Le téléphone dans sa coque** : si vous gardez une coque, réglez `case_extra` (surépaisseur **par côté**, par exemple 1,0).
3. **Le dessus du Roomba** : vérifiez que la zone devant le bouton CLEAN est assez plate sur environ 170 × 94 mm (de 36 à 130 mm devant le centre) et qu'il n'y a pas de poignée ou de bosse. Sinon, ajustez `base_y0`, `base_depth`, `base_w` et `roomba_usable_r`.
4. Si vous n'utilisez pas de batterie externe, mettez `powerbank = false`. Si la vôtre a une autre taille, réglez `bank_l`, `bank_w` et `bank_h`.

Conseil : imprimez d'abord un essai rapide (par exemple en couche de 0,3 mm) pour vérifier comment le téléphone s'emboîte.

## Fixation sur le Roomba
- Posez le support **devant le bouton CLEAN**, centré, la fente du téléphone vers l'avant. Le bord arrière de la base se trouve à environ 36 mm du centre du bouton.
- Fixez-le avec du **Velcro adhésif** (recommandé) ou de la **VHB 3M**. Utilisez 4 bandes d'environ 25 × 40 mm aux coins, sous une base entièrement plate.
- **Ne collez rien sur le pare-chocs** : il doit pouvoir bouger.
- Le port série mini-DIN est sous le capot du dessus. Le Velcro permet d'enlever le support pour ouvrir ce capot.

## Câblage
- Le **téléphone** se glisse par le haut dans les deux montants. Côté caméra, la lèvre avant ne fait que 14 mm de haut : l'objectif, le flash et le capteur cardiaque restent dégagés. Les appuis du bas sont limités aux 18 mm de chaque extrémité, pour ne pas appuyer sur les boutons de volume, Bixby ou marche.
- **Prise USB-C du téléphone** : rien ne gêne au-dessus de la butée basse de 8 mm, ni sur 20 mm et plus à l'extérieur. Prenez de préférence un câble USB-C vers USB-C **court (15 à 20 cm), coudé à 90°**. Évitez un coude orienté vers le bas.
- La **carte FT232** se place dans le bac de droite, avec l'USB-C vers l'avant (vers le téléphone) et le connecteur 6 broches vers l'arrière. Tenez-la avec **2 colliers** : chaque collier passe sous la carte par les fentes des parois, puis remonte et fait le tour par-dessus.
- Faites passer les **fils Dupont** (TX, RX, GND, et éventuellement BRC) vers l'arrière, jusqu'au mini-DIN. Fixez le câble USB et les fils aux **2 pontets** du côté droit avec des colliers.
- La **batterie** se tient avec 1 ou 2 sangles Velcro de 20 mm. Chaque sangle passe sous la batterie, entre les rails, par les fentes avant et arrière, puis fait le tour par-dessus.

## Paramètres principaux (`roomba_mount.scad`)
| Paramètre | Défaut | Rôle |
|---|---|---|
| `tilt_deg` | 8 | Inclinaison de la caméra vers le sol |
| `phone_lift` | 12 | Hauteur du bas du téléphone au-dessus de la base |
| `camera_side` | -1 | Côté de la caméra vu de derrière : -1 à gauche, +1 à droite |
| `case_extra`, `phone_clear` | 0 / 0,6 | Coque éventuelle et jeu, par côté |
| `rear_lip_h`, `front_lip_h`, `front_lip_h_cam` | 40 / 30 / 14 | Hauteur des lèvres |
| `ledge_len` | 18 | Longueur des appuis bas aux extrémités |
| `end_h_usb`, `end_h_cam` | 8 / 22 | Hauteur des butées d'extrémité |
| `ft_l`, `ft_w`, `ft_clear`, `tray_x0`, `tray_y0` | 52 / 26 / 0,8 | Bac de la carte FT232 |
| `powerbank`, `bank_l/w/h`, `bank_x0` | true, 100 × 65 × 22 | Poche de la batterie |
| `base_y0`, `base_depth`, `base_w`, `roomba_usable_r`, `clean_keepout_r` | 36 / 94 / 170 / 155 / 32 | Position et emprise de la base |
| `anchor_pos` | [[78,50],[78,75]] | Pontets pour colliers |

### Commandes utiles
```
openscad -o roomba_mount.stl roomba_mount.scad
openscad roomba_mount.scad -D 'part="assembly"' -o assembly.png --imgsize=1200,900   # vue d'ensemble avec les fantômes
```
