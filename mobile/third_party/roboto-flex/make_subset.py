"""Builds mobile/src/main/res/font/roboto_flex_condensed.ttf (about 44 KB).

Source: Roboto Flex variable TTF from the google/fonts repo, ofl/robotoflex (SIL OFL 1.1, see OFL.txt; the
licence declares no Reserved Font Name, so a renamed subset is allowed). Static instance at wdth 84, wght 680,
opsz 28, every other axis at its default; subset to Basic Latin, Latin-1, Latin Extended-A and the punctuation
Ronde shows. Roboto Flex digits are tabular by default (the font has pnum, no tnum), so figures stay tabular.

    python3 make_subset.py RobotoFlex[...].ttf ../../src/main/res/font/roboto_flex_condensed.ttf
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

src, out = sys.argv[1], sys.argv[2]
f = TTFont(src)
f = instancer.instantiateVariableFont(f, {"wdth": 84, "wght": 680, "opsz": 28, "GRAD": 0, "slnt": 0, "XOPQ": 96, "YOPQ": 79, "XTRA": 468, "YTUC": 712, "YTLC": 514, "YTAS": 750, "YTDE": -203, "YTFI": 738}, updateFontNames=False)
# Name the instance so it does not claim to be the full family.
for r in f["name"].names:
    if r.nameID in (1, 16):
        r.string = "Roboto Flex Condensed"
    elif r.nameID in (2, 17):
        r.string = "Bold"
    elif r.nameID == 4:
        r.string = "Roboto Flex Condensed Bold"
    elif r.nameID == 6:
        r.string = "RobotoFlexCondensed-Bold"
f["OS/2"].usWeightClass = 700
f["OS/2"].usWidthClass = 4
ranges = list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + [
    0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026,
    0x2032, 0x2033, 0x2039, 0x203A, 0x2044, 0x2070, 0x2074, 0x2212, 0x2190, 0x2191, 0x2192, 0x2193,
    0x25B2, 0x25BC, 0x00D7, 0x20AC,
]
opts = subset.Options()
opts.layout_features = ["*"]
opts.name_IDs = ["*"]
opts.name_languages = ["*"]
opts.notdef_outline = True
opts.hinting = False
opts.desubroutinize = True
s = subset.Subsetter(opts)
s.populate(unicodes=ranges)
s.subset(f)
f.save(out)
g = f.getGlyphSet()
cmap = f.getBestCmap()
print("digit advances", sorted({f["hmtx"][cmap[c]][0] for c in range(0x30, 0x3A)}))
print("glyphs", len(f.getGlyphOrder()))
