// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/model/loading/UnbakedModelParserDeserializerMixin.java
// (written in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.model.loading;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;
import net.fabricmc.fabric.impl.client.model.loading.UnbakedModelJsonDeserializer;
import net.minecraft.client.resources.model.UnbakedModel;
import net.neoforged.neoforge.client.model.UnbakedModelParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UnbakedModelParser.Deserializer.class)
public class UnbakedModelParserDeserializerMixin {

    @Inject(
            method = "deserialize",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/google/gson/JsonDeserializationContext;deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;)Ljava/lang/Object;"),
            cancellable = true)
    private static void deserializeFabricModel(
            JsonElement jsonElement,
            Type type,
            JsonDeserializationContext jsonDeserializationContext,
            CallbackInfoReturnable<UnbakedModel> cir)
            throws JsonParseException {
        UnbakedModel fabricModel =
                UnbakedModelJsonDeserializer.INSTANCE.deserialize(jsonElement, type, jsonDeserializationContext);
        if (fabricModel != null) {
            cir.setReturnValue(fabricModel);
        }
    }
}
