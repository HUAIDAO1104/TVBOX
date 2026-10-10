#!/usr/bin/env python3
"""Verify the documented native palette and prevent a mobile-to-TV source alias."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
colors = {e.attrib["name"]: e.text for e in ET.parse(root / "app/src/mobile/res/values/mobile_theme.xml").getroot() if e.tag == "color"}
design = (root / "DESIGN.md").read_text()
for doc, native in {"primary":"accent", "background":"background", "surface":"surface", "outline":"outline", "foreground":"text", "secondary":"muted"}.items():
    match = re.search(r'^  ' + doc + r': "(#[0-9A-Fa-f]+)"$', design, re.M)
    assert match and match.group(1) == colors["mobile_" + native], doc
build = (root / "app/build.gradle").read_text()
assert 'java.setSrcDirs(["src/leanback/java"])' not in build
assert 'res.setSrcDirs(["src/leanback/res"])' not in build
assert 'mobileImplementation libs.androidautosize' not in build
print("PASS: native tokens match DESIGN.md; mobile owns its sources and density")
