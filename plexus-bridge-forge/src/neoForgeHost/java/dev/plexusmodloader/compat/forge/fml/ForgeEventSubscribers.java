package dev.plexusmodloader.compat.forge.fml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.objectweb.asm.Type;

/**
 * Forge on NeoForge, F-N6 (ADR-0051): Forge's {@code @Mod.EventBusSubscriber} classes. Forge's own {@code
 * FMLModContainer} registers them while it constructs the mod ({@code AutomaticEventSubscriber.inject}), from the
 * annotations in the scan data it is given; the host gives it, in Forge's types, the {@code @Mod} and {@code
 * Mod.EventBusSubscriber} annotations NeoForge's scan of the mod file found. Forge's code then picks each subscriber's
 * bus as Forge does: the mod's bus group for mod bus events (through {@code FMLJavaModLoadingContext.get()}, which works
 * while the mod is constructed, as its container is active then), Forge's game bus group otherwise, and skips
 * subscribers of the other side ({@code FMLEnvironment.dist}, which the host sets). Annotation values are copied as they
 * are, with NeoForge's enum values ({@code ModAnnotation.EnumHolder}) made Forge's ({@code ModFileScanData.EnumData}).
 * Forge's {@code Bus.FORGE} gets its bus group from the host ({@code ForgeEventBusPatch} in the Forge bridge), as
 * Forge's {@code Bindings} need Forge's launch.
 *
 * <p>Thread: FML's worker thread that loads the mod; stateless.
 */
final class ForgeEventSubscribers {

    static final String MOD = "Lnet/minecraftforge/fml/common/Mod;";
    static final String EVENT_BUS_SUBSCRIBER = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";

    private static final Set<String> COPIED = Set.of(MOD, EVENT_BUS_SUBSCRIBER);

    private ForgeEventSubscribers() {}

    /** Forge's scan data with the {@code @Mod} and {@code @Mod.EventBusSubscriber} annotations of NeoForge's {@code scan}. */
    static net.minecraftforge.forgespi.language.ModFileScanData of(ModFileScanData scan) {
        net.minecraftforge.forgespi.language.ModFileScanData forge =
                new net.minecraftforge.forgespi.language.ModFileScanData();
        for (ModFileScanData.AnnotationData annotation : scan.getAnnotations()) {
            if (!COPIED.contains(annotation.annotationType().getDescriptor())) {
                continue;
            }
            Map<String, Object> values = values(annotation.annotationData());
            forge.getAnnotations()
                    .add(new net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData(
                            annotation.annotationType(),
                            annotation.targetType(),
                            annotation.clazz(),
                            annotation.memberName(),
                            values));
        }
        return forge;
    }

    /** How many {@code @Mod.EventBusSubscriber} classes {@code forge} has. */
    static long subscribers(net.minecraftforge.forgespi.language.ModFileScanData forge) {
        return forge.getAnnotations().stream()
                .filter(annotation ->
                        annotation.annotationType().getDescriptor().equals(EVENT_BUS_SUBSCRIBER))
                .count();
    }

    private static Map<String, Object> values(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> copy.put(name, value(value)));
        return copy;
    }

    private static Object value(Object value) {
        if (value instanceof ModAnnotation.EnumHolder holder) {
            return new net.minecraftforge.forgespi.language.ModFileScanData.EnumData(
                    Type.getType(holder.desc()), holder.value());
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(value(element));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((name, element) -> copy.put(name, value(element)));
            return copy;
        }
        return value;
    }
}
