# Quic
A Test for Quic using Netty

该实验基于Java网络应用程序框架`Netty`，使用`Quic`协议进行数据传输。

可以通过修改`Config.java`文件中的端口号，IP地址等信息更改所连接的服务器，目前对客户端和服务端只写了简单的消息传递功能。

**格外注意**，`QuicSslContextBuilder.forClient()`方法创建了一个用于客户端的QUIC SSL上下文构建器。`.trustManager(InsecureTrustManagerFactory.INSTANCE)`方法设置了信任管理器，这里使用了不安全的信任管理器工厂，意味着它将信任任何证书，包括自签名证书和无效证书。
不过在服务器端我自己写了一个类似`Bearer Token`的身份鉴别程序。