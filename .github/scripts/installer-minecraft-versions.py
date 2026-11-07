"""Prints the Minecraft versions the installer offers, the way the installer build lists them.

The installer build takes them from MinecraftVersions.runnable() (build-logic): each version in `supported` of
gradle/minecraft-versions.properties, oldest first, followed by its hotfixes (`hotfixes.<version>=...`). Experimental
versions are not offered. The release's "Minecraft:" line must be this same list.

Usage:
  python installer-minecraft-versions.py [<minecraft-versions.properties>]
      prints the list, separated by ", " (default gradle/minecraft-versions.properties; run from the repository root)
  python installer-minecraft-versions.py [<properties>] --jar <PlexusModLoader-x.y.z.jar>
      also reads installer.json inside the built installer jar and exits 1 if its minecraftVersions differ
"""
import json
import sys
import zipfile


def read_properties(path):
    """The key=value lines of a .properties file; comments and blank lines are skipped."""
    values = {}
    with open(path, encoding="utf-8") as file:
        for line in file:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def split(value):
    return [item.strip() for item in value.split(",") if item.strip()]


def offered(properties):
    """Every supported version followed by its hotfixes, oldest first, as MinecraftVersions.runnable()."""
    versions = []
    for version in split(properties.get("supported", "")):
        versions.append(version)
        versions.extend(split(properties.get("hotfixes." + version, "")))
    return versions


args = sys.argv[1:]
jar = None
if "--jar" in args:
    position = args.index("--jar")
    if position + 1 >= len(args):
        sys.exit("--jar needs the installer jar")
    jar = args[position + 1]
    del args[position:position + 2]
path = args[0] if args else "gradle/minecraft-versions.properties"

versions = offered(read_properties(path))
if not versions:
    sys.exit(f"{path} lists no supported Minecraft version")
if len(set(versions)) != len(versions):
    sys.exit(f"{path} lists a Minecraft version twice: {versions}")
if jar is not None:
    with zipfile.ZipFile(jar) as archive:
        names = [name for name in archive.namelist() if name.endswith("installer.json")]
        if len(names) != 1:
            sys.exit(f"{jar} must hold exactly one installer.json, found {names}")
        inside = json.loads(archive.read(names[0]).decode("utf-8"))["minecraftVersions"]
    if inside != versions:
        sys.exit(f"The installer offers {inside} but {path} says {versions}; the release notes would be wrong.")
print(", ".join(versions))
