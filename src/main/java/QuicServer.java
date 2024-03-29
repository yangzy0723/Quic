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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class QuicServer {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(QuicServer.class);
    private static ExecutorService executorService;
    private QuicServer() {
    }

    public static void main(String[] args) throws Exception {

        if (args.length > 0)
            Config.listenPort = Integer.parseInt(args[0]);
        NioEventLoopGroup group = new NioEventLoopGroup();
        executorService = Executors.newFixedThreadPool(32);

        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate();
        QuicSslContext context = QuicSslContextBuilder.forServer(
                selfSignedCertificate.privateKey(), null, selfSignedCertificate.certificate())
                .applicationProtocols("http/0.9").build();

        ChannelHandler codec = new QuicServerCodecBuilder().sslContext(context)
                .maxIdleTimeout(50000, TimeUnit.MILLISECONDS)
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
                    .bind(new InetSocketAddress(Config.listenPort)).sync().channel();
            channel.closeFuture().sync();
            System.out.println();
        } finally {
            group.shutdownGracefully();
        }
    }

    static class QuicServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    String message = ((ByteBuf) msg).toString(CharsetUtil.US_ASCII);
                    String bearerToken = extractBearerToken(message);
                    ByteBuf buffer = ctx.alloc().directBuffer();
                    if (validateBearerToken(bearerToken)) {
                        buffer.writeCharSequence("Successfully receive the message!", CharsetUtil.US_ASCII);
                        ctx.writeAndFlush(buffer);

                        System.out.println("[Accept message from client.] The message is:\"" + message.substring(22 + bearerToken.length() + 1) + "\"");
                    }
                    else {
                        buffer.writeCharSequence("BearerToken check failed!", CharsetUtil.US_ASCII);
                        ctx.writeAndFlush(buffer);

                        ctx.close();
                    }
                }
            });
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
            return Objects.equals(bearerToken, "a"); // 80%的信息能通过验证
        }
    }
}