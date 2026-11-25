#!/usr/bin/env python3
"""Binary-linkage audit for a (merged) mod jar against per-version game jars.

Source compatibility per Minecraft version (the stage-2 harness) does not prove that a
MERGED jar links: shared classes ship one profile's bytecode, so every method/field
reference they contain must resolve, with the exact descriptor, on every Minecraft
version of the jar's range. This tool checks that directly from the class files
(stdlib only; needs nothing but python3 and, for the java.* modules, a JDK's jimage).

    audit-merged-jar-links.py --mod-jar <jar>
        --target <mc> <profile> <game_jar> [<classpath_file>]   (repeatable)
        [--jdk <java_home>]

<game_jar> is the merged Minecraft+NeoForge jar of that version
(versions/verify/<mc>/build/moddev/artifacts/<name>.jar, or versions/<mc>/... for the
real projects). It may be an os.pathsep-separated list searched in order, e.g.
"neoforge-21.7.25-beta-universal.jar:neoforge-21.7.0-beta-merged.jar" to check the
NeoForge API of a later build of the same line on top of the pinned game jar. <classpath_file> optionally lists the version's library jars (one path
per line, or one os.pathsep-separated line), e.g. brigadier, DFU, gson. <profile> is the
compat package suffix the mod's McVersion selects on that version (v121_7, v26_2, ...).

For each target, the classes checked are the shared classes plus the compat classes of
<profile> (com/.../compat/[client/]<profile>/ and, for 26.x, compat/<profile>/client/);
other profiles' compat classes are never loaded on that version and are skipped.

Checks, per target and checked class:
  class     every referenced class (constant pool, super class, interfaces) exists
  member    every Fieldref/Methodref/InterfaceMethodref resolves: exact name and
            descriptor on the owner or its super classes / super interfaces (JVM
            resolution order). Signature-polymorphic MethodHandle/VarHandle calls
            are accepted by name.
  lambda    every LambdaMetafactory call site's functional interface still declares
            the implemented method (name + erased descriptor)
  abstract  concrete mod classes implement every abstract method they inherit
  override  (across targets) a mod method that overrides a game/library method on
            some targets but not on others; on the others it is silently never called

Override drift needs every target of a jar in one run. When the targets are audited in
separate runs (one CI job per Minecraft version), write each run's override table with
--override-json <file> and compare them afterwards:

    audit-merged-jar-links.py --drift <file> [<file> ...]

(tables are grouped by mod jar file name, so one call can cover several jars).

Exit status: 0 when every target is clean, 1 when anything is unresolved, 2 on usage
errors. Reference owners under java/ that cannot be inspected (no --jdk) are listed as
"unverified" and do not fail the audit. --allow-drift <glob> (repeatable) accepts a known,
intended override drift; the glob is matched against "<class>.<method><descriptor>" with
the mod package prefix removed, e.g. "client/*.getScrollbarPosition()I".
"""

import argparse
import fnmatch
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

MOD_PREFIX = "com/pengin0503/itemmilestonelog/"
# compat/<p>/..., compat/client/<p>/... (1.21.x) and compat/<p>/client/... (26.x)
PROFILE_RE = re.compile(r"^" + re.escape(MOD_PREFIX) + r"compat/(?:client/)?(v\d+_\d+)/")

ACC_STATIC = 0x0008
ACC_PRIVATE = 0x0002
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400
ACC_VARARGS = 0x0080
ACC_NATIVE = 0x0100
ACC_BRIDGE = 0x0040
ACC_SYNTHETIC = 0x1000

LAMBDA_BSMS = {
    ("java/lang/invoke/LambdaMetafactory", "metafactory"),
    ("java/lang/invoke/LambdaMetafactory", "altMetafactory"),
}
POLYMORPHIC_OWNERS = {"java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle"}


