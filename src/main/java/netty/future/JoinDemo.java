package netty.future;

/**
 * @Classname JoinDemo
 * @Description TODO
 * @Date 2021/5/18 14:35
 * @Created by wangchao
 *
 * 缺点：无法烧水，洗水壶获取是否成功结果
 */
public class JoinDemo {
    public static final int SLEEP_GAP = 500;

    public static void main(String[] args) {
        Thread hotWaterThread = new HotWaterThread();
        Thread washThread = new WashThread();
        hotWaterThread.start();
        washThread.start();
        try {
            //烧水
            hotWaterThread.join();
            //清洗茶杯
            washThread.join();
            Thread.currentThread().setName("主线程");
            System.out.println("泡茶喝");
        } catch (InterruptedException e) {
            System.out.println(getCurThreadName() + " 有人茶里下毒");
        }
        System.out.println(getCurThreadName() + " 运行结束");
    }

    public static String getCurThreadName() {
        return Thread.currentThread().getName();
    }

    private static class HotWaterThread extends Thread {
        public HotWaterThread() {
            super("** 烧水-Thread");
        }

        @Override
        public void run() {

            try {
                System.out.println("洗好水壶");
                System.out.println("灌上凉水");
                System.out.println("放在火上");

                //线程睡眠一段时间，代表烧水中
                Thread.sleep(SLEEP_GAP);
                System.out.println("水开了");

            } catch (InterruptedException e) {
                System.out.println(" 发生异常被中断.");
            }
            System.out.println(" 运行结束.");
        }
    }

    private static class WashThread extends Thread {
        public WashThread() {
            super("$$ 清洗-Thread");
        }

        @Override
        public void run() {
            try {
                System.out.println("洗茶壶");
                System.out.println("洗茶杯");
                System.out.println("拿茶叶");
                //线程睡眠一段时间，代表清洗中
                Thread.sleep(SLEEP_GAP);
                System.out.println("洗完了");

            } catch (InterruptedException e) {
                System.out.println(" 发生异常被中断.");
            }
            System.out.println(" 运行结束.");
        }

    }
}
