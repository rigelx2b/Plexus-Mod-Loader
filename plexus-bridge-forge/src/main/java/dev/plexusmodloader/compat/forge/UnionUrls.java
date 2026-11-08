package dev.plexusmodloader.compat.forge;

import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;

/**
 * The JVM's handler for {@code union:} URLs, the locations of FML's jar modules, from securejarhandler's {@code
 * ModularURLHandler}. Used by the hosts whose securejarhandler is on the JVM class path: NeoForge's FML 2 and 4, and
 * Forge 47 in an installed launcher profile.
 *
 * <p>Thread: called once, on the launch thread, before FML defines its modules.
 */
final class UnionUrls {

    private static final String HANDLER = "cpw.mods.cl.ModularURLHandler";

    private UnionUrls() {}

    /** Whether {@code union:} URLs can be made already (a handler is registered). */
    static boolean installed() {
        try {
            URI.create("union:/plexus").toURL();
            return true;
        } catch (MalformedURLException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether securejarhandler's {@code ModularURLHandler} is on the JVM class path (Forge 26.x has none there). */
    static boolean available() {
        try {
            Class.forName(HANDLER, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Makes {@code union:} URLs (the locations of FML's jar modules) work: securejarhandler 3 has no URL handler service.
     * NeoForge's bootstrap sets {@code ModularURLHandler} as the JVM's URL stream handler factory; so does this.
     */
    static void install(ModuleLayer boot) throws ReflectiveOperationException {
        Class<?> handler = Class.forName(HANDLER, true, ClassLoader.getSystemClassLoader());
        handler.getMethod("initFrom", ModuleLayer.class).invoke(null, boot);
        Object instance = handler.getField("INSTANCE").get(null);
        if (((java.net.URLStreamHandlerFactory) instance).createURLStreamHandler("union") == null) {
            // securejarhandler 2 (FML 2) offers its handler only as a service of its module, and here the jar is on the
            // class path (FML's file systems need it there), not a module of the BOOT layer: register it by hand.
            Class<?> union = Class.forName("cpw.mods.cl.UnionURLStreamHandler", true, handler.getClassLoader());
            Class<?> provider =
                    Class.forName("cpw.mods.cl.ModularURLHandler$IURLProvider", true, handler.getClassLoader());
            Field handlers = handler.getDeclaredField("handlers");
            handlers.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> known = (java.util.Map<String, Object>) handlers.get(instance);
            Object created = union.getDeclaredConstructor().newInstance();
            known.put((String) provider.getMethod("protocol").invoke(created), created);
        }
        try {
            java.net.URL.setURLStreamHandlerFactory((java.net.URLStreamHandlerFactory) instance);
        } catch (Error alreadySet) {
            throw new IllegalStateException(
                    "The JVM's URL stream handler factory is set already, so union: URLs (FML's"
                            + " jar modules) cannot be opened: " + alreadySet,
                    alreadySet);
        }
    }
}
