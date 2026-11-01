[English](README.md) | [한국어](README.ko.md)

# plexus-mod-metadata

## What this module does
Parses and validates `plexus.mod.json` schema 1, and provides SemVer 2.0.0 versions and version ranges.

## What it owns
`dev.plexusmodloader.runtime.metadata`(`ModMetadata`, `ModId`, `ModMetadataReader`, `MetadataProblemCode`) and `...metadata.version`(`SemanticVersion`, `VersionRange`)

## What it does not do
Does not handle file discovery or inter-mod relationships (dependency resolution).

## Dependencies
Gson 2.14.0 (same version as Minecraft 26.2, ADR-0005)

## Main entry point
`ModMetadataReader.read(String json)` → `Result(metadata, problems)`

## Build and test
```powershell
.\gradlew.bat :plexus-mod-metadata:check
```

19 `ModMetadataReaderTest` tests, 18 `VersionRangeTest` tests

## Typical flow
Parses the JSON, warns on unknown fields, and validates each field to collect all problems. Creates `ModMetadata` only when there are no errors.

## Known limitations
`provides` is a reserved field, and if not empty, it is rejected with `PLEXUS-METADATA-010`. Range syntax supports only comparison operator combinations and `*`; `~` and `^` are not supported.
