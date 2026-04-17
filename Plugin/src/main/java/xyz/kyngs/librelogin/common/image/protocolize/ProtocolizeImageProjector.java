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
import dev.simplix.protocolize.data.item.component.MapIdComponentImpl;
import dev.simplix.protocolize.data.packets.HeldItemChange;
import dev.simplix.protocolize.data.packets.SetSlot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import xyz.kyngs.librelogin.api.image.ImageProjector;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.image.protocolize.packet.MapDataPacket;

import java.awt.image.BufferedImage;

public class ProtocolizeImageProjector<P, S> extends AuthenticImageProjector<P, S> implements ImageProjector<P> {

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

    /**
     * <b>This implementation only really renders pure black and everything else as transparent. Shouldn't be used for anything else than a QR code.</b>
     *
     * @param image  The image to render.
     * @param player The player to render the image to.
     */
    @Override
    public void project(BufferedImage image, P player) {
        var id = platformHandle.getUUIDForPlayer(player);

        var protocolize = Protocolize.playerProvider().player(id);
        var protocol = protocolize.protocolVersion();
        var item = new ItemStack(
                ItemType.FILLED_MAP,
                1,
                (short) 0
        );

        if (protocol >= 766) {
            // 1.20.5+ uses structured components
            item.addComponent(new MapIdComponentImpl(0));
        } else if (protocol >= ProtocolVersions.MINECRAFT_1_17) {
            item.nbtData()
                    .putInt("map", 0);
        }

        protocolize.sendPacket(
                new SetSlot()
                        .slot((short) 36)
                        .itemStack(item)
        );

        protocolize.sendPacketToServer(
                new HeldItemChange()
                        .newSlot((short) 0)
        );

        protocolize.sendPacket(
                new HeldItemChange()
                        .newSlot((short) 0)
        );

        if (image.getWidth() != 128 && image.getHeight() != 128) {
            var resized = new BufferedImage(128, 128, image.getType());

            var graphics = resized.createGraphics();
            graphics.drawImage(image, 0, 0, 128, 128, 0, 0, image.getWidth(), image.getHeight(), null);
            graphics.dispose();

            image = resized;
        }

        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        byte[] data = new byte[pixels.length];

        for (int i = 0; i < pixels.length; i++) {
            data[i] = (byte) (pixels[i] == -16777216 ? 116 : 56);
        }

        // Send MapDataPacket directly via raw bytes to bypass Protocolize/BungeeCord packet registry issues
        sendMapDataRaw(player, protocol, 0, (byte) 0, new MapData(128, 128, 0, 0, data));
    }

    private void sendMapDataRaw(P player, int protocol, int mapId, byte scale, MapData mapData) {
        try {
            // P is ProxiedPlayer on BungeeCord
            Object bungeePlayer = player;
            if (bungeePlayer == null) return;

            // Get channel: player.getCh().getHandle()
            var getChMethod = bungeePlayer.getClass().getDeclaredMethod("getCh");
            getChMethod.setAccessible(true);
            var channelWrapper = getChMethod.invoke(bungeePlayer);
            var getHandleMethod = channelWrapper.getClass().getDeclaredMethod("getHandle");
            getHandleMethod.setAccessible(true);
            var channel = (io.netty.channel.Channel) getHandleMethod.invoke(channelWrapper);

            // Determine packet ID for this protocol version
            int packetId = -1;
            for (var mapping : MapDataPacket.MAPPINGS) {
                if (mapping.inRange(protocol)) {
                    packetId = mapping.id();
                    break;
                }
            }
            if (packetId == -1) return;

            // Build raw packet: varint(length) + varint(packetId) + data
            // BungeeCord's pipeline handles framing, so we just need packetId + data
            ByteBuf packetBuf = Unpooled.buffer();

            // Write packet ID
            writeVarInt(packetBuf, packetId);

            // Write map data packet content
            writeVarInt(packetBuf, mapId); // map ID
            packetBuf.writeByte(scale);    // scale

            if (protocol < ProtocolVersions.MINECRAFT_1_17) {
                packetBuf.writeBoolean(false); // tracking position
                if (protocol >= ProtocolVersions.MINECRAFT_1_14) {
                    packetBuf.writeBoolean(false); // locked
                }
            } else {
                packetBuf.writeBoolean(false); // locked
                packetBuf.writeBoolean(false); // has icons (tracking position)
            }

            // Map update data
            packetBuf.writeByte(mapData.columns());
            if (mapData.columns() > 0) {
                packetBuf.writeByte(mapData.rows());
                packetBuf.writeByte(mapData.posX());
                packetBuf.writeByte(mapData.posZ());
                // Write byte array (varint length + data)
                writeVarInt(packetBuf, mapData.data().length);
                packetBuf.writeBytes(mapData.data());
            }

            // Send through the pipeline
            channel.writeAndFlush(packetBuf);

        } catch (Exception e) {
            e.printStackTrace();
        }
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
