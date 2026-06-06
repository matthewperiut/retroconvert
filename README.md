# RetroConvert

Automatically converts **Babric** b1.7.3 mods to **Ornithe** at launch by
rewriting their intermediary bytecode in place.

Both toolchains run the same fabric loader against the same Minecraft b1.7.3,
but each invented its own intermediary names for Mojang's obfuscated code:

| | classes | methods | fields |
|---|---|---|---|
| Babric intermediary | `net/minecraft/class_N` | `method_N` | `field_N` |
| Calamus gen2 (Ornithe) | `net/minecraft/unmapped/C_NNNNNNNN` | `m_NNNNNNNN` | `f_NNNNNNNN` |

Since both schemes use globally unique tokens, a babric mod can be converted
to an ornithe mod by a direct token-for-token rewrite — no recompilation, no
sources, no internet. RetroConvert ships a precomposed
`babric -> calamus gen2` map (built by joining both intermediaries over the
official obfuscated names of the b1.7.3 client and server jars) and applies it
with ASM, which fabric loader already provides at runtime.

## What it does at launch (`preLaunch`, before any game class loads)

1. Scans every `mods/*.jar`, keyed by sha256 in `config/retroconvert/cache.json`
   (new files only; delete the json to force a full rescan). Anything named
   `*retroconvert*` (and the `retroconvert` mod id) is never touched.
2. Detects the toolchain, recursing into nested `META-INF/jars/` — umbrella
   mods like StationAPI keep all their classes in nested jars. Calamus tokens
   → already ornithe; babric tokens → convert; neither → leave alone. Within
   one jar calamus tokens win, because hand-ported mods are known to keep
   stale babric-looking strings; a babric nested jar still triggers
   conversion, during which each nested jar is judged individually.
3. For babric jars, rewrites:
   - class bytecode: every class/method/field reference, declaration,
     signature, and stack frame
   - mixin annotations — both refmap-less string targets
     (`@Inject(method = "method_1977")`) and `@Mixin(class_475.class)` refs
   - the refmap json, accesswidener, and `fabric.mod.json`
   - string constants, so reflection by intermediary name keeps working
     (e.g. a `getField("field_220")` candidate becomes `"f_92455821"`)
   - nested `META-INF/jars/*.jar` (jar-in-jar), recursively
   - `com.matthewperiut.accessoryapi` → `com.periut.accessoryapi`
     (the package moved in the ornithe port of AccessoryAPI)
4. Moves the original into `config/retroconvert/originals/` and writes
   `<name>-ornithe.jar` into `mods/` in its place.
5. The moment a conversion is committed, the loader's grip on the mods folder
   is released (its jar handles and per-mod filesystems are closed — that
   session was abandoned anyway), so the file swaps run inline even on
   Windows, where those handles are hard locks.
6. Then fabric loader is restarted from the top **inside the same JVM**: the
   stale half-initialized loader is abandoned and a fresh,
   classloader-isolated copy boots over the now-converted mods folder — no
   visible restart at all. Where that isn't possible (dev environment, exotic
   launchers) it falls back to a dialog asking for one manual restart.
   Disable the in-JVM relaunch with `-Dretroconvert.relaunch=false`.

Mods that already migrated to ornithe APIs (StationAPI, etc.) keep working —
references to non-minecraft mod classes are untouched.

## Official-build replacements

A few mods changed too much between the toolchains for a bytecode rewrite to
be enough. For those, the babric jar is archived and the official Ornithe
build is downloaded instead (only when the jar is actually detected as babric
— already-ported copies are never touched, and if the official build is
already installed the babric duplicate is just archived):

| detected babric jar | replaced with |
|---|---|
| `StationAPI-2.0.0-alpha.*` (> 10 MiB) | `StationAPI-2.0.0-alpha.6.2+gen2.jar` |
| `GlassConfigAPI-3.*` (> 2.5 MiB) | `GlassConfigAPI-3.3.0+gen2.jar` |

If the download fails (offline), bytecode conversion runs as the fallback —
and when the official build becomes reachable again (or a replacement rule
ships after a mod was already bytecode-converted), the old conversion output
is automatically upgraded to the official build on the next launch.

## Java agent mode (fully invisible, even on the first launch)

The same jar doubles as a java agent. Add to the JVM arguments (Prism
Launcher: instance → Settings → Java → JVM arguments):

```
-javaagent:mods/retroconvert-1.0.0+mcb1.7.3.jar
```

The relative path works as-is: Prism, MultiMC and the vanilla launcher all set
the working directory to the instance's minecraft folder, so the agent is the
very jar already sitting in `mods/` — nothing else to configure, and the same
line works for every instance.

`premain` then converts babric mods **before fabric loader scans the mods
folder**: no file is locked yet, so there is never a restart, a relaunch, or a
deferred Windows swap. The in-game pass notices the agent already ran and
skips itself. The game directory is found automatically — agent argument
(`-javaagent:...=/path/to/.minecraft`), the MultiMC/Prism `INST_MC_DIR`/
`INST_DIR` environment variables when exported, the `--gameDir` program
argument, then the working directory; the first candidate containing a `mods`
folder wins.

## Headless usage

The exact same pipeline can be run against any mods folder without the game:

```sh
./gradlew runConverter --args="/path/to/.minecraft/mods"
```

## Regenerating the mapping

`src/main/resources/retroconvert/babric-to-calamus-b1.7.3.tsv` is produced by
`tools/compose_mappings.py` from:

- babric intermediary: `https://github.com/babric/intermediary/raw/master/mappings/b1.7.3.tiny`
  (tiny v1: `intermediary | glue | server | client`)
- calamus gen2 intermediary: `net.ornithemc:calamus-intermediary-gen2:b1.7.3:v2`
  from `https://maven.ornithemc.net/releases`
  (tiny v2: `intermediary | clientOfficial | serverOfficial`)

The composer joins the two over the official client/server namespaces and
emits `c`/`m`/`f` token rows plus owner-aware `M`/`F` rows for bundled-library
members (argo, paulscode, ...) that babric left at their original names but
calamus renamed.

## Limitations

- A babric mod that fails fabric-loader dependency resolution outright
  crashes before any entrypoint runs — RetroConvert cannot rescue that;
  remove the offending jar manually.
- Restoring an already-converted original back into `mods/` next to its
  converted output gives the loader two jars with the same mod id.
- Only b1.7.3 is supported, by design (babric only targets b1.7.3).
