/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.image.protocolize;

import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.Protocol;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.module.ProtocolizeModule;
import dev.simplix.protocolize.api.providers.MappingProvider;
import dev.simplix.protocolize.api.providers.ProtocolRegistrationProvider;
import dev.simplix.protocolize.data.packets.HeldItemChange;
import dev.simplix.protocolize.data.packets.SetSlot;
import xyz.kyngs.librelogin.common.image.protocolize.packet.MapDataPacket;

import java.lang.reflect.Method;
import java.util.Arrays;

import static dev.simplix.protocolize.api.mapping.AbstractProtocolMapping.rangedIdMapping;

public class ProtocolizeImageModule implements ProtocolizeModule {

    @Override
    public void registerMappings(MappingProvider mappingProvider) {
        // Register item type mappings for protocol versions 770-774 (1.21.5 - 1.21.11)
        // DataModule only registers up to 769. Use reflection to call its private method.
        try {
            Class<?> dataModuleClass = Class.forName("dev.simplix.protocolize.data.DataModule");
            Object dataModule = dataModuleClass.getDeclaredConstructor().newInstance();
            Method registerMethod = dataModuleClass.getDeclaredMethod("registerMappingsForProtocol",
                    MappingProvider.class, int.class);
            registerMethod.setAccessible(true);
            for (int version = 770; version <= 774; version++) {
                registerMethod.invoke(dataModule, mappingProvider, version);
            }
        } catch (Exception e) {
            System.err.println("[LibreLogin] Failed to register extended item mappings: " + e.getMessage());
        }
    }

    @Override
    public void registerPackets(ProtocolRegistrationProvider protocolRegistrationProvider) {
        protocolRegistrationProvider.registerPacket(MapDataPacket.MAPPINGS, Protocol.PLAY, PacketDirection.CLIENTBOUND, MapDataPacket.class);

        // Register SetSlot and HeldItemChange for protocol versions 770-774
        protocolRegistrationProvider.registerPacket(Arrays.asList(
                rangedIdMapping(770, 774, 0x14)
        ), Protocol.PLAY, PacketDirection.CLIENTBOUND, SetSlot.class);

        protocolRegistrationProvider.registerPacket(Arrays.asList(
                rangedIdMapping(770, 772, 0x62),
                rangedIdMapping(773, 774, 0x67)
        ), Protocol.PLAY, PacketDirection.CLIENTBOUND, HeldItemChange.class);

        protocolRegistrationProvider.registerPacket(Arrays.asList(
                rangedIdMapping(770, 770, 0x33),
                rangedIdMapping(771, 774, 0x34)
        ), Protocol.PLAY, PacketDirection.SERVERBOUND, HeldItemChange.class);
    }

}
