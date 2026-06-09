#!/usr/bin/env python3
"""
Compose babric intermediary -> calamus gen2 intermediary mappings for b1.7.3.

Self-contained: downloads both source mapping sets on first run (cached in
tools/.cache/) and writes the result straight into the mod's resources.

Join key: the official (obfuscated) names of the client and server jars.
  babric  tiny v1 : intermediary | glue | server | client
  calamus tiny v2 : intermediary | clientOfficial | serverOfficial

Outputs a compact TSV token map consumed by the RetroConvert runtime remapper:
  c  <babricIntClass>   <calamusIntClass>
  m  <method_N>         <calamusName>
  f  <field_N>          <calamusName>
  M  <babricOwner> <name> <desc> <calamusName>   (owner-aware oddballs)
  F  <babricOwner> <name> <calamusName>          (owner-aware oddballs)

Reverse (calamus -> babric) is a bijection EXCEPT where calamus merged several
babric tokens into one. For those we emit owner-aware reverse rows so the
runtime can disambiguate by the call's owner (which is in calamus space):
  RM <calamusOwner> <calamusName> <calamusDesc> <babricToken>
  RF <calamusName>  <babricToken>                (client-preferred; owner can't split it)
"""
import io
import re
import sys
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
CACHE = TOOLS_DIR / ".cache"
BABRIC = CACHE / "babric-b1.7.3.tiny"
CALAMUS = CACHE / "calamus-gen2-b1.7.3.tiny"
OUT = TOOLS_DIR.parent / "src/main/resources/retroconvert/babric-to-calamus-b1.7.3.tsv"

BABRIC_URL = "https://raw.githubusercontent.com/babric/intermediary/master/mappings/b1.7.3.tiny"
CALAMUS_URL = ("https://maven.ornithemc.net/releases/net/ornithemc/"
               "calamus-intermediary-gen2/b1.7.3/calamus-intermediary-gen2-b1.7.3-v2.jar")

CACHE.mkdir(exist_ok=True)
if not BABRIC.is_file():
    print(f"downloading {BABRIC_URL}")
    BABRIC.write_bytes(urllib.request.urlopen(BABRIC_URL).read())
if not CALAMUS.is_file():
    print(f"downloading {CALAMUS_URL}")
    jar = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(CALAMUS_URL).read()))
    CALAMUS.write_bytes(jar.read("mappings/mappings.tiny"))

# ---------- parse babric tiny v1 ----------
# header: v1  intermediary  glue  server  client
b_classes = []   # (int, glue, server, client)
b_methods = []   # (ownerInt, descInt, int, glue, server, client)
b_fields = []    # (ownerInt, descInt, int, glue, server, client)

with open(BABRIC) as f:
    header = f.readline().rstrip("\n").split("\t")
    assert header[:2] == ["v1", "intermediary"] and header[2:] == ["glue", "server", "client"], header
    for line in f:
        parts = line.rstrip("\n").split("\t")
        kind = parts[0]
        if kind == "CLASS":
            row = parts[1:] + [""] * (4 - len(parts[1:]))
            b_classes.append(tuple(row[:4]))
        elif kind in ("METHOD", "FIELD"):
            owner, desc = parts[1], parts[2]
            names = parts[3:] + [""] * (4 - len(parts[3:]))
            rec = (owner, desc, names[0], names[1], names[2], names[3])
            (b_methods if kind == "METHOD" else b_fields).append(rec)

# babric class name maps: intermediary -> client/server official
# NOTE: babric tiny v1 omits rows for unobfuscated classes (e.g.
# net/minecraft/client/Minecraft), so lookups fall back to identity
# at the use sites below.
b_int2client = {}
b_int2server = {}
b_known = set()
for ci, cg, cs, cc in b_classes:
    b_known.add(ci)
    if cc:
        b_int2client[ci] = cc
    if cs:
        b_int2server[ci] = cs

# ---------- parse calamus tiny v2 ----------
# header: tiny 2 0 intermediary clientOfficial serverOfficial
c_classes = []   # (int, client, server)
c_members = []   # (kind, ownerInt, descInt, int, client, server)

