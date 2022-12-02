package cn.itcast.nio.c4;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.itcast.nio.c2.ByteBufferUtil.debugAll;
@Slf4j
public class MultiThreadServerModify {
    public static void main(String[] args) throws IOException{
        Thread.currentThread().setName("boss");
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        Selector boss = Selector.open();
        SelectionKey bossKey = ssc.register(boss, 0, null);
        bossKey.interestOps(SelectionKey.OP_ACCEPT);
        ssc.bind(new InetSocketAddress(8080));
        Worker worker = new Worker("woker-0"); // 当前只创建一个worker
//        worker.register(); // 初始化 selector ， 启动 worker-0
        while(true) {
            boss.select();
            Iterator<SelectionKey> iter = boss.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                if (key.isAcceptable()) {
                    SocketChannel sc = ssc.accept();
                    sc.configureBlocking(false);
//                    Worker worker = new Worker("woker-0"); // 放在这里不合适：每次连接建立都要创建一个 worker
                    log.debug("connected...{}", sc.getRemoteAddress());
                    log.debug("before register...{}", sc.getRemoteAddress());
                    /*
                    register 在这个位置会在 selector.select(); 之后执行【跨线程】
                    由于 selector 在 selector.select() 这个位置处阻塞住了【锁住了】，因此 register 无法获得 selector 的使用权往下执行，因此也在这个位置处阻塞住
                    register 无法往下执行导致 selector 无法监听 read 事件，导致 selector.select() 无法往下执行
                    因此 register 需要在 selector.select() 之前执行，这样在事件发生时 selector.select() 才不会阻塞住
                     */
                    /*
                    放在这里有一定的概率会使 register 需要在 selector.select() 之前执行，第一个客户端到来时会正常收到消息【处理结束后还是会在 selector.select() 阻塞住】
                    第二个客户端到来时 register 还是会在 selector.select() 之后执行 -> 导致阻塞
                     */
                    /*
                    解决办法：
                    两个方法的先后顺序不好控制是因为这两个方法一个在boss线程执行，另外一个方法在worker里执行
                    可以把这两个方法都放在worker线程里执行
                    每次初始化时都将当前的channel传递给worker线程里，同时唤醒 worker 去处理这个注册的逻辑
                    线程间通信
                     */
                    worker.register(sc);
//                    sc.register(worker.selector,SelectionKey.OP_READ); // socketChannel 注册到 worker的select上
                    log.debug("after register...{}", sc.getRemoteAddress());
                }
            }
        }
    }
    static class Worker implements Runnable{
        private Thread thread;
        private Selector selector;
        private String name;
        private volatile boolean start=false; // 一个worker实例只能执行一次register
        private ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        public Worker(String name) {
            this.name = name;
        }

        // 初始化线程，和 selector
        public void register(SocketChannel sc) throws IOException {
            if (!start) {
                selector = Selector.open();
                thread = new Thread(this, name);
                thread.start();
                start=true;
            }
            queue.add(()->{
                try {
                    sc.register(selector,SelectionKey.OP_READ);
                } catch (ClosedChannelException e) {
                    e.printStackTrace();
                }
            });
            selector.wakeup();
            /*
            更简单的解决办法：但是会有一些问题：register 和下一次 select 锁竞争？
            selector.wakeup();
            sc.register(selector,SelectionKey.OP_READ);
             */

        }

        @Override
        public void run() {
            while(true) {
                try {
                    selector.select(); // worker-0  阻塞
                    /*
                    这段代码放在 selector.select() 前后都可以，但是在 boss 中添加了任务后都得主动唤醒 selector
                     */
                    Runnable poll = queue.poll();
                    if (poll!=null)
                        poll.run();

                    Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isReadable()) {
                            ByteBuffer buffer = ByteBuffer.allocate(16);
                            SocketChannel channel = (SocketChannel) key.channel();
                            log.debug("read...{}", channel.getRemoteAddress());
                            channel.read(buffer);
                            buffer.flip();
                            debugAll(buffer);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
