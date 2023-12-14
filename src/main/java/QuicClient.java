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

import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class QuicClient implements Runnable {

    int thread;
    long startTime;
    long endTime;
    ArrayList<Long> respondTime;
    boolean firstSendMessage;

    QuicClient(int thread) {
        this.thread = thread;
        this.startTime = 0;
        this.endTime = 0;
        this.respondTime = new ArrayList<>();
        this.firstSendMessage = true;
    }

    @Override
    public void run() {
        NioEventLoopGroup group = new NioEventLoopGroup();

        QuicSslContext context = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("http/0.9").build();

        try {
            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(context)
                    .maxIdleTimeout(50000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    // As we don't want to support remote initiated streams just set up the limit for local initiated streams in this example.
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
                    .remoteAddress(new InetSocketAddress(Config.serverIP, Config.toPort))
                    .connect()
                    .get();

            QuicStreamChannel streamChannel = quicChannel
                    .createStream(QuicStreamType.BIDIRECTIONAL, new QuicClientHandler()).sync().getNow();
            ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
            executorService.scheduleAtFixedRate(() -> {
                if (!streamChannel.isActive()) {
                    try {
                        Long timeSum = 0L;
                        int count = 0;
                        for(Long time : respondTime){
                            timeSum += time;
                            count++;
                        }
                        FileWriter fileWriter = new FileWriter("time-recorder.txt", true);
                        fileWriter.write("平均响应时间：" + (double)timeSum / count + "毫秒\r\n");
                        fileWriter.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    finally {
                        executorService.shutdown();
                    }
                } else {
                    String message = "Hello, server! From ClientThread" + this.thread;
                    streamChannel.writeAndFlush(Unpooled.copiedBuffer(createRequestWithBearerToken(message)))
                            .addListener((ChannelFutureListener) future -> {
                                if (future.isSuccess()) {
                                    startTime = System.currentTimeMillis();
                                    System.out.println("Message has been sent...");
                                }
                                else
                                    System.out.println("Message sending fail...");
                            });
                }
            }, 0, Config.messageInterval, TimeUnit.MILLISECONDS);
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
        String bearerToken;
        if(!firstSendMessage) {
            int randomNum = (int) (Math.random() * 10);
            if (randomNum <= 8)
                bearerToken = "a";
            else
                bearerToken = "b";
        }
        else {
            bearerToken = "a";
            firstSendMessage = false;
        }
        String requestString = "Authorization: Bearer " + bearerToken + " " + message + "\r\n";
        return requestString.getBytes(StandardCharsets.UTF_8);
    }

    class QuicClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {

            endTime = System.currentTimeMillis();
            respondTime.add(endTime - startTime);

            ByteBuf byteBuf = (ByteBuf) msg;
            System.out.println("[Accept message from server.] The message is:\"" + byteBuf.toString(CharsetUtil.US_ASCII) + "\"");
            byteBuf.release();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
                System.out.println("ClientThread" + thread + " Connection Closed...");
                // Close the connection once the remote peer did send the FIN for this stream.
                ((QuicChannel) ctx.channel().parent()).close(true, 0,
                        ctx.alloc().directBuffer(16)
                                .writeBytes(new byte[] { 'k', 't', 'h', 'x', 'b', 'y', 'e' }));
            }
        }
    }
}