def mutf8(raw):
    """Decodes a class-file (modified UTF-8) string."""
    raw = raw.replace(b"\xc0\x80", b"\x00")
    try:
        return raw.decode("utf-8", "surrogatepass")
    except UnicodeDecodeError:
        return raw.decode("utf-8", "replace")


class ClassInfo:
    """The parts of a class file the audit needs."""

    __slots__ = ("name", "access", "super", "interfaces", "fields", "methods",
                 "class_refs", "member_refs", "lambdas")

    def __init__(self, data, with_refs):
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("not a class file")
        pos = 8
        (count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        cp = [None] * count
        i = 1
        while i < count:
            tag = data[pos]
            pos += 1
            if tag == 1:  # Utf8
                (ln,) = struct.unpack_from(">H", data, pos)
                pos += 2
                cp[i] = ("Utf8", mutf8(data[pos:pos + ln]))
                pos += ln
            elif tag in (3, 4):  # Integer, Float
                cp[i] = ("Num",)
                pos += 4
            elif tag in (5, 6):  # Long, Double (two slots)
                cp[i] = ("Num",)
                pos += 8
                i += 1
            elif tag == 7:  # Class
                cp[i] = ("Class", struct.unpack_from(">H", data, pos)[0])
                pos += 2
            elif tag == 8:  # String
                cp[i] = ("String",)
                pos += 2
            elif tag in (9, 10, 11):  # Fieldref, Methodref, InterfaceMethodref
                c, nt = struct.unpack_from(">HH", data, pos)
                cp[i] = ({9: "Field", 10: "Method", 11: "IMethod"}[tag], c, nt)
                pos += 4
            elif tag == 12:  # NameAndType
                cp[i] = ("NameAndType",) + struct.unpack_from(">HH", data, pos)
                pos += 4
            elif tag == 15:  # MethodHandle
                cp[i] = ("MethodHandle", data[pos], struct.unpack_from(">H", data, pos + 1)[0])
                pos += 3
            elif tag == 16:  # MethodType
                cp[i] = ("MethodType", struct.unpack_from(">H", data, pos)[0])
                pos += 2
            elif tag in (17, 18):  # Dynamic, InvokeDynamic
                b, nt = struct.unpack_from(">HH", data, pos)
                cp[i] = ("Dynamic" if tag == 17 else "Indy", b, nt)
                pos += 4
            elif tag in (19, 20):  # Module, Package
                cp[i] = ("Other",)
                pos += 2
            else:
                raise ValueError("bad constant pool tag %d" % tag)
            i += 1

        def utf(idx):
            return cp[idx][1]

        def cls(idx):
            return utf(cp[idx][1])

        def nat(idx):
            _, n, d = cp[idx]
            return utf(n), utf(d)

        self.access, this_idx, super_idx = struct.unpack_from(">HHH", data, pos)
        pos += 6
        self.name = cls(this_idx)
        self.super = cls(super_idx) if super_idx else None
        (n_if,) = struct.unpack_from(">H", data, pos)
        pos += 2
        self.interfaces = [cls(struct.unpack_from(">H", data, pos + 2 * k)[0]) for k in range(n_if)]
        pos += 2 * n_if

        def members(pos):
            (n,) = struct.unpack_from(">H", data, pos)
            pos += 2
            out = {}
            for _ in range(n):
                acc, ni, di, na = struct.unpack_from(">HHHH", data, pos)
                pos += 8
                for _ in range(na):
                    (alen,) = struct.unpack_from(">I", data, pos + 2)
                    pos += 6 + alen
                out[(utf(ni), utf(di))] = acc
            return out, pos

        self.fields, pos = members(pos)
        self.methods, pos = members(pos)

        self.class_refs = set()
        self.member_refs = set()
        self.lambdas = set()
        if not with_refs:
            return

        bootstrap = []
        (na,) = struct.unpack_from(">H", data, pos)
        pos += 2
        for _ in range(na):
            ni, alen = struct.unpack_from(">HI", data, pos)
            body = pos + 6
            if utf(ni) == "BootstrapMethods":
                (nb,) = struct.unpack_from(">H", data, body)
                p = body + 2
                for _ in range(nb):
                    mh, nargs = struct.unpack_from(">HH", data, p)
                    p += 4
                    args = list(struct.unpack_from(">%dH" % nargs, data, p))
                    p += 2 * nargs
                    bootstrap.append((mh, args))
            pos = body + alen

        for entry in cp:
            if entry is None:
                continue
            kind = entry[0]
            if kind == "Class":
                self.class_refs.add(utf(entry[1]))
            elif kind in ("Field", "Method", "IMethod"):
                name, desc = nat(entry[2])
                self.member_refs.add((kind, cls(entry[1]), name, desc))
            elif kind == "Indy":
                mh_idx, args = bootstrap[entry[1]]
                ref = cp[cp[mh_idx][2]]
                bsm = (cls(ref[1]), nat(ref[2])[0])
                if bsm in LAMBDA_BSMS and args and cp[args[0]][0] == "MethodType":
                    name, desc = nat(entry[2])
                    iface = desc[desc.rindex(")") + 2:-1]  # return type L...;
                    self.lambdas.add((iface, name, utf(cp[args[0]][1])))


class ClassPool:
    """Class lookup across the mod jar, game jar(s), libraries and the JDK modules."""

    def __init__(self, mod_classes, jar_paths, jdk_dirs):
        self.mod = mod_classes
        self.zips = []
        for p in jar_paths:
            try:
                self.zips.append(zipfile.ZipFile(p))
            except (OSError, zipfile.BadZipFile) as e:
                print("  WARN  cannot open classpath entry %s: %s" % (p, e), file=sys.stderr)
        self.jdk_dirs = jdk_dirs or []
        self.cache = {}

    def get(self, name):
        if name in self.mod:
            return self.mod[name]
        if name in self.cache:
            return self.cache[name]
        info = None
        entry = name + ".class"
        for z in self.zips:
            try:
                data = z.read(entry)
            except KeyError:
                continue
            info = ClassInfo(data, with_refs=False)
            break
        if info is None and name.startswith(("java/", "javax/", "jdk/", "sun/")):
            for module_dir in self.jdk_dirs:
                path = os.path.join(module_dir, entry)
                if os.path.isfile(path):
                    with open(path, "rb") as f:
                        info = ClassInfo(f.read(), with_refs=False)
                    break
        self.cache[name] = info
        return info

    def supertypes(self, name):
        """Yields (name, info) for name and every super class / super interface, JVM order:
        the class chain first, then interfaces breadth-first. Missing types yield info None."""
        seen = set()
        chain = []
        cur = name
        while cur and cur not in seen:
            seen.add(cur)
            info = self.get(cur)
            chain.append((cur, info))
            cur = info.super if info else None
        for item in chain:
            yield item
        queue = []
        for _, info in chain:
            if info:
                queue.extend(info.interfaces)
        while queue:
            cur = queue.pop(0)
            if cur in seen:
                continue
            seen.add(cur)
            info = self.get(cur)
            yield cur, info
            if info:
                queue.extend(info.interfaces)


def is_jdk(name):
    return name.startswith(("java/", "javax/", "jdk/", "sun/"))


def element_class(name):
    """Class name of a constant-pool class entry (arrays: their element class or None)."""
    if not name.startswith("["):
        return name
    stripped = name.lstrip("[")
    if stripped.startswith("L") and stripped.endswith(";"):
        return stripped[1:-1]
    return None  # primitive array


def erase_params(desc):
    return desc[:desc.index(")") + 1]


class Target:
    def __init__(self, mc, profile, game_jars, classpath_file):
        self.mc = mc
        self.profile = profile
        self.game_jars = game_jars
        own = {os.path.abspath(j) for j in game_jars}
        self.libs = []
        if classpath_file:
            with open(classpath_file) as f:
                for line in f:
                    for part in line.strip().split(os.pathsep):
                        if part and part.endswith(".jar") and os.path.abspath(part) not in own:
                            self.libs.append(part)


def checked_classes(mod_classes, profile):
    out = []
    for name in sorted(mod_classes):
        m = PROFILE_RE.match(name + "/")
        if m and m.group(1) != profile:
            continue
        out.append(name)
    return out


def resolve_member(pool, kind, owner, name, desc):
    """Returns 'ok', 'unverified' (a java/ type in the hierarchy could not be inspected)
    or 'missing'."""
    owner_cls = element_class(owner)
    if owner_cls is None or owner.startswith("["):
        return "ok"  # array pseudo-class (clone(), length)
    unverified = False
    for cname, info in pool.supertypes(owner_cls):
        if info is None:
            if is_jdk(cname):
                unverified = True
                continue
            continue
        if kind == "Field":
            if (name, desc) in info.fields:
                return "ok"
        else:
            if (name, desc) in info.methods:
                return "ok"
            if cname in POLYMORPHIC_OWNERS:
                for (mn, _md), acc in info.methods.items():
                    if mn == name and acc & ACC_NATIVE and acc & ACC_VARARGS:
                        return "ok"
    if kind == "IMethod" or (kind == "Method" and pool.get(owner_cls) and pool.get(owner_cls).access & ACC_INTERFACE):
        # Interface methods also resolve against java/lang/Object's public methods.
        obj = pool.get("java/lang/Object")
        if obj is None:
            unverified = True
        elif (name, desc) in obj.methods:
            return "ok"
    return "unverified" if unverified else "missing"


def abstract_gaps(pool, cname):
    """Abstract methods inherited by concrete class cname that nothing implements."""
    info = pool.get(cname)
    if info is None or info.access & (ACC_ABSTRACT | ACC_INTERFACE):
        return []
    concrete = set()
    abstract = {}
    for tname, tinfo in pool.supertypes(cname):
        if tinfo is None:
            if is_jdk(tname):
                return []  # cannot judge without the JDK type
            continue
        is_iface = bool(tinfo.access & ACC_INTERFACE)
        for key, acc in tinfo.methods.items():
            if key[0] in ("<init>", "<clinit>") or acc & (ACC_STATIC | ACC_PRIVATE):
                continue
            if acc & ACC_ABSTRACT:
                abstract.setdefault(key, tname)
            elif not is_iface or key not in concrete:
                concrete.add(key)  # class method or interface default
    return sorted((k, owner) for k, owner in abstract.items() if k not in concrete)


def overridden_in(pool, cname, key):
    """Non-mod supertype that declares the overridable method key, or None."""
    first = True
    for tname, tinfo in pool.supertypes(cname):
        if first:
            first = False
            continue
        if tinfo is None or tname.startswith(MOD_PREFIX):
            continue
        acc = tinfo.methods.get(key)
        if acc is not None and not acc & (ACC_STATIC | ACC_PRIVATE):
            return tname
    return None


def extract_jdk_modules(java_home, tmp):
    jimage = os.path.join(java_home, "bin", "jimage")
    modules = os.path.join(java_home, "lib", "modules")
    if not (os.path.isfile(jimage) and os.path.isfile(modules)):
        print("  WARN  no jimage/modules under %s; java/ owners stay unverified" % java_home, file=sys.stderr)
        return None
    dest = os.path.join(tmp, "jdk")
    subprocess.run([jimage, "extract", "--dir", dest, "--include", r"regex:/java\.[^/]*/.*", modules],
                   check=True)
    return sorted(os.path.join(dest, d) for d in os.listdir(dest))


def short(cname):
    return cname[len(MOD_PREFIX):] if cname.startswith(MOD_PREFIX) else cname


def report_drift(tables, allowed=()):
    """tables: list of (jar, mc, {(class, name, desc): owner-or-None}). Returns True on drift
    that no glob in allowed accepts."""
    by_jar = defaultdict(list)
    for jar, mc, table in tables:
        by_jar[jar].append((mc, table))
    any_drift = False
    for jar in sorted(by_jar):
        runs = by_jar[jar]
        mcs = [mc for mc, _ in runs]
        per_key = defaultdict(dict)
        for mc, table in runs:
            for key, owner in table.items():
                per_key[key][mc] = owner
        drift = [(key, per) for key, per in sorted(per_key.items())
                 if len(per) > 1 and any(per.values()) and not all(per.values())]
        label = lambda key: "%s.%s%s" % (short(key[0]), key[1], key[2])
        failing = [d for d in drift if not any(fnmatch.fnmatchcase(label(d[0]), g) for g in allowed)]
        print("\n== Override drift across targets of %s (%s): %s"
              % (jar, ", ".join(mcs), "FAIL" if failing else "PASS"))
        for key, per in drift:
            print("  %s %s" % ("DRIFT" if (key, per) in failing else "drift (allowed)", label(key)))
            for mc in mcs:
                if mc in per:
                    print("        %-22s %s" % (mc, ("overrides " + per[mc]) if per[mc] else "overrides nothing"))
        any_drift = any_drift or bool(failing)
    return any_drift


def audit_target(t, mod_classes, jdk_dirs):
    """Prints the report of one target. Returns (failed, override table)."""
    pool = ClassPool(mod_classes, t.game_jars + t.libs, jdk_dirs)
    names = checked_classes(mod_classes, t.profile)
    problems = defaultdict(set)  # (category, detail) -> referencing classes
    unverified = defaultdict(set)
    overrides = {}
    n_refs = 0
    for cname in names:
        info = mod_classes[cname]
        for ref in sorted(info.class_refs | ({info.super} if info.super else set()) | set(info.interfaces)):
            ec = element_class(ref)
            if ec is None or ec in mod_classes:
                continue
            n_refs += 1
            if pool.get(ec) is None:
                (unverified if is_jdk(ec) else problems)[("class", ec)].add(cname)
        for kind, owner, mname, mdesc in sorted(info.member_refs):
            n_refs += 1
            res = resolve_member(pool, kind, owner, mname, mdesc)
            label = "%s.%s%s%s" % (owner, mname, "" if kind != "Field" else ":", mdesc)
            if res == "missing":
                problems[("member", label)].add(cname)
            elif res == "unverified":
                unverified[("member", label)].add(cname)
        for iface, mname, sam in sorted(info.lambdas):
            n_refs += 1
            if pool.get(iface) is None:
                (unverified if is_jdk(iface) else problems)[("lambda", "%s (functional interface missing)" % iface)].add(cname)
                continue
            found = any(n == mname and erase_params(d) == erase_params(sam)
                        for _, ti in pool.supertypes(iface) if ti for (n, d) in ti.methods)
            if not found:
                problems[("lambda", "%s.%s%s" % (iface, mname, sam))].add(cname)
        for (mname, mdesc), owner in abstract_gaps(pool, cname):
            problems[("abstract", "%s.%s%s (unimplemented in %s)" % (owner, mname, mdesc, cname))].add(cname)
        for key, acc in info.methods.items():
            if key[0].startswith("<") or acc & (ACC_STATIC | ACC_PRIVATE | ACC_SYNTHETIC | ACC_BRIDGE):
                continue
            overrides[(cname,) + key] = overridden_in(pool, cname, key)

    print("\n== MC %s (profile %s, game jar %s, %d library jars): %s  %d classes, %d references checked, %d unresolved, %d unverified"
          % (t.mc, t.profile, " + ".join(os.path.basename(j) for j in t.game_jars), len(t.libs),
             "FAIL" if problems else "PASS", len(names), n_refs, len(problems), len(unverified)))
    for (cat, detail) in sorted(problems):
        users = sorted(problems[(cat, detail)])
        print("  UNRESOLVED [%s] %s" % (cat, detail))
        print("             from: %s" % ", ".join(short(u) for u in users))
    for (cat, detail) in sorted(unverified):
        print("  unverified [%s] %s" % (cat, detail))
    return bool(problems), overrides


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mod-jar")
    ap.add_argument("--target", nargs="+", action="append",
                    metavar="MC PROFILE GAME_JAR [CLASSPATH_FILE]")
    ap.add_argument("--jdk", default=os.environ.get("JAVA_HOME"),
                    help="JDK home whose java.* modules are used for java/ owners (default $JAVA_HOME)")
    ap.add_argument("--override-json", metavar="FILE",
                    help="write the override tables of this run (input for --drift)")
    ap.add_argument("--drift", nargs="+", metavar="FILE",
                    help="only compare override tables written by earlier runs")
    ap.add_argument("--allow-drift", action="append", default=[], metavar="GLOB",
                    help="accept a known override drift (see the module docstring)")
    args = ap.parse_args()

    if args.drift:
        tables = []
        for path in args.drift:
            with open(path) as f:
                doc = json.load(f)
            for run in doc["runs"]:
                tables.append((doc["jar"], run["mc"],
                               {tuple(k.split(" ", 2)): v for k, v in run["overrides"].items()}))
        failed = report_drift(tables, args.allow_drift)
        print("\nAUDIT %s" % ("FAILED" if failed else "PASSED"))
        return 1 if failed else 0

    if not args.mod_jar or not args.target:
        ap.error("--mod-jar and at least one --target are required (or use --drift)")
    targets = []
    for t in args.target:
        if len(t) not in (3, 4):
            ap.error("--target takes MC PROFILE GAME_JAR [CLASSPATH_FILE]")
        game_jars = [j for j in t[2].split(os.pathsep) if j]
        for j in game_jars:
            if not os.path.isfile(j):
                ap.error("game jar not found: %s" % j)
        if len(t) == 4 and not os.path.isfile(t[3]):
            ap.error("classpath file not found: %s" % t[3])
        targets.append(Target(t[0], t[1], game_jars, t[3] if len(t) == 4 else None))

    mod_classes = {}
    with zipfile.ZipFile(args.mod_jar) as z:
        for entry in z.namelist():
            if entry.endswith(".class") and not entry.startswith("META-INF/"):
                info = ClassInfo(z.read(entry), with_refs=True)
                mod_classes[info.name] = info

    profiles_in_jar = sorted({m.group(1) for n in mod_classes for m in [PROFILE_RE.match(n + "/")] if m})
    jar_name = os.path.basename(args.mod_jar)
    print("Mod jar: %s (%d classes, compat profiles: %s)"
          % (jar_name, len(mod_classes), ", ".join(profiles_in_jar) or "none"))

    tmp = tempfile.mkdtemp(prefix="audit-links-")
    failed = False
    tables = []
    try:
        jdk_dirs = extract_jdk_modules(args.jdk, tmp) if args.jdk else None
        for t in targets:
            if t.profile not in profiles_in_jar:
                print("\n== MC %s: FAIL  profile %s not in the mod jar" % (t.mc, t.profile))
                failed = True
                continue
            target_failed, overrides = audit_target(t, mod_classes, jdk_dirs)
            failed = failed or target_failed
            tables.append((jar_name, t.mc, overrides))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if args.override_json:
        with open(args.override_json, "w") as f:
            json.dump({"jar": jar_name, "runs": [
                {"mc": mc, "overrides": {" ".join(k): v for k, v in sorted(table.items())}}
                for _, mc, table in tables]}, f, indent=1, sort_keys=True)
    if len(tables) > 1:
        failed = report_drift(tables, args.allow_drift) or failed

    print("\nAUDIT %s" % ("FAILED" if failed else "PASSED"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
