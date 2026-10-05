/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.translator.protocol.java;

import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;

/**
 * Used to forward the keep alive packet to the client in order to get back a reliable ping.
 */
@Translator(packet = ClientboundKeepAlivePacket.class)
public class JavaKeepAliveTranslator extends PacketTranslator<ClientboundKeepAlivePacket> {

    @Override
    public void translate(GeyserSession session, ClientboundKeepAlivePacket packet) {
        if (!session.getGeyser().config().gameplay().forwardPlayerPing()) {
            return;
        }

        final long javaId = packet.getPingId();
        // ZID: the Bedrock client doesn't answer latency packets on the loading screen, and the replies come back
        // in arrival order, so a forwarded keepalive could reach the server after the connection changed state and
        // get the player kicked ("keepalive response without matching challenge"). Answer straight away until the
        // player has spawned, and drop a forwarded answer whose connection state changed since.
        if (!session.isSpawned() || session.getDownstream() == null) {
            session.sendDownstreamPacket(new ServerboundKeepAlivePacket(javaId));
            return;
        }
        final ProtocolState state = session.getDownstream().getSession().getPacketProtocol().getOutboundState();
        // ClientboundKeepAlivePacket's are async, hence we won't add additional delay ensuring it's sent in the event loop would add
        session.sendNetworkLatencyStackPacket(javaId, false, () -> {
            if (session.getDownstream() != null && session.getDownstream().getSession().getPacketProtocol().getOutboundState() == state) {
                session.sendDownstreamPacket(new ServerboundKeepAlivePacket(javaId));
            }
        });
    }
}
