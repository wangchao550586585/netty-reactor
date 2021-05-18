package netty.future;

import com.google.common.util.concurrent.*;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Classname JavaFutureDemo
 * @Description TODO
 * @Date 2021/5/18 15:12
 * @Created by wangchao
 */
public class GuavaFutureDemo {
    public static final int SLEEP_GAP = 500;


    public static void main(String[] args) {
        MainJob mainJob = new MainJob();
        Thread mainThread = new Thread(mainJob);
        mainThread.setName("主线程");
        mainThread.start();

        Callable<Boolean> hotWaterJob = new HotWaterJob();
        Callable<Boolean> washJob = new WashJob();

        //创建java 线程池
        ExecutorService jPool = Executors.newFixedThreadPool(10);

        //包装java线程池，构造guava 线程池
        ListeningExecutorService gPool = MoreExecutors.listeningDecorator(jPool);
        ListenableFuture<Boolean> hotFuture = gPool.submit(hotWaterJob);
        Futures.addCallback(hotFuture, new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(@Nullable Boolean aBoolean) {
                if (aBoolean) {
                    mainJob.waterOk = true;
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                System.out.println("烧水失败，没有茶喝了");
            }
        }, jPool);

        ListenableFuture<Boolean> washFuture = gPool.submit(washJob);
        Futures.addCallback(washFuture, new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(@Nullable Boolean aBoolean) {
                if (aBoolean) {
                    mainJob.cupOk = true;
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                System.out.println("杯子洗不了，没有茶喝了");
            }
        }, jPool);
    }

    public static String getCurThreadName() {
        return Thread.currentThread().getName();
    }


    private static class HotWaterJob implements Callable<Boolean> {
        @Override
        public Boolean call() throws Exception {
            try {
                System.out.println("洗好水壶");
                System.out.println("灌上凉水");
                System.out.println("放在火上");

                //线程睡眠一段时间，代表烧水中
                Thread.sleep(SLEEP_GAP);
                System.out.println("水开了");

            } catch (InterruptedException e) {
                System.out.println(" 发生异常被中断.");
                return false;
            }
            System.out.println(" 运行结束.");
            return true;
        }
    }

    private static class WashJob implements Callable<Boolean> {
        @Override
        public Boolean call() throws Exception {
            try {
                System.out.println("洗茶壶");
                System.out.println("洗茶杯");
                System.out.println("拿茶叶");
                //线程睡眠一段时间，代表清洗中
                Thread.sleep(SLEEP_GAP);
                System.out.println("洗完了");

            } catch (InterruptedException e) {
                System.out.println(" 发生异常被中断.");
                return false;
            }
            System.out.println(" 运行结束.");
            return true;
        }
    }

    private static class MainJob implements Runnable {
        int gap = SLEEP_GAP / 10;
        volatile boolean waterOk = false;
        volatile boolean cupOk = false;

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(gap);
                    System.out.println("摸鱼中...");
                } catch (InterruptedException e) {
                    System.out.println(getCurThreadName() + " 发生异常被中断.");
                }
                if (waterOk && cupOk) {
                    drinkTea(waterOk, cupOk);
                }
            }
        }

        private void drinkTea(Boolean hotWaterResult, Boolean washResult) {
            if (hotWaterResult && washResult) {
                System.out.println("来大活儿喝茶~~");
                this.waterOk = false;
                this.gap = SLEEP_GAP * 100;
            } else if (!washResult) {
                System.out.println("我儿豁，没茶叶了~~~");
            } else {
                System.out.println("我儿豁，水没烧开啊~~");
            }
        }

    }
}
