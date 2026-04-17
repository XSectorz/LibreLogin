/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.image.protocolize;

import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.item.ItemStack;
import dev.simplix.protocolize.api.providers.ModuleProvider;
import dev.simplix.protocolize.api.util.ProtocolVersions;
import dev.simplix.protocolize.data.ItemType;
import dev.simplix.protocolize.data.packets.HeldItemChange;
import dev.simplix.protocolize.data.packets.SetSlot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import xyz.kyngs.librelogin.api.image.ImageProjector;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.image.protocolize.packet.MapDataPacket;

import java.awt.image.BufferedImage;

public class ProtocolizeImageProjector<P, S> extends AuthenticImageProjector<P, S> implements ImageProjector<P> {

    // FILLED_MAP item IDs per protocol version
    private static final int[][] FILLED_MAP_IDS = {
        {393, 769, 1031},  // 1.13 - 1.21.4
        {770, 770, 1042},  // 1.21.5
        {771, 772, 1059},  // 1.21.6 - 1.21.8
        {773, 774, 1104},  // 1.21.9 - 1.21.11
    };

    // SetSlot packet IDs
    private static final int[][] SET_SLOT_IDS = {
        {770, 774, 0x14},
    };

    // HeldItemChange clientbound packet IDs
    private static final int[][] HELD_ITEM_CB_IDS = {
        {770, 772, 0x62},
        {773, 774, 0x67},
    };

    // HeldItemChange serverbound packet IDs
    private static final int[][] HELD_ITEM_SB_IDS = {
        {770, 770, 0x33},
        {771, 774, 0x34},
    };

    // MapId component type IDs
    private static final int[][] MAP_ID_COMPONENT_IDS = {
        {766, 769, 26},   // minecraft:map_id component type ID
        {770, 774, 26},   // Same component type ID
    };

    public ProtocolizeImageProjector(AuthenticLibreLogin<P, S> plugin) {
        super(plugin);
    }

    public boolean compatible() {
        return !Protocolize.version().equals("2.2.2");
    }

    @Override
    public void enable() {
        Protocolize.getService(ModuleProvider.class).registerModule(new ProtocolizeImageModule());
    }

    @Override
    public void project(BufferedImage image, P player) {
        var id = platformHandle.getUUIDForPlayer(player);
        var protocolize = Protocolize.playerProvider().player(id);
        var protocol = protocolize.protocolVersion();

        if (protocol >= 770) {
            // Bypass Protocolize entirely for 1.21.5+ — send all packets as raw bytes
            projectRaw(image, player, protocol);
        } else {
            // Use Protocolize for older versions (works fine up to 769)
            projectProtocolize(image, player, protocol, protocolize);
        }
    }

    private void projectProtocolize(BufferedImage image, P player, int protocol, dev.simplix.protocolize.api.player.ProtocolizePlayer protocolize) {
        var item = new ItemStack(ItemType.FILLED_MAP, 1, (short) 0);

        if (protocol >= ProtocolVersions.MINECRAFT_1_17) {
            item.nbtData().putInt("map", 0);
        }

        protocolize.sendPacket(new SetSlot().slot((short) 36).itemStack(item));
        protocolize.sendPacketToServer(new HeldItemChange().newSlot((short) 0));
        protocolize.sendPacket(new HeldItemChange().newSlot((short) 0));

        byte[] data = renderQR(image);
        protocolize.sendPacket(new MapDataPacket(0, (byte) 0, new MapData(128, 128, 0, 0, data)));
    }

