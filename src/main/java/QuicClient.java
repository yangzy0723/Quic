import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class QuicClient implements Runnable {

    int thread;
    boolean running;

    QuicClient(int thread) {
        this.thread = thread;
        this.running = true;
    }

    @Override
    public void run() {

        NioEventLoopGroup group = new NioEventLoopGroup(1);

        QuicSslContext context = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("http/0.9").build();

        try {
            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(context)
                    .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    // As we don't want to support remote initiated streams just setup the limit for local initiated streams in this example.
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .build();

            Bootstrap bs = new Bootstrap();
            Channel channel = null;
            channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(0).sync().channel();

            QuicChannel quicChannel = null;
            quicChannel = QuicChannel.newBootstrap(channel)
                    .streamHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            // As we did not allow any remote initiated streams we will never see this method called.
                            // That said just let us keep it here to demonstrate that this handle would be called for each remote initiated stream.
                            ctx.close();
                        }
                    })
                    .remoteAddress(new InetSocketAddress(Config.IP, Config.port))
                    .connect()
                    .get();

            QuicStreamChannel streamChannel = quicChannel
                    .createStream(QuicStreamType.BIDIRECTIONAL, new QuicClientHandler(this.thread)).sync().getNow();

            ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
            executorService.scheduleAtFixedRate(() -> {
                if (!streamChannel.isActive()) {
                    executorService.shutdown();
                } else {
                    String message = "Hello, server! From ClientThread" + this.thread;
                    streamChannel.writeAndFlush(Unpooled.copiedBuffer(createRequestWithBearerToken(message)))
                            .addListener((ChannelFutureListener) future -> {
                                if (future.isSuccess())
                                    System.out.println("Message has been sent...");
                                else
                                    System.out.println("Message sending fail...");
                            });
                }
            }, 0, 1, TimeUnit.SECONDS);

            // Wait for the stream channel and quic channel to be closed (this will happen after we received the FIN).
            // After this is done we will close the underlying datagram channel.
            streamChannel.closeFuture().sync();
            quicChannel.closeFuture().sync();
            channel.close().sync();

        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            group.shutdownGracefully();
        }
    }

    private byte[] createRequestWithBearerToken(String message) {
        String bearerToken = "abc";
        String requestString = "Authorization: Bearer " + bearerToken + " " + message + "\r\n";
        return requestString.getBytes(StandardCharsets.UTF_8);
    }

    void stopThread() {
        this.running = false;
    }
}

class QuicClientHandler extends ChannelInboundHandlerAdapter {
    int thread;

    public QuicClientHandler(int thread){
        this.thread = thread;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf byteBuf = (ByteBuf) msg;
        System.out.println(byteBuf.toString(CharsetUtil.US_ASCII));
        byteBuf.release();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
            System.out.println("ClientThread" + this.thread + " Connection Closed...");
            // Close the connection once the remote peer did send the FIN for this stream.
            ((QuicChannel) ctx.channel().parent()).close(true, 0,
                    ctx.alloc().directBuffer(16)
                            .writeBytes(new byte[] { 'k', 't', 'h', 'x', 'b', 'y', 'e' }));
        }
    }
}