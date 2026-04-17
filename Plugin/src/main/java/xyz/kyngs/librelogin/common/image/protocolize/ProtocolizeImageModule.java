/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.image.protocolize;

import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.Protocol;
import dev.simplix.protocolize.api.module.ProtocolizeModule;
import dev.simplix.protocolize.api.providers.MappingProvider;
import dev.simplix.protocolize.api.providers.ProtocolRegistrationProvider;
import dev.simplix.protocolize.data.packets.HeldItemChange;
import dev.simplix.protocolize.data.packets.SetSlot;
import xyz.kyngs.librelogin.common.image.protocolize.packet.MapDataPacket;

import java.util.Arrays;
import java.util.List;

import static dev.simplix.protocolize.api.mapping.AbstractProtocolMapping.rangedIdMapping;

public class ProtocolizeImageModule implements ProtocolizeModule {

    @Override
    public void registerMappings(MappingProvider mappingProvider) {

    }

    @Override
    public void registerPackets(ProtocolRegistrationProvider protocolRegistrationProvider) {
        protocolRegistrationProvider.registerPacket(MapDataPacket.MAPPINGS, Protocol.PLAY, PacketDirection.CLIENTBOUND, MapDataPacket.class);

        // Register SetSlot and HeldItemChange for protocol versions 770-774 (1.21.5 - 1.21.11)
        // These are missing from protocolize-data-bundle which only covers up to 769
        protocolRegistrationProvider.registerPacket(Arrays.asList(
                rangedIdMapping(770, 774, 0x14)  // SetSlot: 1.21.5 - 1.21.11
        ), Protocol.PLAY, PacketDirection.CLIENTBOUND, SetSlot.class);

        protocolRegistrationProvider.registerPacket(Arrays.asList(
                rangedIdMapping(770, 772, 0x62), // HeldItemChange CB: 1.21.5 - 1.21.8
                rangedIdMapping(773, 774, 0x67)  // HeldItemChange CB: 1.21.9 - 1.21.11
        ), Protocol.PLAY, PacketDirection.CLIENTBOUND, HeldItemChange.class);

        protocolRegistrationProvider.registerPacket(Arrays.asList(
                rangedIdMapping(770, 770, 0x33), // HeldItemChange SB: 1.21.5
                rangedIdMapping(771, 774, 0x34)  // HeldItemChange SB: 1.21.6 - 1.21.11
        ), Protocol.PLAY, PacketDirection.SERVERBOUND, HeldItemChange.class);
    }

}
