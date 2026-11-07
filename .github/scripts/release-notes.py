"""Prints the release notes of one version from its CHANGELOG.md section, from "## <version>" to the next "## <number>".

The section must have "### Summary" (one sentence) and at least one of "### What's New" and "### Improvements & Fixes"
(bullets). The notes hold only these, in English; the rest of the section stays in the changelog.

Usage: python release-notes.py <version> <CHANGELOG.md>
Environment: PLEXUS_MINECRAFT_VERSIONS (comma-separated) adds a "Minecraft: ..." line first.
"""
import os
import re
import sys

# Windows (GitHub's runner) would print the notes' emoji headings with its ANSI code page and fail.
sys.stdout.reconfigure(encoding="utf-8")

version, changelog = sys.argv[1], sys.argv[2]
lines = open(changelog, encoding="utf-8").read().splitlines()
start = next((i for i, line in enumerate(lines) if re.match(rf"^## {re.escape(version)}(\s|$)", line)), None)
if start is None:
    sys.exit(f"{changelog} has no '## {version}' section; rename the '## Unreleased' section to it before tagging")
end = next((i for i in range(start + 1, len(lines)) if re.match(r"^## \d", lines[i])), len(lines))
section = lines[start + 1:end]


def part(heading):
    """The text of one "### <heading>" part of the section, empty when it is missing."""
    first = next((i for i, line in enumerate(section) if line.strip() == f"### {heading}"), None)
    if first is None:
        return ""
    last = next((i for i in range(first + 1, len(section)) if section[i].startswith("### ")), len(section))
    return "\n".join(section[first + 1:last]).strip()


summary = part("Summary")
if not summary or "\n" in summary or summary.startswith("- "):
    sys.exit(f"The '## {version}' section of {changelog} needs a '### Summary' of one sentence")
parts = [summary]
for heading, title in (("What's New", "### ✨ What's New"), ("Improvements & Fixes", "### 🔧 Improvements & Fixes")):
    text = part(heading)
    if text:
        if not all(line.startswith("- ") for line in text.splitlines()):
            sys.exit(f"The '### {heading}' part of '## {version}' must hold only one-line bullets")
        parts.append(f"{title}\n{text}")
if len(parts) == 1:
    sys.exit(f"The '## {version}' section of {changelog} needs '### What's New' or '### Improvements & Fixes' bullets")
body = "\n\n".join(parts)
if re.search("[가-힣]", body):
    sys.exit(f"The release notes of '## {version}' must be in English only")
# Repository-relative links do not work on a release page; point them at the tagged files on GitHub.
server, repository = os.environ.get("GITHUB_SERVER_URL"), os.environ.get("GITHUB_REPOSITORY")
release_repository = os.environ.get("PLEXUS_RELEASE_REPO") or repository
if release_repository != repository:
    # Released from a private source repository: its files cannot be linked, so keep only the link text.
    body = re.sub(r"\[([^\]]+)\]\((?!https?:|#|mailto:)[^)]+\)", r"\1", body)
elif server and repository:
    ref = os.environ.get("GITHUB_REF_NAME", "main")
    body = re.sub(
        r"\]\((?!https?:|#|mailto:)([^)]+)\)", lambda m: f"]({server}/{repository}/blob/{ref}/{m.group(1)})", body
    )
# The website's download page reads the "Minecraft:" line to group releases by game version.
minecraft = os.environ.get("PLEXUS_MINECRAFT_VERSIONS", "").strip()
if minecraft:
    print("Minecraft: %s" % minecraft)
    print()
print(body)
print()
print("NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.")
