package netty.reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主从Reactor线程模型
 *
 * @author WangChao
 * @create 2021/5/16 23:46
 */
public class MasterSlaveReactor {
    Selector[] selectors = new Selector[2];
    SubReactor[] subReactors = new SubReactor[2];

    AtomicInteger next = new AtomicInteger(0);
    ServerSocketChannel serverSocketChannel;


    public MasterSlaveReactor() throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        InetSocketAddress inetSocketAddress = new InetSocketAddress("127.0.0.1", 8081);
        serverSocketChannel.socket().bind(inetSocketAddress);
        serverSocketChannel.configureBlocking(false);
        //第一个选择器监控accept
        SelectionKey sk = serverSocketChannel.register(selectors[0], SelectionKey.OP_ACCEPT);
        sk.attach(new AcceptorHandler());

        //一个反应堆对应一个子选择器
        selectors[0] = Selector.open();
        selectors[1] = Selector.open();
        subReactors[0] = new SubReactor(selectors[0]);
        subReactors[1] = new SubReactor(selectors[1]);

    }

    private void startService() {
        new Thread(subReactors[0]).start();
        new Thread(subReactors[1]).start();
    }

    private class AcceptorHandler implements Runnable {
        @Override
        public void run() {
            try {
                SocketChannel socketChannel = serverSocketChannel.accept();
                if (Objects.isNull(socketChannel)) {
                    new MasterSlaveReactorHandler(selectors[next.get()], socketChannel);
                }
            } catch (IOException e) {
            }
            if (next.incrementAndGet() == selectors.length) {
                next.set(0);
            }
        }
    }

    private class SubReactor implements Runnable {
        private final Selector selector;

        public SubReactor(Selector selector) {
            this.selector = selector;
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
                        dispatch(sk);
                    }
                    selectionKeys.clear();
                }
            } catch (IOException e) {
            }
        }

        private void dispatch(SelectionKey sk) {
            Runnable handler = (Runnable) sk.attachment();
            if (!Objects.isNull(handler)) {
                handler.run();
            }

        }
    }
}