with open(CALAMUS) as f:
    header = f.readline().rstrip("\n").split("\t")
    assert header[3:] == ["intermediary", "clientOfficial", "serverOfficial"], header
    cur = None
    for line in f:
        line = line.rstrip("\n")
        if line.startswith("c\t"):
            parts = line.split("\t")[1:]
            parts += [""] * (3 - len(parts))
            cur = tuple(parts[:3])
            c_classes.append(cur)
        elif line.startswith("\tm\t") or line.startswith("\tf\t"):
            parts = line.split("\t")
            kind = parts[1]
            desc = parts[2]
            names = parts[3:] + [""] * (3 - len(parts[3:]))
            c_members.append((kind, cur[0], desc, names[0], names[1], names[2]))

c_int2client = {}
c_int2server = {}
c_client2int = {}
c_server2int = {}
for ci, cc, cs in c_classes:
    if cc:
        c_int2client[ci] = cc
        c_client2int[cc] = ci
    if cs:
        c_int2server[ci] = cs
        c_server2int[cs] = ci

# ---------- class map: babricInt -> calamusInt ----------
class_map = {}
class_conflicts = []
unmatched_classes = []
for ci, cg, cs, cc in b_classes:
    via_client = c_client2int.get(cc) if cc else None
    via_server = c_server2int.get(cs) if cs else None
    target = None
    if via_client and via_server and via_client != via_server:
        class_conflicts.append((ci, via_client, via_server))
        target = via_client
    else:
        target = via_client or via_server
    if target is None:
        if ci.startswith("net/minecraft/"):
            unmatched_classes.append(ci)
        continue
    if ci != target:
        class_map[ci] = target

# descriptor remapper
desc_cls_re = re.compile(r"L([^;]+);")

def remap_desc(desc, cmap):
    return desc_cls_re.sub(lambda m: "L" + cmap.get(m.group(1), m.group(1)) + ";", desc)

# ---------- member join via official namespaces ----------
# calamus member lookup keyed by official triple
c_meth_client = {}
c_meth_server = {}
c_fld_client = {}
c_fld_server = {}
for kind, owner_int, desc_int, n_int, n_c, n_s in c_members:
    if kind == "m":
        if n_c:
            oc = c_int2client.get(owner_int)
            if oc:
                c_meth_client[(oc, n_c, remap_desc(desc_int, c_int2client))] = n_int
        if n_s:
            os_ = c_int2server.get(owner_int)
            if os_:
                c_meth_server[(os_, n_s, remap_desc(desc_int, c_int2server))] = n_int
    else:
        if n_c:
            oc = c_int2client.get(owner_int)
            if oc:
                c_fld_client[(oc, n_c)] = n_int
        if n_s:
            os_ = c_int2server.get(owner_int)
            if os_:
                c_fld_server[(os_, n_s)] = n_int

method_token_re = re.compile(r"^method_\d+$")
field_token_re = re.compile(r"^field_\d+$")

method_map = {}          # token -> calamus name
field_map = {}           # token -> calamus name
odd_methods = []         # (ownerInt, nameInt, descInt, calamusName)
odd_fields = []          # (ownerInt, nameInt, calamusName)
m_token_recs = []        # (ownerInt, descInt, token, calamusName, clientName) for token methods
f_token_recs = []        # (ownerInt, token, calamusName, clientName) for token fields
method_conflicts = []
field_conflicts = []
unmatched_members = []

for owner_int, desc_int, n_int, n_g, n_s, n_c in b_methods:
    target = None
    if n_c:
        oc = b_int2client.get(owner_int, owner_int)
        target = c_meth_client.get((oc, n_c, remap_desc(desc_int, b_int2client)))
    if target is None and n_s:
        os_ = b_int2server.get(owner_int, owner_int)
        target = c_meth_server.get((os_, n_s, remap_desc(desc_int, b_int2server)))
    if target is None:
        unmatched_members.append(("M", owner_int, n_int, desc_int))
        continue
    if method_token_re.match(n_int):
        prev = method_map.get(n_int)
        if prev and prev != target:
            method_conflicts.append((n_int, prev, target))
        method_map[n_int] = target
        m_token_recs.append((owner_int, desc_int, n_int, target, n_c))
    elif n_int != target:
        odd_methods.append((owner_int, n_int, desc_int, target))

