package dev.plexusmodloader.api.content.attribute;

import dev.plexusmodloader.api.content.ContentHandle;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** A declared mod attribute; pass {@link #id()} to {@code LivingEntityView.attribute} and its relatives. */
@ApiStability(StabilityLevel.PREVIEW)
public interface AttributeHandle extends ContentHandle {}
