#!/usr/bin/env python3
"""Build (or regenerate) a UMB legacy-mod sound resource pack.

Universal: works for ANY 1.7.10 mod namespace, never mod-specific. Two modes:

1. sounds.json mode (e.g. HBM, Chisel, Railcraft): the mod ships
   assets/<ns>/sounds.json plus assets/<ns>/sounds/*.ogg. Every event key and
   every sound file path is lowercased (matching LegacyFx's
   ``name.toLowerCase(Locale.ROOT)`` rule; Python ``str.lower()`` is identical
   for the ASCII ids resource locations allow), and every file-type ``name``
   without a ``:`` is prefixed with ``<ns>:`` so modern Minecraft resolves it
   to ``assets/<ns>/sounds/...`` instead of ``minecraft:sounds/...`` (an
   un-namespaced name defaults to the ``minecraft`` namespace, which is why
   every mod sound was silent: 510 hbm + 68 mcheli warnings in latest.log).

2. ogg-scan mode (e.g. MC Heli): the mod ships oggs but no sounds.json (MC Heli
   registers sounds at runtime via MCH_SoundsJson). One event is synthesized
   per ogg file, ``{"category": <default>, "sounds": ["<ns>:<path>"]}``.

If the mod ships neither, an explicit absence pack is written (pack.mcmeta
only, as for IronChest) so the absence is recorded, not silent.

1.7.10 sounds.json forms handled generically: ``"sounds"`` may be a bare
string, a list of strings, or a list of ``{"name":..., "stream":...}`` objects
(plus any other per-sound keys, which are preserved byte-indifferently); all
other per-event keys (``category``, ``subtitle``, ``replace``, ...) pass
through untouched. Entries with ``"type": "event"`` are event redirects, not
file references: their name is namespaced but no ogg is required.

Safety: staging-then-swap. The pack is built fully under ``<pack>.staging``;
only after the build AND the offline self-check pass is the live pack rotated
(``<pack>`` -> ``<pack>.prev``, staging -> ``<pack>``, drop ``.prev``). A
failed run never touches the live pack. The existing ``pack.mcmeta`` is
preserved byte-for-byte; one is created only when absent.

JSON is written UTF-8 with NO BOM and CRLF line endings (the convention of the
existing packs; a repo-wide audit requires BOM-free generated JSON).

Usage:
  python tools/build-sound-pack.py --namespace hbm \
      --assets research/out/legacy/hbm-assets/assets/hbm \
      --pack research/out/legacy/packs/hbm-sounds \
      --expect-missing weapon/grenadebounce2
  python tools/build-sound-pack.py --check --pack research/out/legacy/packs/hbm-sounds
"""

import argparse
import json
import os
import shutil
import sys

PACK_FORMAT = 88  # matches hbm-generated / existing *-sounds packs on 26.2


