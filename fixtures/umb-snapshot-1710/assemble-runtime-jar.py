#!/usr/bin/env python3
"""Assemble research/out/legacy/1.7.10-forge-srg-runtime.jar.

Inputs
  1. the LaunchClassLoader DEBUG_SAVE dump produced by run-native-1710.ps1
     (every class the production client actually loaded, AFTER Forge's
     binpatches, the mods' access transformers and FML's obf->SRG remap), and
  2. research/out/1.7.10-client-srg.jar, the *unpatched* SRG vanilla client,
     used only to fill in classes the client never loaded.

Dumped vanilla wins wherever both have a class, so the result is "1.7.10 vanilla
as Forge 10.13.4.1614 + HBM's AT actually present it at runtime", with full
coverage of the 1.7.10 class set.

Mod classes from the dump (com/hbm, api, cofh, net/umb) are copied to
research/out/legacy/dump-mods/ for reference and are NEVER put in the jar.

Research-only. Mojang/Forge-derived bytes stay on this host.
"""
import os
import sys
import zipfile
import shutil

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRG_JAR = os.path.join(ROOT, 'research', 'out', '1.7.10-client-srg.jar')
OUT_DIR = os.path.join(ROOT, 'research', 'out', 'legacy')
OUT_JAR = os.path.join(OUT_DIR, '1.7.10-forge-srg-runtime.jar')
MODS_DIR = os.path.join(OUT_DIR, 'dump-mods')

MOD_PREFIXES = ('com/hbm/', 'net/umb/', 'api/', 'cofh/')


def find_dump():
    inst = os.path.join(ROOT, 'research', 'visual', 'mc1710-native')
    cands = []
    for base in (inst, ROOT):
        try:
            for n in os.listdir(base):
                if n.startswith('CLASSLOADER_TEMP'):
                    cands.append(os.path.join(base, n))
        except OSError:
            pass
    return cands


def main():
    dumps = find_dump()
    if not dumps:
        print('no CLASSLOADER_TEMP* directory found', file=sys.stderr)
        return 2
    dump = sorted(dumps)[0]
    print('dump dir            : %s' % dump)

    vanilla = {}   # jar entry name -> abs path
    mods = []
    other = []
    root_level = []
    total = 0
    for dirpath, _dirnames, filenames in os.walk(dump):
        for fn in filenames:
            if not fn.endswith('.class'):
                continue
            total += 1
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, dump).replace('\\', '/')
            if rel.startswith('net/minecraft/'):
                vanilla[rel] = full
            elif rel.startswith(MOD_PREFIXES):
                mods.append((rel, full))
            elif '/' not in rel:
                root_level.append((rel, full))
            else:
                other.append(rel)

    srg_names = []
    with zipfile.ZipFile(SRG_JAR) as z:
        srg_names = [n for n in z.namelist() if n.endswith('.class')]
    srg_set = set(srg_names)
    srg_root = set(n for n in srg_names if '/' not in n)

    # root-level dumped classes are kept only if the unpatched SRG jar also has
    # them (i.e. they are vanilla, not Forge's own Start/FMLRenderAccessLibrary)
    kept_root = [(r, f) for (r, f) in root_level if r in srg_root]
    for rel, full in kept_root:
        vanilla[rel] = full

    filled = [n for n in srg_names if n not in vanilla]

    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT_JAR):
        os.remove(OUT_JAR)
    with zipfile.ZipFile(OUT_JAR, 'w', zipfile.ZIP_DEFLATED) as out:
        for rel in sorted(vanilla):
            with open(vanilla[rel], 'rb') as fh:
                out.writestr(rel, fh.read())
        with zipfile.ZipFile(SRG_JAR) as z:
            for n in sorted(filled):
                out.writestr(n, z.read(n))

    # mod classes -> reference dir (not in the jar)
    if os.path.isdir(MODS_DIR):
        shutil.rmtree(MODS_DIR)
    for rel, full in mods:
        dst = os.path.join(MODS_DIR, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(full, dst)

    with zipfile.ZipFile(OUT_JAR) as z:
        final = len([n for n in z.namelist() if n.endswith('.class')])

    print('dumped total        : %d' % total)
    print('  vanilla dumped    : %d  (net/minecraft/** = %d, root-level kept = %d)'
          % (len(vanilla), len(vanilla) - len(kept_root), len(kept_root)))
    print('  mod classes dumped: %d  -> %s' % (len(mods), MODS_DIR))
    print('  other dumped      : %d  (cpw/**, net/minecraftforge/**, libs; excluded)' % len(other))
    print('  root-level dropped: %d  %s'
          % (len(root_level) - len(kept_root),
             sorted(r for r, _ in root_level if r not in srg_root)[:10]))
    print('unpatched srg jar   : %d classes' % len(srg_names))
    print('  filled from it    : %d' % len(filled))
    print('final jar           : %s (%d classes, %d bytes)'
          % (OUT_JAR, final, os.path.getsize(OUT_JAR)))
    extra = sorted(n for n in vanilla if n not in srg_set)
    print('  classes present only in the dump (Forge additions): %d %s'
          % (len(extra), extra[:12]))
    return 0


if __name__ == '__main__':
    sys.exit(main())
