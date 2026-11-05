package dev.plexusmodloader.game.layer.v1_20_1.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.plexusmodloader.api.client.CameraPerspective;
import dev.plexusmodloader.api.client.ClientEvents;
import dev.plexusmodloader.api.client.ClientView;
import dev.plexusmodloader.api.client.HudCanvas;
import dev.plexusmodloader.api.client.ModScreen;
import dev.plexusmodloader.api.content.entity.EntityDefinition;
import dev.plexusmodloader.api.content.particle.ParticleDefinition;
import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.world.WorldView;
import dev.plexusmodloader.game.layer.v1_20_1.LayerState;
import dev.plexusmodloader.game.layer.v1_20_1.ModReloadListener;
import dev.plexusmodloader.game.layer.v1_20_1.Networking;
import dev.plexusmodloader.game.layer.v1_20_1.SyncChannel;
import dev.plexusmodloader.game.layer.v1_20_1.Views;
import dev.plexusmodloader.game.layer.v1_20_1.content.Particles;
import dev.plexusmodloader.game.layer.v1_20_1.content.PlexusMenu;
import dev.plexusmodloader.game.layer.v1_20_1.content.PlexusMenus;
import dev.plexusmodloader.game.layer.v1_20_1.entity.EntityInstaller;
import dev.plexusmodloader.game.version.layer.ChannelEndpoint;
import dev.plexusmodloader.game.version.layer.ConfigurationEndpoint;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import dev.plexusmodloader.game.version.layer.NativeDeclarations;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.BlockEntityRendererDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.HudLayerDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.KeyBindingDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.KeyState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only hooks for Minecraft 1.20.1; only rules for the client side point here. */
public final class ClientHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static final Set<String> FAILED_LAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<MenuType<?>> OPENED_MENUS = ConcurrentHashMap.newKeySet();
    private static volatile boolean keyMappingsAdded;
    private static volatile boolean hudReported;

    private ClientHooks() {}

    /** Head of {@code Options.load()}: adds mod key bindings before saved bindings are read. */
    public static void onOptionsLoad(Options options) {
        if (keyMappingsAdded) {
            return;
        }
        LayerState.context().ifPresent(context -> {
            List<KeyBindingDeclaration> declarations = context.declarations().keyBindings();
            if (declarations.isEmpty()) {
                return;
            }
            List<KeyMapping> added = new ArrayList<>();
            for (KeyBindingDeclaration declaration : declarations) {
                // 1.20.1 names a key category with a translation key; a mod's lang file can name it.
                KeyMapping mapping = new KeyMapping(
                        "key." + declaration.id().namespace() + "."
                                + declaration.id().path(),
                        declaration.defaultKey(),
                        "key.categories." + declaration.ownerModId());
                declaration.bind().accept(new KeyState() {
                    @Override
                    public boolean isDown() {
                        return mapping.isDown();
                    }

                    @Override
                    public boolean consumeClick() {
                        return mapping.consumeClick();
                    }
                });
                added.add(mapping);
            }
            try {
                KeyMapping[] all = Arrays.copyOf(options.keyMappings, options.keyMappings.length + added.size());
                for (int i = 0; i < added.size(); i++) {
                    all[options.keyMappings.length + i] = added.get(i);
                }
                Field field = Options.class.getDeclaredField("keyMappings");
                field.setAccessible(true);
                field.set(options, all);
                keyMappingsAdded = true;
                LOGGER.info("[Plexus Mod Loader] Added {} mod key binding(s) to the controls", added.size());
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[Plexus Mod Loader] PLEXUS-CLIENT-004 could not add mod key bindings to the controls", e);
            }
        });
    }

    /** Head of {@code KeyboardHandler.keyPress(long, int, int, int, int)}: {@code ClientEvents.KEY_INPUT}. */
    public static void onKeyPress(
            KeyboardHandler handler, long window, int key, int scancode, int action, int modifiers) {
        GameLayerContext context = inputListeners(window, ClientEvents.KEY_INPUT);
        if (context != null) {
            InputCodes.action(action).ifPresent(keyAction -> {
                ClientView view = new MinecraftView(Minecraft.getInstance());
                int code = InputCodes.key(key);
                context.events().publish(ClientEvents.KEY_INPUT, l -> l.onKeyInput(view, code, keyAction));
            });
        }
    }

    /** Head of {@code MouseHandler.onPress(long, int, int, int)}: {@code ClientEvents.MOUSE_BUTTON}. */
    public static void onMouseButton(MouseHandler handler, long window, int button, int action, int modifiers) {
        GameLayerContext context = inputListeners(window, ClientEvents.MOUSE_BUTTON);
        int number = InputCodes.mouseButton(button);
        if (context != null && number >= 0) {
            InputCodes.action(action).ifPresent(keyAction -> {
                ClientView view = new MinecraftView(Minecraft.getInstance());
                context.events().publish(ClientEvents.MOUSE_BUTTON, l -> l.onMouseButton(view, number, keyAction));
            });
        }
    }

    /** Head of {@code MouseHandler.onScroll(long, double, double)}: {@code ClientEvents.MOUSE_SCROLL}. */
    public static void onMouseScroll(MouseHandler handler, long window, double horizontal, double vertical) {
        GameLayerContext context = inputListeners(window, ClientEvents.MOUSE_SCROLL);
        if (context != null) {
            ClientView view = new MinecraftView(Minecraft.getInstance());
            context.events().publish(ClientEvents.MOUSE_SCROLL, l -> l.onMouseScroll(view, horizontal, vertical));
        }
    }

    /** The context when {@code key} has listeners and the input is for the game window, else null. */
    private static GameLayerContext inputListeners(long window, EventKey<?> key) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !context.events().hasListeners(key)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || window != minecraft.getWindow().getWindow()) {
            return null;
        }
        return context;
    }

    /** Before the return of {@code Minecraft.tick()}. */
    public static void onClientTick(Minecraft minecraft) {
        NotLoadedModsScreen.onClientTick(minecraft);
        LayerState.context().ifPresent(context -> {
            if (context.events().hasListeners(ClientEvents.TICK)) {
                ClientView view = new MinecraftView(minecraft);
                context.events().publish(ClientEvents.TICK, listener -> listener.onClientTick(view));
            }
        });
    }

    /** Before the return of {@code Gui.render(GuiGraphics, float)}. */
    public static void onRenderHud(Gui gui, GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        // The HUD is hidden with F1 through Options.hideGui.
        if (minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        LayerState.context().ifPresent(context -> {
            List<HudLayerDeclaration> layers = context.declarations().hudLayers();
            if (layers.isEmpty()) {
                return;
            }
            HudCanvas canvas = new Canvas(graphics, minecraft.font);
            for (HudLayerDeclaration layer : layers) {
                String id = layer.id().toString();
                if (FAILED_LAYERS.contains(id)) {
                    continue;
                }
                try {
                    layer.layer().render(canvas);
                } catch (RuntimeException | LinkageError e) {
                    FAILED_LAYERS.add(id);
                    LOGGER.error(
                            "[Plexus Mod Loader] PLEXUS-CLIENT-003 HUD layer {} of {} failed and is disabled",
                            id,
                            layer.ownerModId(),
                            e);
                }
            }
            if (!hudReported) {
                hudReported = true;
                LOGGER.info("[Plexus Mod Loader] Drawing {} mod HUD layer(s)", layers.size());
            }
        });
    }

    /**
     * Head of {@code ClientHandshakePacketListenerImpl.handleCustomQuery}: answers mods' configuration exchanges, which
     * are login queries in 1.20.1; {@code true} when the query belongs to one. The game's handler runs on the
     * connection's thread, so the mod runs on the client's through {@code execute}, as in later versions. A mod with no
     * answer still replies with no data, because the server waits for every query it sent.
     */
    public static boolean onLoginQuery(
            ClientHandshakePacketListenerImpl listener, ClientboundCustomQueryPacket packet) {
        ConfigurationEndpoint exchange = LayerState.configurationExchanges().get(Views.id(packet.getIdentifier()));
        if (exchange == null) {
            return false;
        }
        byte[] request = Networking.bytes(packet.getData());
        int transaction = packet.getTransactionId();
        Connection connection = loginConnection(listener);
        Minecraft.getInstance().execute(() -> {
            Optional<byte[]> answer = exchange.answer(request);
            connection.send(new ServerboundCustomQueryPacket(
                    transaction, answer.map(Networking::buffer).orElse(null)));
            if (answer.isPresent()) {
                LOGGER.info("[Plexus Mod Loader] Answered configuration exchange {}", exchange.id());
            }
        });
        return true;
    }

    private static Connection loginConnection(ClientHandshakePacketListenerImpl listener) {
        try {
            Field field = ClientHandshakePacketListenerImpl.class.getDeclaredField("connection");
            field.setAccessible(true);
            return (Connection) field.get(listener);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PLEXUS-NETWORK-005 cannot read the login's connection", e);
        }
    }

    /**
     * Head of {@code ClientPacketListener.handleCustomPayload(ClientboundCustomPayloadPacket)}; {@code true} when the
     * channel is declared. The game's handler has not moved to the client thread yet here, and would run this hook a
     * second time if it did, so the hook consumes the packet at once and moves the mod over itself.
     */
    public static boolean onClientPayload(ClientPacketListener listener, ClientboundCustomPayloadPacket packet) {
        ChannelEndpoint endpoint = LayerState.channels().get(Views.id(packet.getIdentifier()));
        if (endpoint == null) {
            return false;
        }
        // getData() of this packet makes a copy each call, which this hook owns.
        FriendlyByteBuf buffer = packet.getData();
        byte[] data;
        try {
            data = Networking.bytes(buffer);
        } finally {
            buffer.release();
        }
        Minecraft.getInstance().execute(() -> endpoint.receiveOnClient(data));
        return true;
    }

    /**
     * Head of {@code MenuScreens.create}: opens the screen of a mod menu with its own layout; the
     * game's menus continue to the game's code.
     */
    public static boolean onCreateMenuScreen(MenuType<?> type, Minecraft minecraft, int containerId, Component title) {
        if (PlexusMenus.layout(type) == null || minecraft.player == null) {
            return false;
        }
        Inventory inventory = minecraft.player.getInventory();
        PlexusMenu menu = PlexusMenus.createForClient(type, containerId, inventory);
        minecraft.player.containerMenu = menu;
        minecraft.setScreen(new PlexusContainerScreen(menu, inventory, title));
        if (OPENED_MENUS.add(type)) {
            LOGGER.info(
                    "[Plexus Mod Loader] Opened mod menu {} with {} slot(s)",
                    BuiltInRegistries.MENU.getKey(type),
                    menu.layout().slots().size());
        }
        return true;
    }

    /**
     * Before the return of {@code ParticleEngine.registerProviders()}: adds providers for mod particle types, whose
     * sprites the game loads from {@code particles/<path>.json}. The game keeps the registration method and its factory
     * interface private.
     */
    public static void onRegisterParticleProviders(ParticleEngine engine) {
        Map<ParticleType<?>, ParticleDefinition> particles = Particles.definitions();
        if (particles.isEmpty()) {
            return;
        }
        try {
            Class<?> registration = Class.forName(
                    ParticleEngine.class.getName() + "$SpriteParticleRegistration",
                    false,
                    ParticleEngine.class.getClassLoader());
            Method register = ParticleEngine.class.getDeclaredMethod("register", ParticleType.class, registration);
            register.setAccessible(true);
            for (Map.Entry<ParticleType<?>, ParticleDefinition> entry : particles.entrySet()) {
                String name = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(entry.getKey()));
                Object factory = Proxy.newProxyInstance(
                        ParticleEngine.class.getClassLoader(),
                        new Class<?>[] {registration},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "create" ->
                                new PlexusParticle.Provider((SpriteSet) arguments[0], entry.getValue(), name);
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            case "toString" -> "PlexusParticleRegistration[" + name + "]";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
                register.invoke(engine, entry.getKey(), factory);
            }
            LOGGER.info("[Plexus Mod Loader] Added {} mod particle provider(s)", particles.size());
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[Plexus Mod Loader] PLEXUS-CLIENT-005 cannot add mod particle providers", e);
        }
    }

    /** Before the return of {@code BlockEntityRenderers.createEntityRenderers}: adds mod painters. */
    public static Map<BlockEntityType<?>, BlockEntityRenderer<?>> onCreateBlockEntityRenderers(
            BlockEntityRendererProvider.Context context, Map<BlockEntityType<?>, BlockEntityRenderer<?>> renderers) {
        List<BlockEntityRendererDeclaration> declarations = LayerState.context()
                .map(layer -> layer.declarations().blockEntityRenderers())
                .orElse(List.of());
        if (declarations.isEmpty()) {
            return renderers;
        }
        Map<BlockEntityType<?>, BlockEntityRenderer<?>> all = new HashMap<>(renderers);
        for (BlockEntityRendererDeclaration declaration : declarations) {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getOptional(Views.id(declaration.block()))
                    .orElseThrow(() -> new IllegalStateException(
                            "PLEXUS-CLIENT-002 " + declaration.block() + " has no block entity type"));
            all.put(type, new PlexusBlockEntityRenderer(context, declaration));
        }
        LOGGER.info("[Plexus Mod Loader] Added {} mod block entity renderer(s)", declarations.size());
        return Map.copyOf(all);
    }

    /**
     * Before the return of {@code LevelRenderer.renderLevel}: mods' world painters draw this frame's shapes once the
     * game has drawn the world.
     */
    public static void onRenderLevel(
            LevelRenderer levelRenderer,
            PoseStack poseStack,
            float partialTick,
            long finishTimeNano,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix) {
        LayerState.context().ifPresent(context -> {
            List<NativeDeclarations.WorldPainterDeclaration> painters =
                    context.declarations().worldPainters();
            if (!painters.isEmpty()) {
                WorldPainting.paint(painters, poseStack, camera);
            }
        });
    }

    /** Before the return of {@code GameRenderer.getFov(Camera, float, boolean)}: mods may change the field of view. */
    public static double onGetFov(
            GameRenderer renderer, Camera camera, float partialTicks, boolean useFovSetting, double fov) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !context.events().hasListeners(ClientEvents.FIELD_OF_VIEW)) {
            return fov;
        }
        float[] result = {(float) fov};
        context.events().publish(ClientEvents.FIELD_OF_VIEW, listener -> result[0] = listener.modifyFov(result[0]));
        return Float.isFinite(result[0]) && result[0] > 0 ? result[0] : fov;
    }

    /**
     * Before the return of {@code ReloadableResourceManager(PackType)}: the client's resource manager
     * gets the mod asset listener.
     */
    public static void onResourceManagerCreated(ReloadableResourceManager manager, PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        List<NativeDeclarations.ReloadListenerDeclaration> assets = LayerState.context()
                .map(context -> context.declarations().reloadListeners().stream()
                        .filter(NativeDeclarations.ReloadListenerDeclaration::assets)
                        .toList())
                .orElse(List.of());
        if (!assets.isEmpty()) {
            manager.registerReloadListener(new ModReloadListener(assets));
        }
    }

    /** Whether {@link ClientEvents#JOINED_WORLD} fired for the current connection. */
    private static volatile boolean joined;

    /** Before the return of {@code ClientPacketListener.handleLogin}. */
    public static void onClientLogin(ClientPacketListener listener, ClientboundLoginPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (joined || minecraft.player == null) {
            return;
        }
        joined = true;
        SyncChannel.setLocalPlayerCheck(entity -> entity == Minecraft.getInstance().player);
        LayerState.context()
                .ifPresent(context -> context.events()
                        .publish(ClientEvents.JOINED_WORLD, l -> l.onConnection(new MinecraftView(minecraft))));
    }

    /** Head of {@code Minecraft.clearLevel(Screen)}, 1.20.1's way of leaving a world. */
    public static void onClientDisconnect(Minecraft minecraft, Screen screen) {
        if (!joined) {
            return;
        }
        joined = false;
        try {
            LayerState.context()
                    .ifPresent(context -> context.events()
                            .publish(ClientEvents.LEFT_WORLD, l -> l.onConnection(new MinecraftView(minecraft))));
        } finally {
            SyncChannel.clearClientCopies();
        }
    }

    /** Sends a packet over the client's connection. */
    public static void sendToServer(ServerboundCustomPayloadPacket packet) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            throw new IllegalStateException("PLEXUS-NETWORK-007 the client is not connected to a server");
        }
        connection.send(packet);
    }

    /**
     * The file of a post effect in 1.20.1: the game reads {@code assets/<namespace>/shaders/post/<path>.json}, where
     * 26.x reads {@code post_effect/<path>.json}.
     */
    static ResourceLocation postEffectFile(ResourceId effect) {
        return new ResourceLocation(effect.namespace(), "shaders/post/" + effect.path() + ".json");
    }

    /** The effect ID of a post chain's name ({@code namespace:shaders/post/<path>.json}), if it has that form. */
    static Optional<ResourceId> postEffectId(String chainName) {
        ResourceLocation file = ResourceLocation.tryParse(chainName);
        if (file == null
                || !file.getPath().startsWith("shaders/post/")
                || !file.getPath().endsWith(".json")) {
            return Optional.empty();
        }
        String path = file.getPath()
                .substring("shaders/post/".length(), file.getPath().length() - ".json".length());
        return Optional.of(ResourceId.of(file.getNamespace(), path));
    }

    private record MinecraftView(Minecraft minecraft) implements ClientView {

        @Override
        public Optional<PlayerView> player() {
            return Optional.ofNullable(minecraft.player).map(Views::player);
        }

        @Override
        public Optional<WorldView> world() {
            return Optional.ofNullable(minecraft.level).map(Views::world);
        }

        @Override
        public void showActionBar(String text) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(text), true);
            }
        }

        @Override
        public void openScreen(ModScreen screen) {
            minecraft.setScreen(new PlexusScreen(screen));
        }

        @Override
        public void closeScreen() {
            minecraft.setScreen(null);
        }

        @Override
        public boolean isScreenOpen() {
            return minecraft.screen != null;
        }

        @Override
        public CameraPerspective cameraPerspective() {
            return switch (minecraft.options.getCameraType()) {
                case FIRST_PERSON -> CameraPerspective.FIRST_PERSON;
                case THIRD_PERSON_BACK -> CameraPerspective.THIRD_PERSON_BACK;
                case THIRD_PERSON_FRONT -> CameraPerspective.THIRD_PERSON_FRONT;
            };
        }

        @Override
        public void setCameraPerspective(CameraPerspective perspective) {
            minecraft.options.setCameraType(CameraType.valueOf(perspective.name()));
        }

        @Override
        public boolean setPostEffect(ResourceId effect) {
            ResourceLocation file = postEffectFile(effect);
            if (minecraft.getResourceManager().getResource(file).isEmpty()) {
                return false;
            }
            try {
                Method load = GameRenderer.class.getDeclaredMethod("loadEffect", ResourceLocation.class);
                load.setAccessible(true);
                load.invoke(minecraft.gameRenderer, file);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[Plexus Mod Loader] PLEXUS-CLIENT-005 cannot apply post effect {}", effect, e);
                return false;
            }
            // loadEffect logs a broken effect and keeps the old chain; the new one is current only when it loaded.
            return postEffect().filter(effect::equals).isPresent();
        }

        @Override
        public void clearPostEffect() {
            minecraft.gameRenderer.shutdownEffect();
        }

        @Override
        public Optional<ResourceId> postEffect() {
            return Optional.ofNullable(minecraft.gameRenderer.currentEffect())
                    .flatMap(chain -> postEffectId(chain.getName()));
        }

        @Override
        public void leaveWorld() {
            if (minecraft.level == null) {
                return;
            }
            // As the pause menu's "Save and Quit to Title" / "Disconnect" does.
            boolean local = minecraft.isLocalServer();
            minecraft.level.disconnect();
            if (local) {
                minecraft.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            } else {
                minecraft.clearLevel();
            }
            minecraft.setScreen(new TitleScreen());
        }
    }

    record Canvas(GuiGraphics graphics, Font font) implements HudCanvas {

        @Override
        public int width() {
            return graphics.guiWidth();
        }

        @Override
        public int height() {
            return graphics.guiHeight();
        }

        @Override
        public int textWidth(String text) {
            return font.width(text);
        }

        @Override
        public void drawText(String text, int x, int y, int argb) {
            graphics.drawString(font, text, x, y, argb, true);
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int argb) {
            graphics.fill(x1, y1, x2, y2, argb);
        }

        @Override
        public void drawTexture(ResourceId texture, int x, int y, int width, int height) {
            // The whole texture, stretched to the rectangle: a 1x1 region of a 1x1 texture in texture units.
            graphics.blit(Views.id(texture), x, y, width, height, 0.0f, 0.0f, 1, 1, 1, 1);
        }
    }

    /** Before the return of {@code EntityRenderers.createEntityRenderers}: adds renderers for mod mobs. */
    public static Map<EntityType<?>, EntityRenderer<?>> onCreateEntityRenderers(
            EntityRendererProvider.Context context, Map<EntityType<?>, EntityRenderer<?>> renderers) {
        Map<EntityType<?>, ResourceId> textures = EntityInstaller.textures();
        Set<EntityType<?>> projectiles = EntityInstaller.projectileTypes();
        if (textures.isEmpty() && projectiles.isEmpty()) {
            return renderers;
        }
        Map<EntityType<?>, EntityRenderer<?>> all = new HashMap<>(renderers);
        Map<EntityType<?>, EntityDefinition> definitions = EntityInstaller.definitions();
        textures.forEach((type, texture) -> {
            EntityDefinition definition = definitions.get(type);
            all.put(
                    type,
                    definition != null && definition.model().isPresent()
                            ? new PlexusModelRenderer(
                                    context,
                                    PlexusEntityModel.create(definition.model().get()),
                                    Views.id(texture),
                                    definition.width() * 0.5f,
                                    String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(type)))
                            : new PlexusHumanoidRenderer(context, Views.id(texture)));
        });
        projectiles.forEach(type -> all.put(type, newThrownItemRenderer(context)));
        LOGGER.info("[Plexus Mod Loader] Added {} mod entity renderer(s)", textures.size() + projectiles.size());
        return Map.copyOf(all);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityRenderer<?> newThrownItemRenderer(EntityRendererProvider.Context context) {
        return new ThrownItemRenderer(context);
    }
}