def die(msg):
    print("build-sound-pack: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def load_json(path):
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        return json.load(f)


def write_json(path, obj):
    # No BOM (encoding="utf-8", never utf-8-sig), CRLF to match pack convention.
    with open(path, "w", encoding="utf-8", newline="\r\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\r\n")


def index_tree_lower(root):
    """Map lowercased forward-slash relpath -> real path for every file."""
    out = {}
    if not os.path.isdir(root):
        return out
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            out.setdefault(rel.lower(), full)
    return out


def namespace_name(ns, raw):
    """Lowercase + prefix with ns: unless already namespaced (generic rule)."""
    low = raw.lower()
    if ":" in low:
        return low
    return ns + ":" + low


def rewrite_event(ns, key, entry):
    """Return (new_entry, file_refs, event_refs). Never fakes: refs only listed."""
    if not isinstance(entry, dict):
        die("event %r is not an object" % (key,))
    new_entry = {}
    for k, v in entry.items():
        if k != "sounds":
            new_entry[k] = v
    sounds = entry.get("sounds", [])
    if isinstance(sounds, str):
        sounds = [sounds]
    new_sounds = []
    file_refs = []
    event_refs = []
    for s in sounds:
        if isinstance(s, str):
            new_sounds.append(namespace_name(ns, s))
            file_refs.append(s.lower())
        elif isinstance(s, dict) and "name" in s:
            ns2 = dict(s)
            ns2["name"] = namespace_name(ns, str(s["name"]))
            new_sounds.append(ns2)
            if str(s.get("type", "sound")) == "event":
                event_refs.append(ns2["name"])
            else:
                file_refs.append(str(s["name"]).lower())
        else:
            die("event %r has an unparsable sound entry: %r" % (key, s))
    new_entry["sounds"] = new_sounds
    return new_entry, file_refs, event_refs


def build(args):
    ns = args.namespace
    if not ns or ns != ns.lower() or ":" in ns:
        die("namespace must be a lowercase id without ':'")

    src_sounds_json = os.path.join(args.assets, "sounds.json")
    src_sounds_dir = os.path.join(args.assets, "sounds")
    src_index = index_tree_lower(src_sounds_dir)

    staging = args.pack + ".staging"
    if os.path.exists(staging):
        shutil.rmtree(staging)
    st_assets_ns = os.path.join(staging, "assets", ns)
    st_sounds_dir = os.path.join(st_assets_ns, "sounds")
    os.makedirs(st_sounds_dir, exist_ok=True)

    file_refs_all = []
    event_refs_all = []
    if os.path.isfile(src_sounds_json):
        original = load_json(src_sounds_json)
        rebuilt = {}
        for key, entry in original.items():
            new_entry, frefs, erefs = rewrite_event(ns, key, entry)
            low_key = key.lower()
            if low_key in rebuilt:
                die("lowercase key collision: %r" % (key,))
            rebuilt[low_key] = new_entry
            file_refs_all.extend(frefs)
            event_refs_all.extend(erefs)
        mode = "sounds.json"
        n_events = len(rebuilt)
        write_json(os.path.join(st_assets_ns, "sounds.json"), rebuilt)
    elif src_index:
        rebuilt = {}
        for rel_low in sorted(src_index):
            if not rel_low.endswith(".ogg"):
                continue
            stem = rel_low[:-4]
            rebuilt[stem] = {"category": args.default_category,
                             "sounds": [ns + ":" + stem]}
            file_refs_all.append(stem)
        mode = "ogg-scan"
        n_events = len(rebuilt)
        write_json(os.path.join(st_assets_ns, "sounds.json"), rebuilt)
    else:
        mode = "absence"
        n_events = 0

    # Copy every referenced ogg (case-insensitive lookup in the source tree).
    distinct_refs = sorted(set(file_refs_all))
    copied = 0
    missing = []
    for ref in distinct_refs:
        want = ref + ".ogg"
        src = src_index.get(want)
        if src is None:
            missing.append(ref)  # the MOD's own bug: counted, never papered over
            continue
        dst = os.path.join(st_sounds_dir, *ref.split("/")) + ".ogg"
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        copied += 1

    # Event-type redirects must resolve locally when un-namespaced; a namespaced
    # target outside this pack is the other pack's business (count, don't fail).
    event_keys = set(rebuilt.keys()) if mode == "sounds.json" else set()
    dangling = sorted(e for e in set(event_refs_all)
                      if ":" not in e and e.lower() not in event_keys)
    if dangling:
        shutil.rmtree(staging, ignore_errors=True)
        die("event redirects with no local target: %s" % dangling)

    unreferenced = sorted(set(k for k in src_index if k.endswith(".ogg"))
                          - set(r + ".ogg" for r in distinct_refs))
    expect = set(e.lower() for e in (args.expect_missing or []))
    unexpected = [m for m in missing if m not in expect]
    if unexpected:
        shutil.rmtree(staging, ignore_errors=True)
        die("refs missing from mod's own tree (not in --expect-missing): %s"
            % unexpected)

    # pack.mcmeta: preserve the live one byte-for-byte; create only if absent.
    live_mcmeta = os.path.join(args.pack, "pack.mcmeta")
    if os.path.isfile(live_mcmeta):
        with open(live_mcmeta, "rb") as f:
            mcmeta_bytes = f.read()
    else:
        if mode == "absence":
            desc = "UMB sound pack - %s has no legacy sound assets" % ns
        elif mode == "ogg-scan":
            desc = ("UMB sound pack - %s 1.7.10 sounds "
                    "(namespaced ids) on Minecraft 26.2" % ns)
        else:
            desc = ("UMB sound pack - %s 1.7.10 sounds "
                    "(lowercased ids) on Minecraft 26.2" % ns)
        mcmeta_bytes = json.dumps(
            {"pack": {"description": desc,
                      "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}},
            indent=2).encode("utf-8") + b"\n"
    with open(os.path.join(staging, "pack.mcmeta"), "wb") as f:
        f.write(mcmeta_bytes)

    # Offline self-check against the STAGED tree before touching the live pack.
    # Refs in --expect-missing are the ORIGINAL mod's own dangles: carried
    # honestly in sounds.json, resolved-counted separately, never a failure.
    if mode != "absence":
        ok, report = check_tree(st_assets_ns, ns, expect)
        print(report)
        if not ok:
            shutil.rmtree(staging, ignore_errors=True)
            die("staged pack failed self-check; live pack untouched")

    # Swap: live -> .prev, staging -> live, drop .prev. No partial states.
    prev = args.pack + ".prev"
    if os.path.exists(prev):
        shutil.rmtree(prev)
    if os.path.isdir(args.pack):
        os.rename(args.pack, prev)
    os.rename(staging, args.pack)
    if os.path.exists(prev):
        shutil.rmtree(prev)

    print("mode=%s events=%d total_refs=%d distinct_refs=%d/%d copied=%d "
          "missing_own_bug=%d unreferenced_left=%d"
          % (mode, n_events, len(file_refs_all), len(distinct_refs),
             len(distinct_refs), copied, len(missing), len(unreferenced)))
    if missing:
        print("missing (mod's own sounds.json dangles, carried honestly): %s"
              % missing)
    print("OK pack=%s" % args.pack)


def check_tree(assets_ns_dir, ns, expect_missing=frozenset()):
    """Validate assets/<ns>/sounds.json against assets/<ns>/sounds/. offline.

    Returns (ok, report). Every referenced file is resolved; the denominator
    is referenced files. Refs in expect_missing (lowercased paths without
    extension, verified absent from the ORIGINAL mod's own tree) are reported
    as the mod's own bug and do not fail the check. Anything else missing, or
    any un-namespaced file ref (which 26.2 resolves to minecraft:sounds),
    fails.
    """
    sj = os.path.join(assets_ns_dir, "sounds.json")
    sdir = os.path.join(assets_ns_dir, "sounds")
    sj = os.path.join(assets_ns_dir, "sounds.json")
    sdir = os.path.join(assets_ns_dir, "sounds")
    if not os.path.isfile(sj):
        return True, "absence pack: no sounds.json, nothing to resolve"
    with open(sj, "r", encoding="utf-8-sig", newline="") as f:
        raw_head = f.read(1)
    if raw_head == "\ufeff":
        return False, "sounds.json has a UTF-8 BOM"
    data = load_json(sj)
    pack_index = index_tree_lower(sdir)
    total = 0
    resolved = 0
    missing = []
    own_bug = []
    for ev, entry in data.items():
        sounds = entry.get("sounds", [])
        if isinstance(sounds, str):
            sounds = [sounds]
        for s in sounds:
            name = s if isinstance(s, str) else s.get("name", "")
            if isinstance(s, dict) and str(s.get("type", "sound")) == "event":
                continue  # event redirect, not a file
            total += 1
            if ":" in name:
                _pns, _colon, path = name.partition(":")
            else:
                path = name  # un-namespaced: resolves to minecraft:sounds (BUG)
            path = path.lower()
            if (path + ".ogg") in pack_index:
                # namespaced correctly only if it points at THIS pack's ns
                if ":" in name and not name.lower().startswith(ns.lower() + ":"):
                    missing.append("%s -> %s (foreign ns)" % (ev, name))
                else:
                    resolved += 1
            elif path in expect_missing:
                own_bug.append("%s -> %s (mod's own sounds.json dangles)" % (ev, name))
            else:
                missing.append("%s -> %s" % (ev, name))
    unprefixed = 0
    for ev, entry in data.items():
        sounds = entry.get("sounds", [])
        if isinstance(sounds, str):
            sounds = [sounds]
        for s in sounds:
            name = s if isinstance(s, str) else s.get("name", "")
            if isinstance(s, dict) and str(s.get("type", "sound")) == "event":
                continue
            if ":" not in name:
                unprefixed += 1
    ok = (resolved + len(own_bug) == total) and (unprefixed == 0)
    lines = ["check ns=%s events=%d resolved_files=%d/%d own_bug=%d unprefixed_refs=%d"
             % (ns, len(data), resolved, total, len(own_bug), unprefixed)]
    for m in missing[:20]:
        lines.append("  MISSING: " + m)
    if len(missing) > 20:
        lines.append("  ... and %d more" % (len(missing) - 20))
    for m in own_bug:
        lines.append("  OWN-BUG: " + m)
    return ok, "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="Build a UMB legacy-mod sound pack")
    ap.add_argument("--namespace", default=None)
    ap.add_argument("--assets", default=None,
                    help="extracted mod asset root (dir holding sounds.json/sounds/)")
    ap.add_argument("--pack", required=True, help="pack dir to create/regenerate")
    ap.add_argument("--default-category", default="master",
                    help="sounds.json category for ogg-scan mode")
    ap.add_argument("--expect-missing", nargs="*", default=[],
                    help="lowercased ref paths the ORIGINAL mod lacks (its own bug)")
    ap.add_argument("--check", action="store_true",
                    help="offline validation only; resolve every ref, print counts")
    args = ap.parse_args()
    if args.check:
        ns_dirs = []
        assets = os.path.join(args.pack, "assets")
        if os.path.isdir(assets):
            ns_dirs = sorted(os.listdir(assets))
        if not ns_dirs:
            print("absence pack: no assets/, nothing to resolve")
            return 0
        ok_all = True
        expect = set(e.lower() for e in (args.expect_missing or []))
        for ns in ns_dirs:
            ok, report = check_tree(os.path.join(assets, ns), ns, expect)
            print(report)
            ok_all = ok_all and ok
        return 0 if ok_all else 2
    if not args.namespace or not args.assets:
        die("--namespace and --assets are required (unless --check)")
    build(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
