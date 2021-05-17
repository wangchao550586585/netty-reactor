package netty.reactor.reactor;

import netty.reactor.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * Reactor线程模型
 * 单线程
 *
 * @author WangChao
 * @create 2021/5/16 22:51
 */
public class Reactor implements Runnable {
    public static void main(String[] args) throws IOException {
        new Reactor().run();
    }

    final Selector selector;
    final ServerSocketChannel serverSocket;

    Reactor() throws IOException {
        //Reactor初始化
        selector = Selector.open();
        serverSocket = ServerSocketChannel.open();
        serverSocket.socket().bind(new InetSocketAddress(Config.HOST, Config.PORT));
        //非阻塞
        serverSocket.configureBlocking(false);
        //与Selector一起使用时，Channel必须处于非阻塞模式下。
        SelectionKey sk = serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        //分步处理,第一步,接收accept事件
        //attach callback object, Acceptor
        sk.attach(new Acceptor());
    }

    @Override
    public void run() {
        try {
            while (!Thread.interrupted()) {
                selector.select();
                Set selected = selector.selectedKeys();
                Iterator it = selected.iterator();
                while (it.hasNext()) {
                    //Reactor负责dispatch收到的事件
                    dispatch((SelectionKey) (it.next()));
                }
                selected.clear();
            }
        } catch (IOException ex) { /* ... */ }
    }

    void dispatch(SelectionKey k) {
        //调用之前注册的callback对象
        Runnable r = (Runnable) (k.attachment());
        if (r != null) {
            r.run();
        }
    }

    class Acceptor implements Runnable { // inner
        @Override
        public void run() {
            try {
                SocketChannel c = serverSocket.accept();
                if (c != null) {
                    new Handler(selector, c);
                }
            } catch (IOException ex) { /* ... */ }
        }
    }
}

final class Handler implements Runnable {
    final SocketChannel socket;
    final SelectionKey sk;
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    static final int READING = 0, SENDING = 1;
    int state = READING;

    Handler(Selector sel, SocketChannel c) throws IOException {
        socket = c;
        //设置非阻塞，读取立即返回
        c.configureBlocking(false);
        // Optionally try first read now
        sk = socket.register(sel, 0);
        //将Handler作为callback对象
        sk.attach(this);
        //第二步,接收Read事件
        sk.interestOps(SelectionKey.OP_READ);
        //某个线程调用select()方法后阻塞了，即使没有通道已经就绪，也有办法让其从select()方法返回。
        //只要让其它线程在第一个线程调用select()方法的那个对象上调用Selector.wakeup()方法即可。
        //阻塞在select()方法上的线程会立马返回。
        //如果有其它线程调用了wakeup()方法，但当前没有线程阻塞在select()方法上，
        //下个调用select()方法的线程会立即“醒来（wake up）”。
/*
        主要作用
            解除阻塞在Selector.select()/select(long)上的线程，立即返回。
            两次成功的select之间多次调用wakeup等价于一次调用。
            如果当前没有阻塞在select上，则本次wakeup调用将作用于下一次select——“记忆”作用。
        为什么要唤醒？
            注册了新的channel或者事件。
            channel关闭，取消注册。
            优先级更高的事件触发（如定时器事件），希望及时处理。
        原理
            Linux上利用pipe调用创建一个管道，Windows上则是一个loopback的tcp连接。这是因为win32的管道无法加入select的fd set，将管道或者TCP连接加入select fd set。
            wakeup往管道或者连接写入一个字节，阻塞的select因为有I/O事件就绪，立即返回。可见，wakeup的调用开销不可忽视。
        */
        sel.wakeup();
    }

    @Override
    public void run() {
        try {
            if (state == READING) {
                System.out.println("Reading : ");
                read();
            } else if (state == SENDING) {
                System.out.println("Sending : ");
                send();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            sk.cancel();
            try {
                socket.finishConnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    void read() throws IOException {
        //从通道读
        int length = 0;
        while ((length = socket.read(buffer)) > 0) {
            System.out.println((new String(buffer.array(), 0, length)));
        }
        //读完后，准备开始写入通道,byteBuffer切换成读模式
        buffer.flip();
        //读完后，注册write就绪事件
        sk.interestOps(SelectionKey.OP_WRITE);
        //读完后,进入发送的状态
        state = SENDING;
    }

    void send() throws IOException {
        //写入通道
        socket.write(buffer);
        //写完后,准备开始从通道读,byteBuffer切换成写模式
        buffer.clear();
        //写完后,注册read就绪事件
        sk.interestOps(SelectionKey.OP_READ);
        //写完后,进入接收的状态
        state = READING;
    }
}

//上面 的实现用Handler来同时处理Read和Write事件, 所以里面出现状态判断
//我们可以用State-Object pattern来更优雅的实现
/*
class Handler { // ...
    public void run() { // initial state is reader
        socket.read(input);
        if (inputIsComplete()) {
            process();
            //状态迁移, Read后变成write, 用Sender作为新的callback对象
            sk.attach(new Sender());
            sk.interestOps(SelectionKey.OP_WRITE);
            sk.selector().wakeup();
        }
    }
    class Sender implements Runnable {
        public void run(){ // ...
            socket.write(output);
            if (outputIsComplete()) sk.cancel();
        }
    }
}
*/
