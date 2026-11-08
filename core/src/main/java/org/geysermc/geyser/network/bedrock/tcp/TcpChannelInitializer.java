/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.network.bedrock.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.ConnectionRequests;
import org.geysermc.geyser.network.GeyserServerInitializer;
import org.geysermc.geyser.network.bedrock.GeyserBedrockPeer;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * The Bedrock pipeline of a {@link TcpServer} game connection: as over RakNet protocol 11, without RakNet.
 */
@ChannelHandler.Sharable
public class TcpChannelInitializer extends GeyserServerInitializer {
    private static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    public TcpChannelInitializer(GeyserImpl geyser) {
        super(geyser, "Geyser TCP player thread");
    }

    @Override
    protected void preInitChannel(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress clientAddress
                && !ConnectionRequests.accept(geyser, clientAddress, null)) {
            channel.close();
            return;
        }
        // Nothing is compressed until the peer sets compression after RequestNetworkSettings, as on RakNet 11
        channel.pipeline()
                .addLast(FrameCodec.NAME, FrameCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(NOOP_STRATEGY, false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        // The parent picks this by RakNet protocol version, which a TCP channel does not have
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new TcpPeer(channel, this::createSession);
    }

    /**
     * Strips and adds the 0xfe batch header, as the RakNet frame codec does for RakNet messages.
     */
    @ChannelHandler.Sharable
    static final class FrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {
        // Named as the RakNet frame codec: the peer adds encryption right after it by this name.
        static final String NAME = "frame-id-codec";
        static final FrameCodec INSTANCE = new FrameCodec();
        private static final int FRAME_ID = 0xfe;

        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
            if (msg.getCompressed() == null) {
                throw new IllegalStateException("Bedrock batch was not compressed");
            }
            CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(2);
            try {
                buf.addComponent(true, ctx.alloc().ioBuffer(1).writeByte(FRAME_ID));
                buf.addComponent(true, msg.getCompressed().retainedSlice());
                out.add(buf.retain());
            } finally {
                buf.release();
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) {
                return;
            }
            int id = in.readUnsignedByte();
            if (id != FRAME_ID) {
                throw new IllegalStateException("Invalid frame ID: " + id);
            }
            out.add(BedrockBatchWrapper.newInstance(in.readRetainedSlice(in.readableBytes()), null));
        }
    }

    /**
     * A TCP game connection behaves like RakNet protocol 11: no compression until network settings, encrypted as
     * usual once the login sets a key.
     */
    static final class TcpPeer extends GeyserBedrockPeer {
        private static final int EQUIVALENT_RAK_VERSION = 11;

        TcpPeer(Channel channel, BedrockSessionFactory sessionFactory) {
            super(channel, sessionFactory);
        }

        @Override
        public int getRakVersion() {
            return EQUIVALENT_RAK_VERSION;
        }
    }
}
