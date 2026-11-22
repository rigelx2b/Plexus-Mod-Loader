package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.RedirectCall;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge on NeoForge, F-N6 networking (ADR-0051 decision 22): Forge's own network classes in its {@code universal} jar
 * call two members of MinecraftForge's patched game that NeoForge's game does not have, so the NeoForge host rewrites
 * those calls with Plexus Mod Loader's transform rules ({@link #rules}), to hooks of the game layer ({@code
 * ForgeNetworkHooks}):
 *
 * <ul>
 *   <li>{@code Connection.getProtocol()} in {@code Channel.toVanillaPacket} (a send to one connection, and {@code
 *       reply}) and in {@code Channel.validate} (every message a {@code SimpleChannel} sends to a connection or
 *       receives);
 *   <li>Forge's {@code ServerLifecycleHooks.getCurrentServer()} in {@code PacketDistributor.getServer()} (the {@code
 *       ALL}, {@code DIMENSION} and {@code NEAR} targets), whose field only Forge's own server start sets.
 * </ul>
 *
 * <p>Each rule replaces one three-byte call with a three-byte {@code invokestatic} of the same stack effect, so no stack
 * map frame changes. Checked with {@code javap -c} on Forge 26.1-62.0.9, 26.1.1-63.0.2, 26.1.2-64.1.5, 26.2-65.1.5 and
 * 26.3-66.1.0: each target method has exactly one such call. The pre-launch check ({@link #missing}) confirms that for
 * the jar the host runs; the rules' own failure policy only disables a rule, so a jar that fails the check leaves the
 * Forge mods out instead ({@code PLEXUS-COMPAT-229}).
 *
 * <p>Thread: stateless.
 */
final class ForgeNetworkRules {

    static final String CHANNEL = "net/minecraftforge/network/Channel";
    static final String PACKET_DISTRIBUTOR = "net/minecraftforge/network/PacketDistributor";
    static final String HOOKS = "ForgeNetworkHooks";

    static final MethodTarget GET_PROTOCOL = new MethodTarget(
            "net/minecraft/network/Connection", "getProtocol", "()Lnet/minecraft/network/ConnectionProtocol;");
    static final MethodTarget GET_CURRENT_SERVER = new MethodTarget(
            "net/minecraftforge/server/ServerLifecycleHooks",
            "getCurrentServer",
            "()Lnet/minecraft/server/MinecraftServer;");

    static final String CLIENT_HOOKS = "client/ForgeNetworkClientHooks";
    private static final Set<GameSide> BOTH = Set.of(GameSide.CLIENT, GameSide.DEDICATED_SERVER);
    private static final String PACKET = "Lnet/minecraft/network/protocol/Packet;";

    /**
     * A rewritten call: in which method of Forge's, which call, to which hook (a class of the game layer's package and
     * its method), on which sides, under which rule ID.
     */
    record Redirect(
            String ruleId, MethodTarget method, MethodTarget invoked, String hooks, String hook, Set<GameSide> sides) {
        Redirect(String ruleId, MethodTarget method, MethodTarget invoked, String hook) {
            this(ruleId, method, invoked, HOOKS, hook, BOTH);
        }
    }

    static final List<Redirect> REDIRECTS = List.of(
            new Redirect(
                    "plexus:forge_channel_send_protocol",
                    new MethodTarget(
                            CHANNEL,
                            "toVanillaPacket",
                            "(Lnet/minecraft/network/Connection;Ljava/lang/Object;)"
                                    + "Lnet/minecraft/network/protocol/Packet;"),
                    GET_PROTOCOL,
                    "connectionProtocol"),
            new Redirect(
                    "plexus:forge_channel_validate_protocol",
                    new MethodTarget(
                            CHANNEL,
                            "validate",
                            "(Ljava/lang/Object;Lnet/minecraft/network/Connection;"
                                    + "Lnet/minecraftforge/network/NetworkProtocol;"
                                    + "Lnet/minecraft/network/protocol/PacketFlow;Z)V"),
                    GET_PROTOCOL,
                    "connectionProtocol"),
            new Redirect(
                    "plexus:forge_packet_distributor_server",
                    new MethodTarget(PACKET_DISTRIBUTOR, "getServer", "()Lnet/minecraft/server/MinecraftServer;"),
                    GET_CURRENT_SERVER,
                    "currentServer"),
            // Peers without a Forge channel (ADR-0051 22, second amendment): send only to those that have it.
            new Redirect(
                    "plexus:forge_distributor_player_channel",
                    new MethodTarget(
                            PACKET_DISTRIBUTOR,
                            "lambda$playerConsumer$0",
                            "(Lnet/minecraft/server/level/ServerPlayer;" + PACKET + ")V"),
                    new MethodTarget("net/minecraft/network/Connection", "send", "(" + PACKET + ")V"),
                    "sendIfChannel"),
            new Redirect(
                    "plexus:forge_channel_connection_channel",
                    new MethodTarget(CHANNEL, "send", "(Ljava/lang/Object;Lnet/minecraft/network/Connection;)V"),
                    new MethodTarget("net/minecraft/network/Connection", "send", "(" + PACKET + ")V"),
                    "sendIfChannel"),
            new Redirect(
                    "plexus:forge_distributor_all_channel",
                    new MethodTarget(PACKET_DISTRIBUTOR, "lambda$playerListAll$0", "(" + PACKET + ")V"),
                    new MethodTarget("net/minecraft/server/players/PlayerList", "broadcastAll", "(" + PACKET + ")V"),
                    "broadcastIfChannel"),
            new Redirect(
                    "plexus:forge_distributor_server_channel",
                    new MethodTarget(PACKET_DISTRIBUTOR, "lambda$clientToServer$0", "(" + PACKET + ")V"),
                    new MethodTarget(
                            "net/minecraft/client/multiplayer/ClientPacketListener", "send", "(" + PACKET + ")V"),
                    CLIENT_HOOKS,
                    "sendIfChannel",
                    Set.of(GameSide.CLIENT)));

    private ForgeNetworkRules() {}

    /** The rules for {@code gameVersion}, with hooks in {@code gameLayerPackage}'s {@code ForgeNetworkHooks}. */
    static List<TransformRuleDefinition> rules(String gameVersion, String gameLayerPackage) {
        return REDIRECTS.stream()
                .map(redirect -> new TransformRuleDefinition(
                        redirect.ruleId(),
                        "plexus",
                        gameVersion,
                        redirect.sides(),
                        redirect.method(),
                        new RedirectCall(
                                redirect.invoked(), 0, gameLayerPackage + "/" + redirect.hooks(), redirect.hook()),
                        TransformFailurePolicy.DISABLE_RULE))
                .toList();
    }

    /**
     * What Forge's {@code universal} jar lacks for {@link #rules}: one line for each target method that does not have
     * exactly one call the rule rewrites. Empty when every rule fits.
     *
     * @throws IOException when the jar or one of its classes cannot be read
     */
    static List<String> missing(Path universal) throws IOException {
        List<String> missing = new ArrayList<>();
        try (JarFile jar = new JarFile(universal.toFile())) {
            for (Redirect redirect : REDIRECTS) {
                String classFile = redirect.method().ownerInternalName() + ".class";
                JarEntry entry = jar.getJarEntry(classFile);
                int calls = -1;
                if (entry != null) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        calls = calls(input.readAllBytes(), redirect);
                    } catch (IllegalArgumentException e) {
                        throw new IOException(classFile + " in " + universal.getFileName() + ": " + e, e);
                    }
                }
                if (calls != 1) {
                    missing.add(redirect.method().ownerInternalName() + "."
                            + redirect.method().name() + " has "
                            + (calls < 0 ? "no method" : calls + " call(s)") + " of "
                            + redirect.invoked().ownerInternalName() + "."
                            + redirect.invoked().name() + ", not 1");
                }
            }
        }
        return missing;
    }

    /**
     * How many calls of {@code redirect}'s member its target method has in the class {@code bytes}, or -1 when the class
     * does not declare that method.
     */
    static int calls(byte[] bytes, Redirect redirect) {
        int[] count = {-1};
        MethodTarget method = redirect.method();
        MethodTarget invoked = redirect.invoked();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!name.equals(method.name()) || !descriptor.equals(method.descriptor())) {
                                    return null;
                                }
                                count[0] = 0;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String member, String type, boolean isInterface) {
                                        if (opcode != Opcodes.INVOKESPECIAL
                                                && owner.equals(invoked.ownerInternalName())
                                                && member.equals(invoked.name())
                                                && type.equals(invoked.descriptor())) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
