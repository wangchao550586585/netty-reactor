package netty.reactor.master_slave;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 主从Reactor线程模型，handler
 *
 * @author WangChao
 * @create 2021/5/17 0:49
 */
public class MasterSlaveReactorHandler implements Runnable {
    private final SocketChannel socketChannel;
    private final SelectionKey sk;
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    static final int READING = 0, SENDING = 1, PROCESSING = 3;
    int state = READING;

    static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
            10,
            50,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    public MasterSlaveReactorHandler(Selector selector, SocketChannel socketChannel) throws IOException {
        this.socketChannel = socketChannel;
        this.socketChannel.configureBlocking(false);
        this.sk = this.socketChannel.register(selector, 0);
        this.sk.attach(this);
        this.sk.interestOps(SelectionKey.OP_READ);
        selector.wakeup();
    }

    /**
     * 引入多线程后，会造成一个问题。就是重复处理多次。
     * 这是因为当监听写事件时，因为是异步发送。所以造成会重复监听多次写事件。
     */
    @Override
    public void run() {
        threadPoolExecutor.execute(new AsyncTask());
    }

    /**
     *异步线程处理，这里可能存在临界状态。
     */
    private synchronized void asyncRun() {
        try {
            if (state == READING) {
                System.out.println("Reading : ");
                int length = 0;
                while ((length = socketChannel.read(buffer)) > 0) {
                    System.out.println(new String(buffer.array(), 0, length));
                }
                buffer.flip();
                sk.interestOps(SelectionKey.OP_WRITE);
                state = SENDING;
            } else if (state == SENDING) {
                System.out.println("Sending : ");
                socketChannel.write(buffer);
                buffer.clear();
                sk.interestOps(SelectionKey.OP_READ);
                state = READING;
            }
            //取消监听的事件,这里注释是为了重复使用
//            sk.cancel();
        } catch (IOException e) {
            e.printStackTrace();
            sk.cancel();
            try {
                socketChannel.finishConnect();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    class AsyncTask implements Runnable {
        @Override
        public void run() {
            MasterSlaveReactorHandler.this.asyncRun();
        }
    }


}
