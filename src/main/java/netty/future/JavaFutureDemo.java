package netty.future;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * @Classname JavaFutureDemo
 * @Description TODO
 * @Date 2021/5/18 15:12
 * @Created by wangchao
 *
 * 缺点，获取异步结果过程中,阻塞.
 * 异步回调解决
 */
public class JavaFutureDemo {
    public static final int SLEEP_GAP = 500;

    public static void main(String[] args) {
        Callable<Boolean> hotWaterJob = new HotWaterJob();
        FutureTask<Boolean> hotWaterFutureTask = new FutureTask<>(hotWaterJob);
        Thread hotWaterThread = new Thread(hotWaterFutureTask, "烧水线程");

        Callable<Boolean> washJob = new WashJob();
        FutureTask<Boolean> washFutureTask = new FutureTask<>(washJob);
        Thread washThread = new Thread(washFutureTask, "清洗线程");

        hotWaterThread.start();
        washThread.start();
        Thread.currentThread().setName("主线程");

        try {
            Boolean hotWaterResult = hotWaterFutureTask.get();
            Boolean washResult = washFutureTask.get();

            drinkTea(hotWaterResult,washResult);
        } catch (InterruptedException e) {
            System.out.println(getCurThreadName() + " 有人茶里下毒");
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        System.out.println(getCurThreadName() + " 运行结束");

    }
    public static String getCurThreadName() {
        return Thread.currentThread().getName();
    }
    private static void drinkTea(Boolean hotWaterResult, Boolean washResult) {
        if (hotWaterResult&&washResult){
            System.out.println("来大活儿喝茶~~");
        }else if(!washResult){
            System.out.println("我儿豁，没茶叶了~~~");
        }else{
            System.out.println("我儿豁，水没烧开啊~~");
        }
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
                return  false;
            }
            System.out.println(" 运行结束.");
            return  true;
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
                return  false;
            }
            System.out.println(" 运行结束.");
            return  true;
        }
    }
}