    private void projectRaw(BufferedImage image, P player, int protocol) {
        try {
            Channel channel = getChannel(player);
            if (channel == null) return;

            int filledMapId = lookup(FILLED_MAP_IDS, protocol);
            int setSlotPacketId = lookup(SET_SLOT_IDS, protocol);
            int heldItemCBPacketId = lookup(HELD_ITEM_CB_IDS, protocol);
            int mapDataPacketId = -1;
            for (var mapping : MapDataPacket.MAPPINGS) {
                if (mapping.inRange(protocol)) {
                    mapDataPacketId = mapping.id();
                    break;
                }
            }

            if (filledMapId == -1 || setSlotPacketId == -1 || heldItemCBPacketId == -1 || mapDataPacketId == -1) return;

            // 1. Send SetSlot — put filled map in slot 36 (hotbar slot 0)
            sendRawSetSlot(channel, protocol, setSlotPacketId, filledMapId);

            // 2. Send HeldItemChange clientbound — select slot 0
            sendRawHeldItemChange(channel, heldItemCBPacketId, (short) 0);

            // 3. Send MapDataPacket — QR code image
            byte[] data = renderQR(image);
            sendRawMapData(channel, protocol, mapDataPacketId, 0, (byte) 0, data);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendRawSetSlot(Channel channel, int protocol, int packetId, int filledMapId) {
        ByteBuf buf = Unpooled.buffer();
        writeVarInt(buf, packetId);

        // Window ID (0 = player inventory)
        buf.writeByte(0);
        // State ID (varint, 0)
        writeVarInt(buf, 0);
        // Slot (short, 36 = hotbar slot 0)
        buf.writeShort(36);

        // Item: present=true, itemId=filledMapId, count=1, components
        buf.writeBoolean(true);             // present
        writeVarInt(buf, filledMapId);       // item ID
        buf.writeByte(1);                   // count

        // Components: added count=1 (map_id), removed count=0
        writeVarInt(buf, 1);  // number of components to add
        writeVarInt(buf, 0);  // number of components to remove

        // map_id component (type 26, value = varint 0)
        writeVarInt(buf, 26); // component type ID for map_id
        writeVarInt(buf, 0);  // map id value

        channel.writeAndFlush(buf);
    }

    private void sendRawHeldItemChange(Channel channel, int packetId, short slot) {
        ByteBuf buf = Unpooled.buffer();
        writeVarInt(buf, packetId);
        buf.writeByte(slot);
        channel.writeAndFlush(buf);
    }

    private void sendRawMapData(Channel channel, int protocol, int packetId, int mapId, byte scale, byte[] data) {
        ByteBuf buf = Unpooled.buffer();
        writeVarInt(buf, packetId);

        writeVarInt(buf, mapId);  // map ID
        buf.writeByte(scale);      // scale
        buf.writeBoolean(false);   // locked
        buf.writeBoolean(false);   // has icons

        // Map update data
        buf.writeByte(128);        // columns
        buf.writeByte(128);        // rows
        buf.writeByte(0);          // x offset
        buf.writeByte(0);          // z offset
        writeVarInt(buf, data.length);
        buf.writeBytes(data);

        channel.writeAndFlush(buf);
    }

    private byte[] renderQR(BufferedImage image) {
        if (image.getWidth() != 128 || image.getHeight() != 128) {
            var resized = new BufferedImage(128, 128, image.getType());
            var graphics = resized.createGraphics();
            graphics.drawImage(image, 0, 0, 128, 128, 0, 0, image.getWidth(), image.getHeight(), null);
            graphics.dispose();
            image = resized;
        }

        int[] pixels = image.getRGB(0, 0, 128, 128, null, 0, 128);
        byte[] data = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            data[i] = (byte) (pixels[i] == -16777216 ? 116 : 56);
        }
        return data;
    }

    private Channel getChannel(P player) {
        try {
            Object bungeePlayer = player;
            var getChMethod = bungeePlayer.getClass().getDeclaredMethod("getCh");
            getChMethod.setAccessible(true);
            var channelWrapper = getChMethod.invoke(bungeePlayer);
            var getHandleMethod = channelWrapper.getClass().getDeclaredMethod("getHandle");
            getHandleMethod.setAccessible(true);
            return (Channel) getHandleMethod.invoke(channelWrapper);
        } catch (Exception e) {
            return null;
        }
    }

    private static int lookup(int[][] table, int protocol) {
        for (int[] entry : table) {
            if (protocol >= entry[0] && protocol <= entry[1]) {
                return entry[2];
            }
        }
        return -1;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    @Override
    public boolean canProject(P player) {
        var id = platformHandle.getUUIDForPlayer(player);
        var protocolize = Protocolize.playerProvider().player(id);
        return protocolize.protocolVersion() >= ProtocolVersions.MINECRAFT_1_13;
    }

}
