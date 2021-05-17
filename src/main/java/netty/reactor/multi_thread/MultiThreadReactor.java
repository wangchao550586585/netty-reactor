package netty.reactor.multi_thread;

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
 * 多线程Reactor模型
 *
 * @author WangChao
 * @create 2021/5/16 23:21
 */
public class MultiThreadReactor {
}

class HandlerV implements Runnable {
    final SocketChannel socket;
    final SelectionKey sk;
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    static final int READING = 0, SENDING = 1,PROCESSING = 3;
    int state = READING;
    static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
            10,
            50,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    HandlerV(Selector sel, SocketChannel c) throws IOException {
        socket = c;
        c.configureBlocking(false);
        sk = socket.register(sel, 0);
        sk.attach(this);
        sk.interestOps(SelectionKey.OP_READ);
        sel.wakeup();
    }

    @Override
    public void run() {
        try {
            if (state == READING) {
                read();
            } else if (state == SENDING) {
                send();
            }
            //处理结束后，不能关闭selectKey,需要重复使用
            //sk.cancel();
        } catch (IOException ex) { /* ... */ }

    }

    synchronized void send() throws IOException {
        socket.write(buffer);
        //写完后，准备从通道读取，将buffer切换写入模式
        buffer.clear();
        if (outputIsComplete()) {
            //write完就结束了, 关闭select key
            //sk.cancel();
        }
    }



    synchronized void read() throws IOException { // ...
        int length=0;
        while ((length=socket.read(buffer))>1){
            System.out.println(new String(buffer.array(),0,length));
        }
        ////读完后，准备开始写入通道，将buffer切换为读取模式
        buffer.flip();
        if (inputIsComplete()) {
            state = PROCESSING;
            //使用线程pool异步执行
            threadPoolExecutor.execute(new Processer());
        }
    }

    class Processer implements Runnable {
         @Override
         public void run() { processAndHandOff(); }
    }


    private boolean inputIsComplete() {
        return false;
    }

    private boolean outputIsComplete() {
        return false;
    }

    synchronized  void processAndHandOff() {
        process();
        // or rebind attachment
        state = SENDING;
        //process完,开始等待write事件
        sk.interestOps(SelectionKey.OP_WRITE);
    }

    private void process() {

    }
}