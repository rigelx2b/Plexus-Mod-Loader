package cpw.mods.modlauncher.api;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * ModLauncher's naming service contract, which ModLauncher 11 (NeoForge's FML 4, ADR-0043) no longer has but Plexus Mod
 * Loader's ModLauncher implementation ({@code forge-host.jar}, written against ModLauncher 10) still reads. Same shape as
 * ModLauncher 10's, so that implementation runs unchanged; nothing in FML 4 provides one, so no naming is ever applied.
 */
public interface INameMappingService {

    /** The name of this mapping. */
    String mappingName();

    /** The version of this mapping. */
    String mappingVersion();

    /** What this service understands: a name of the mapping set and the naming it applies to. */
    Map.Entry<String, String> understanding();

    /** The function that renames a name of the given domain. */
    BiFunction<Domain, String, String> namingFunction();

    /** What kind of name is renamed. */
    enum Domain {
        CLASS,
        METHOD,
        FIELD
    }
}
