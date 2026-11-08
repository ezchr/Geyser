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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.BedrockPingHandler;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Bedrock listener for a proxy on the same machine (such as Beacon), compatible with gophertunnel's "tcp"
 * network: packet batches over a TCP stream instead of RakNet.
 * <p>
 * The client first sends one byte: 0 for a game connection, 1 for a ping. A game connection may add 2 (no
 * encryption) and 4 (no compression) to it: a link that never leaves the machine gains nothing from either, so this
 * listener grants both. A game connection then carries frames both ways, each a big-endian 4-byte length and that many bytes: what a RakNet message would hold (a batch, with
 * its 0xfe header). A ping is answered with one frame holding the pong, then the connection is closed.
 * <p>
 * It has no access control of its own: bind it to an address only the proxy can reach, such as 127.0.0.1.
 */
public final class TcpServer {
    public static final String ADDRESS_PROPERTY = "geyserTcpAddress";

    private static final int GAME = 0;
    private static final int PING = 1;
    private static final int NO_ENCRYPTION = 1 << 1;
    private static final int NO_COMPRESSION = 1 << 2;
    /**
     * The flags a game connection asked for in its first byte.
     */
    private static final AttributeKey<Integer> FLAGS = AttributeKey.valueOf("geyser-tcp-flags");
    private static final int MAX_FRAME = 64 << 20;

    private final GeyserImpl geyser;
    private final BedrockPingHandler pingResponder;
    private final long serverId = ThreadLocalRandom.current().nextLong();
    private final TcpChannelInitializer gameInitializer;
    private EventLoopGroup group;
    private Channel channel;

    public TcpServer(GeyserImpl geyser) {
        this.geyser = geyser;
        this.pingResponder = new BedrockPingHandler(geyser);
        this.gameInitializer = new TcpChannelInitializer(geyser);
    }

    /**
     * @return whether the connection on this channel asked not to be encrypted
     */
    public static boolean noEncryption(Channel channel) {
        Integer flags = channel.attr(FLAGS).get();
        return flags != null && (flags & NO_ENCRYPTION) != 0;
    }

    /**
     * @return whether the connection on this channel asked not to be compressed
     */
    public static boolean noCompression(Channel channel) {
        Integer flags = channel.attr(FLAGS).get();
        return flags != null && (flags & NO_COMPRESSION) != 0;
    }

    public void start(InetSocketAddress address) {
        this.group = new NioEventLoopGroup(0, new DefaultThreadFactory("GeyserTcpServer", true));
        ChannelFuture future = new ServerBootstrap()
                .group(this.group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast("tcp-handshake", new Handshake());
                    }
                })
                .bind(address)
                .syncUninterruptibly();
        this.channel = future.channel();
        this.geyser.getLogger().info("Started Geyser TCP listener for a local proxy on " + address);
        if (!address.getAddress().isLoopbackAddress()) {
            this.geyser.getLogger().warning("The Geyser TCP listener has no access control: let only the proxy reach " + address);
        }
    }

    public void shutdown() {
        if (this.channel != null) {
            this.channel.close().syncUninterruptibly();
        }
        if (this.group != null) {
            this.group.shutdownGracefully();
        }
        this.gameInitializer.getEventLoopGroup().shutdownGracefully();
    }

    /**
     * Reads the first byte: a game connection gets the Bedrock pipeline, a ping its pong.
     */
    private final class Handshake extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) {
                return;
            }
            int kind = in.readUnsignedByte();
            if ((kind & ~(NO_ENCRYPTION | NO_COMPRESSION)) == GAME) {
                ctx.channel().attr(FLAGS).set(kind);
                ctx.pipeline()
                        .addAfter(ctx.name(), "tcp-frame-decoder", new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4))
                        .addAfter("tcp-frame-decoder", "tcp-frame-prepender", new LengthFieldPrepender(4))
                        .addAfter("tcp-frame-prepender", "tcp-bedrock-init", gameInitializer);
                // The channel became active before these handlers existed; the Bedrock peer creates its session on
                // that event, so let them see it.
                ctx.fireChannelActive();
                // The bytes already read after the first one pass on to the frame decoder.
                ctx.pipeline().remove(this);
            } else if (kind == PING) {
                InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
                // Clients take the port to connect to from the pong: name this listener's, not the RakNet one.
                int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
                ByteBuf pong = pingResponder.onQuery(serverId, remote, null).ipv4Port(port).ipv6Port(port).toByteBuf();
                ByteBuf frame = ctx.alloc().buffer(4 + pong.readableBytes());
                frame.writeInt(pong.readableBytes()).writeBytes(pong);
                pong.release();
                ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);
                in.skipBytes(in.readableBytes());
            } else {
                in.skipBytes(in.readableBytes());
                ctx.close();
            }
        }
    }
}
