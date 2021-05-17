package netty.reactor.reactor;

import netty.reactor.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;

/**
 * @Classname Client
 * @Description TODO
 * @Date 2021/5/17 15:16
 * @Created by wangchao
 */
public class Client {
    public static void main(String[] args) throws IOException {
        new Client().start();
    }

    private void start() throws IOException {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(Config.HOST, Config.PORT);
        SocketChannel socketChannel = SocketChannel.open(inetSocketAddress);
        socketChannel.configureBlocking(false);
        //不断自旋，等待连接完成
        while (!socketChannel.finishConnect()) {
        }
        Processer processer = new Processer(socketChannel);
        new Thread(processer).start();
    }

    private class Processer implements Runnable {
        private final SocketChannel socketChannel;
        private final Selector selector;

        public Processer(SocketChannel socketChannel) throws IOException {
            this.selector = Selector.open();
            this.socketChannel = socketChannel;
            socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

        }

        @Override
        public void run() {
            try {
                while (!Thread.interrupted()) {
                    selector.select();
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = selectionKeys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey sk = iterator.next();
                        if (sk.isReadable()) {
                            SocketChannel channel = (SocketChannel) sk.channel();
                            ByteBuffer buffer = ByteBuffer.allocate(1024);
                            int length = 0;
                            while ((length = channel.read(buffer)) > 0) {
                                buffer.flip();
                                System.out.println(new String(buffer.array(), 0, length));
                                buffer.clear();
                            }

                        }

                        if (sk.isWritable()) {
                            ByteBuffer buffer = ByteBuffer.allocate(1024);
                            Scanner scanner = new Scanner(System.in);
                            if (scanner.hasNext()) {
                                SocketChannel channel = (SocketChannel) sk.channel();
                                String next = scanner.next();
                                LocalDateTime now = LocalDateTime.now();
                                DateTimeFormatter dtf2 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                                String strNow = dtf2.format(now);
                                buffer.put((strNow + " >>" + next).getBytes());
                                buffer.flip();
                                channel.write(buffer);
                                buffer.clear();
                            }
                        }
                        //处理结束了, 这里不能关闭select key，需要重复使用
                        //selectionKey.cancel();
                    }
                    selectionKeys.clear();
                }
            } catch (IOException e) {
            }
        }
    }
}
