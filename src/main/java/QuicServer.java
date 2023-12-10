import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class QuicServer {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(QuicServer.class);

    private QuicServer() {
    }

    public static void main(String[] args) throws Exception {

        NioEventLoopGroup group = new NioEventLoopGroup(8);

        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate();
        QuicSslContext context = QuicSslContextBuilder.forServer(
                selfSignedCertificate.privateKey(), null, selfSignedCertificate.certificate())
                .applicationProtocols("http/0.9").build();

        ChannelHandler codec = new QuicServerCodecBuilder().sslContext(context)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                // Configure some limits for the maximal number of streams (and the data) that
                // we want to handle.
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100)
                .initialMaxStreamsUnidirectional(100)
                .activeMigration(true)
                // Set up a token handler. In a production system you would want to implement
                // and provide your custom
                // one.
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                // ChannelHandler that is added into QuicChannel pipeline.
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) throws InterruptedException {
                        QuicChannel channel = (QuicChannel) ctx.channel();
                        // Create streams etc..
                    }

                    public void channelInactive(ChannelHandlerContext ctx) {
                        ((QuicChannel) ctx.channel()).collectStats().addListener(f -> {
                            if (f.isSuccess()) {
                                LOGGER.info("Connection closed: {}", f.getNow());
                            }
                        });
                    }

                    @Override
                    public boolean isSharable() {
                        return true;
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        // Add a LineBasedFrameDecoder here as we just want to do some simple HTTP 0.9
                        // handling.
                        ch.pipeline()
                                .addLast(new LineBasedFrameDecoder(1024))
                                .addLast(new QuicServerHandler());
                    }
                })
                .build();

        try {
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(new InetSocketAddress(Config.port)).sync().channel();
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}

class QuicServerHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        String message = ((ByteBuf) msg).toString(CharsetUtil.US_ASCII);
        String bearerToken = extractBearerToken(message);
        if (validateBearerToken(bearerToken)) {
            ByteBuf buffer = ctx.alloc().directBuffer();
            buffer.writeCharSequence("Message From Server: Hello, the Client! Successfully receive the message!", CharsetUtil.US_ASCII);
            ctx.writeAndFlush(buffer);

            System.out.println("Accept message from client! The message is:\"" + message.substring(22 + bearerToken.length() + 1) + "\"");
        }
        else {
            ByteBuf buffer = ctx.alloc().directBuffer();
            buffer.writeCharSequence("Message From Server: BearerToken check failed!", CharsetUtil.US_ASCII);
            ctx.writeAndFlush(buffer);

            ctx.close();
        }
    }

    private String extractBearerToken(String message) {
        String bearerToken = message.substring(22);
        int i;
        for (i = 0; i < bearerToken.length(); i++)
            if (bearerToken.charAt(i) == ' ')
                break;
        return bearerToken.substring(0, i);
    }

    private boolean validateBearerToken(String bearerToken) {
        // TODO: 实现Bearer token的验证逻辑，例如与授权服务器进行通信或验证签名
        return Objects.equals(bearerToken, "a"); // 假设验证始终成功
    }
}