for owner_int, desc_int, n_int, n_g, n_s, n_c in b_fields:
    target = None
    if n_c:
        oc = b_int2client.get(owner_int, owner_int)
        target = c_fld_client.get((oc, n_c))
    if target is None and n_s:
        os_ = b_int2server.get(owner_int, owner_int)
        target = c_fld_server.get((os_, n_s))
    if target is None:
        unmatched_members.append(("F", owner_int, n_int, desc_int))
        continue
    if field_token_re.match(n_int):
        prev = field_map.get(n_int)
        if prev and prev != target:
            field_conflicts.append((n_int, prev, target))
        field_map[n_int] = target
        f_token_recs.append((owner_int, n_int, target, n_c))
    elif n_int != target:
        odd_fields.append((owner_int, n_int, target))

# ---------- reverse-disambiguation rows for many-to-one (calamus merged) tokens ----------
# A calamus token that several babric tokens map to is ambiguous on the way back.
# Methods split by owner: emit owner-aware reverse rows in calamus space.
m_target_count = defaultdict(int)
for _o, _d, _t, tgt, _c in m_token_recs:
    m_target_count[tgt] += 1
rev_methods = []   # (calamusOwner, calamusName, calamusDesc, babricToken)
for owner_int, desc_int, token, target, n_c in m_token_recs:
    if m_target_count[target] > 1:
        c_owner = class_map.get(owner_int, owner_int)
        c_desc = remap_desc(desc_int, class_map)
        rev_methods.append((c_owner, target, c_desc, token))

# Fields collide on the same owner (a client/server pair), so owner can't split
# them; prefer the client-side babric token (client mods are what we target).
f_target_recs = defaultdict(list)
for owner_int, token, target, n_c in f_token_recs:
    f_target_recs[target].append((token, n_c))
rev_fields = []    # (calamusName, babricToken)
for target, recs in f_target_recs.items():
    if len(recs) > 1:
        client = [t for t, nc in recs if nc]
        chosen = client[-1] if client else recs[-1][0]
        rev_fields.append((target, chosen))

# ---------- write output ----------
with open(OUT, "w") as f:
    f.write("# babric intermediary -> calamus gen2 intermediary (b1.7.3)\n")
    for k in sorted(class_map):
        f.write(f"c\t{k}\t{class_map[k]}\n")
    for k in sorted(method_map, key=lambda s: int(s.split("_")[1])):
        f.write(f"m\t{k}\t{method_map[k]}\n")
    for k in sorted(field_map, key=lambda s: int(s.split("_")[1])):
        f.write(f"f\t{k}\t{field_map[k]}\n")
    for owner, name, desc, target in sorted(odd_methods):
        f.write(f"M\t{owner}\t{name}\t{desc}\t{target}\n")
    for owner, name, target in sorted(odd_fields):
        f.write(f"F\t{owner}\t{name}\t{target}\n")
    for c_owner, c_name, c_desc, token in sorted(rev_methods):
        f.write(f"RM\t{c_owner}\t{c_name}\t{c_desc}\t{token}\n")
    for c_name, token in sorted(rev_fields):
        f.write(f"RF\t{c_name}\t{token}\n")

print(f"classes mapped:     {len(class_map)}")
print(f"method tokens:      {len(method_map)}")
print(f"field tokens:       {len(field_map)}")
print(f"odd methods (owner-aware, non-token, renamed): {len(odd_methods)}")
print(f"odd fields  (owner-aware, non-token, renamed): {len(odd_fields)}")
print(f"reverse-disambiguation method rows (RM): {len(rev_methods)}")
print(f"reverse-disambiguation field rows  (RF): {len(rev_fields)}")
print(f"class conflicts (client/server join disagree): {len(class_conflicts)}")
print(f"method token conflicts: {len(method_conflicts)}")
print(f"field token conflicts:  {len(field_conflicts)}")
print(f"unmatched net/minecraft classes: {len(unmatched_classes)}")
print(f"unmatched members: {len(unmatched_members)}")
for c in class_conflicts[:10]:
    print("  class conflict:", c)
for c in method_conflicts[:10]:
    print("  method conflict:", c)
for c in field_conflicts[:10]:
    print("  field conflict:", c)
for c in unmatched_classes[:10]:
    print("  unmatched class:", c)
for c in unmatched_members[:15]:
    print("  unmatched member:", c)
for c in odd_methods[:15]:
    print("  odd method:", c)
for c in odd_fields[:15]:
    print("  odd field:", c)
