/*
 * This file is part of ViaBackwards - https://github.com/ViaVersion/ViaBackwards
 * Copyright (C) 2016-2025 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viabackwards.api.entities;

import com.viaversion.viabackwards.api.rewriters.EntityRewriter;
import com.viaversion.viaversion.api.data.entity.StoredEntityData;
import com.viaversion.viaversion.api.minecraft.entities.EntityType;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.protocol.packet.ClientboundPacketType;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataHandlerEvent;

import java.util.HashMap;
import java.util.Map;
import com.viaversion.viaversion.api.data.FullMappings;

/**
 * A modular helper for tracking and injecting entity scaling on older clients.
 * When older clients lack a specific entity, ViaBackwards maps it to a different entity.
 * Sometimes this mapping requires the entity to be scaled (e.g. mapping a tiny baby mob to a large adult mob).
 * 
 * Example Guidance for Future Developers:
 * If Mojang adds a "Vulture" and "Baby Vulture", and you map the Adult Vulture to a Bat:
 *   - The Bat is inherently small.
 *   - You might need to scale UP the *entire* Bat so it looks like an Adult Vulture. 
 *   - If *that* happens, you would apply a baseline scale increase (e.g. 2.0x) to both Adult and Baby.
 *   - However, since there is no "Baby Bat", you would *also* need to track `isBaby` here.
 *   - If `isBaby` is true, you'd apply a *reduced* scale relative to the Adult Vulture's new scale.
 *   - Therefore, you should always verify the math: (Desired Baby Size) / (Fallback Entity Baseline Size).
 */
public class EntityScaleHelper {

    private static final int BABY_INDEX = 16; // Standard Ageable "is_baby" index
    private final Map<EntityType, Float> babyScales = new HashMap<>();
    private final String scaleAttributeId;
    private final ClientboundPacketType updateAttributesPacket;

    public EntityScaleHelper(String scaleAttributeId, ClientboundPacketType updateAttributesPacket) {
        this.scaleAttributeId = scaleAttributeId;
        this.updateAttributesPacket = updateAttributesPacket;
    }

    /**
     * Registers a scaling factor for a specific baby entity type.
     * @param type The entity type that requires scaling when it is a baby.
     * @param babyScale The multiplier to apply to the generic.scale attribute when it is a baby.
     */
    public void addBabyScale(EntityType type, float babyScale) {
        babyScales.put(type, babyScale);
    }

    /**
     * Checks the metadata, tracks the scale state natively, and injects an UPDATE_ATTRIBUTES
     * packet down the pipeline right as the metadata is processed by the client.
     * Use this inside an Entity Rewriter's filter().handler().
     *
     * @param event The meta packet processing event.
     * @param data The current metadata piece being processed.
     * @param protocol The protocol handling the translation, used for mapping lookups.
     */
    public void trackAndInject(EntityDataHandlerEvent event, EntityData data, Protocol protocol) {
        if (data.id() == BABY_INDEX && data.value() instanceof Boolean) {
            StoredEntityData storedEntityData = event.user().getEntityTracker(protocol.getClass()).entityData(event.entityId());
            if (storedEntityData == null) return;
            
            // Check if this protocol layer actually cares about scaling this entity
            Float babyScaleFactor = babyScales.get(storedEntityData.type());
            if (babyScaleFactor == null) {
                return; // Not registered for this protocol, do nothing
            }

            EntityScaleData scaleData = storedEntityData.get(EntityScaleData.class);
            if (scaleData == null) {
                scaleData = new EntityScaleData();
                storedEntityData.put(scaleData);
            }
            
            boolean isBaby = (Boolean) data.value();
            float scale = isBaby ? babyScaleFactor : 1.0f;
            
            if (scaleData.isBaby() != isBaby || scaleData.getScale() != scale) {
                scaleData.setBaby(isBaby);
                scaleData.setScale(scale);
                
                // Actively inject the packet so the client receives the scale update immediately
                try {
                    PacketWrapper updatePacket = PacketWrapper.create(updateAttributesPacket, event.user());
                    updatePacket.write(Types.VAR_INT, event.entityId());
                    updatePacket.write(Types.VAR_INT, 1); // 1 attribute
                    
                    FullMappings attributeMappings = protocol.getMappingData().getAttributeMappings();
                    int serverId = attributeMappings != null ? attributeMappings.id(scaleAttributeId) : -1;
                    int mappedId = serverId != -1 ? protocol.getMappingData().getNewAttributeId(serverId) : -1;
                    
                    if (mappedId != -1) {
                        updatePacket.write(Types.VAR_INT, mappedId);
                        updatePacket.write(Types.DOUBLE, (double) scaleData.getScale());
                        updatePacket.write(Types.VAR_INT, 0); // 0 modifiers
                        updatePacket.scheduleSend(protocol.getClass());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}