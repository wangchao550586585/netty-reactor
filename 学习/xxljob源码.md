@[TOC]
系统架构

![在这里插入图片描述](https://img-blog.csdnimg.cn/20210427223244159.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
执行流程
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210427223302591.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

各大调度中心对比
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210422204828418.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

# 1）服务端启动流程
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210423200220377.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

首先找到配置类 <font color="red" size="3">XxlJobAdminConfig </font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/202104121350352.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
可以发现该类实现 <font color="red" size="3"> InitializingBean</font>接口，这里直接看<font color="blue" size="3"> afterPropertiesSet</font>方法即可。

```java
    @Override
    public void afterPropertiesSet() throws Exception {
        //利用静态声明的只会加载一次的特性，初始化一个单例对象。
        adminConfig = this;

        //初始化xxjob调度器
        xxlJobScheduler = new XxlJobScheduler();
        xxlJobScheduler.init();
    }
```
**初始化xxjob调度器**

```java
    public void init() throws Exception {
        // init i18n
        initI18n();

        // admin trigger pool start
        JobTriggerPoolHelper.toStart(); //初始化触发器线程池

        // admin registry monitor run  30秒执行一次,维护注册表信息， 判断在线超时时间90s
        JobRegistryHelper.getInstance().start();

        // admin fail-monitor run   运行事变监视器,主要失败发送邮箱,重试触发器
        JobFailMonitorHelper.getInstance().start();

        // admin lose-monitor run ( depend on JobTriggerPoolHelper ) //将丢失主机信息调度日志更改状态
        JobCompleteHelper.getInstance().start();

        // admin log report start  统计一些失败成功报表
        JobLogReportHelper.getInstance().start();

        // start-schedule  ( depend on JobTriggerPoolHelper ) 执行调度器
        JobScheduleHelper.getInstance().start();

        logger.info(">>>>>>>>> init xxl-job admin success.");
    }

```

该方法主要做了如下事情：

 1. init i18n
 2. 初始化触发器线程池
 3. 维护注册表信息(30秒执行一次)
 4. 将丢失主机信息调度日志更改状态
 5. 统计一些失败成功报表,删除过期日志
 6. 执行调度器

## 1）1：初始化触发器线程池

```java
    public static void toStart() {
        helper.start();
    }
```

```java
 public void start(){
        //最大200线程，最多处理1000任务
        fastTriggerPool = new ThreadPoolExecutor(
                10,
                XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(1000),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "xxl-job, admin JobTriggerPoolHelper-fastTriggerPool-" + r.hashCode());
                    }
                });

        //最大100线程，最多处理2000任务
        //一分钟内超时10次，则采用慢触发器执行
        slowTriggerPool = new ThreadPoolExecutor(
                10,
                XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(2000),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "xxl-job, admin JobTriggerPoolHelper-slowTriggerPool-" + r.hashCode());
                    }
                });
    }
```
 **<font color="red" size="4"  >注意：这里分别初始化了2个线程池，一个快一个慢，优先选择快，当一分钟以内任务超过10次执行时间超过500ms，则加入慢线程池执行。</font>**

## 1）2：维护注册表信息(30秒执行一次)
这里分别做了三件事

 1. 初始化注册或者删除线程池，主要负责客户端注册或者销毁到xxl_job_registry表
 2. 剔除超时注册机器
 3. 更新xxl_job_group执行器地址列表

```java
public void start(){

		// for registry or remove
		//初始化注册或者删除线程池
		registryOrRemoveThreadPool = new ThreadPoolExecutor(
				2,
				10,
				30L,
				TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(2000),
				new ThreadFactory() {
					@Override
					public Thread newThread(Runnable r) {
						return new Thread(r, "xxl-job, admin JobRegistryMonitorHelper-registryOrRemoveThreadPool-" + r.hashCode());
					}
				},
				//注意:这里的拒绝策略就是再次执行...^_^'''
				new RejectedExecutionHandler() {
					@Override
					public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
						r.run();
						logger.warn(">>>>>>>>>>> xxl-job, registry or remove too fast, match threadpool rejected handler(run now).");
					}
				});

		// for monitor    30秒执行一次,维护注册表信息， 判断在线超时时间90s
		registryMonitorThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!toStop) {
					try {
						// auto registry group
						List<XxlJobGroup> groupList = XxlJobAdminConfig.getAdminConfig().getXxlJobGroupDao().findByAddressType(0);
						if (groupList!=null && !groupList.isEmpty()) {

							// remove dead address (admin/executor)  从注册表中删除超时的机器
							List<Integer> ids = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().findDead(RegistryConfig.DEAD_TIMEOUT, new Date());
							if (ids!=null && ids.size()>0) {
								XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().removeDead(ids);
							}

							// fresh online address (admin/executor) 获取所有在线机器
							HashMap<String, List<String>> appAddressMap = new HashMap<String, List<String>>();  //维护注册表注册key和注册value
							List<XxlJobRegistry> list = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().findAll(RegistryConfig.DEAD_TIMEOUT, new Date());
							if (list != null) {
								for (XxlJobRegistry item: list) {//将注册类型为EXECUTOR的XxlJobRegistry集合改装成appname=>设置触发器的ip地址
									if (RegistryConfig.RegistType.EXECUTOR.name().equals(item.getRegistryGroup())) {
										String appname = item.getRegistryKey();
										List<String> registryList = appAddressMap.get(appname);
										if (registryList == null) {
											registryList = new ArrayList<String>();
										}

										if (!registryList.contains(item.getRegistryValue())) {
											registryList.add(item.getRegistryValue());
										}
										appAddressMap.put(appname, registryList);
									}
								}
							}

							// fresh group address    更新xxl_job_group执行器地址列表
							for (XxlJobGroup group: groupList) {
								List<String> registryList = appAddressMap.get(group.getAppname());
								String addressListStr = null;//将所有配置触发器的ip地址，使用,拼接
								if (registryList!=null && !registryList.isEmpty()) {
									Collections.sort(registryList);
									StringBuilder addressListSB = new StringBuilder();
									for (String item:registryList) {
										addressListSB.append(item).append(",");
									}
									addressListStr = addressListSB.toString();
									addressListStr = addressListStr.substring(0, addressListStr.length()-1);
								}
								group.setAddressList(addressListStr);//更新设置了触发器的ip地址
								group.setUpdateTime(new Date());//更新修改时间

								XxlJobAdminConfig.getAdminConfig().getXxlJobGroupDao().update(group);
							}
						}
					} catch (Exception e) {
						if (!toStop) {
							logger.error(">>>>>>>>>>> xxl-job, job registry monitor thread error:{}", e);
						}
					}
					try {
						TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
					} catch (InterruptedException e) {
						if (!toStop) {
							logger.error(">>>>>>>>>>> xxl-job, job registry monitor thread error:{}", e);
						}
					}
				}
				logger.info(">>>>>>>>>>> xxl-job, job registry monitor thread stop");
			}
		});
		registryMonitorThread.setDaemon(true);
		registryMonitorThread.setName("xxl-job, admin JobRegistryMonitorHelper-registryMonitorThread");
		registryMonitorThread.start();
	}
```
## 1）3：运行失败监视器,主要失败发送邮箱,重试触发器
这里分别做了三件事

 1. 失败重试

 		这里判断失败有2种情况(trigger_code表示调度中心调用执行器状态，handle_code表示执行器执行结束后回调给调度中心的状态，200均标识成功，500标识失败)
 		第一种：trigger_code!=(0,200) 且 handle_code!=0
 		第二种：handle_code!=200
 3. 告警(这里可向spring注入JobAlarm)

```java
public void start(){
		monitorThread = new Thread(new Runnable() {

			@Override
			public void run() {

				// monitor
				while (!toStop) {
					try {
						//获取执行失败的日志 调度日志表： 用于保存XXL-JOB任务调度的历史信息，如调度结果、执行结果、调度入参、调度机器和执行器等等；
						List<Long> failLogIds = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().findFailJobLogIds(1000);
						if (failLogIds!=null && !failLogIds.isEmpty()) {//1:执行触发器成功,返回值失败.2:触发器失败
							for (long failLogId: failLogIds) {

								// lock log   加锁，乐观修改alarm_status=-1
								int lockRet = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateAlarmStatus(failLogId, 0, -1);
								if (lockRet < 1) {
									continue;
								}
								XxlJobLog log = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().load(failLogId); //获取失败日志具体信息
								XxlJobInfo info = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().loadById(log.getJobId()); //加载job信息

								// 1、fail retry monitor
								if (log.getExecutorFailRetryCount() > 0) { //若可重试次数>0,则再次执行触发器
									JobTriggerPoolHelper.trigger(log.getJobId(), TriggerTypeEnum.RETRY, (log.getExecutorFailRetryCount()-1), log.getExecutorShardingParam(), log.getExecutorParam(), null);
									String retryMsg = "<br><br><span style=\"color:#F39C12;\" > >>>>>>>>>>>"+ I18nUtil.getString("jobconf_trigger_type_retry") +"<<<<<<<<<<< </span><br>";
									log.setTriggerMsg(log.getTriggerMsg() + retryMsg);
									XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateTriggerInfo(log); //修改触发器执行信息
								}

								// 2、fail alarm monitor 失败警告监视器
								int newAlarmStatus = 0;		// 告警状态：0-默认、-1=锁定状态、1-无需告警、2-告警成功、3-告警失败
								if (info!=null && info.getAlarmEmail()!=null && info.getAlarmEmail().trim().length()>0) {//若设置报警邮箱，则执行报警
									boolean alarmResult = XxlJobAdminConfig.getAdminConfig().getJobAlarmer().alarm(info, log);  //发送警报
									newAlarmStatus = alarmResult?2:3; //获取警报执行状态
								} else {//没设置报警邮箱，则更改状态为不需要告警
									newAlarmStatus = 1;
								}
								//释放锁
								XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateAlarmStatus(failLogId, -1, newAlarmStatus);
							}
						}

					} catch (Exception e) {
						if (!toStop) {
							logger.error(">>>>>>>>>>> xxl-job, job fail monitor thread error:{}", e);
						}
					}

                    try {
                        TimeUnit.SECONDS.sleep(10);
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                }

				logger.info(">>>>>>>>>>> xxl-job, job fail monitor thread stop");

			}
		});
		monitorThread.setDaemon(true);
		monitorThread.setName("xxl-job, admin JobFailMonitorHelper");
		monitorThread.start();
	}
```
在这里可通过实现**JobAlarm**接口，注入**spring**容器执行报警。

```java
boolean alarmResult = XxlJobAdminConfig.getAdminConfig().getJobAlarmer().alarm(info, log);
```

```java
 public boolean alarm(XxlJobInfo info, XxlJobLog jobLog) {

        boolean result = false;
        if (jobAlarmList!=null && jobAlarmList.size()>0) {
            result = true;  // success means all-success
            for (JobAlarm alarm: jobAlarmList) {
                boolean resultItem = false;
                try {
                    resultItem = alarm.doAlarm(info, jobLog);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                if (!resultItem) {
                    result = false;
                }
            }
        }

        return result;
    }
```
这里jobAlarmList集合里面有很多**JobAlarm**对象， 都是执行初始化方法从**spring**注入的，也就是说扩展的话，只需要实现**JobAlarm**接口，注入**spring**即可。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210429093106911.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)


## 1）4：将丢失主机信息调度日志更改状态

```java
public void start(){

		// for callback  针对回调函数处理的线程池
		callbackThreadPool = new ThreadPoolExecutor(
				2,
				20,
				30L,
				TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(3000),
				new ThreadFactory() {
					@Override
					public Thread newThread(Runnable r) {
						return new Thread(r, "xxl-job, admin JobLosedMonitorHelper-callbackThreadPool-" + r.hashCode());
					}
				},
				new RejectedExecutionHandler() {
					@Override
					public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
						r.run();
						logger.warn(">>>>>>>>>>> xxl-job, callback too fast, match threadpool rejected handler(run now).");
					}
				});


		// for monitor
		monitorThread = new Thread(new Runnable() {

			@Override
			public void run() {

				// wait for JobTriggerPoolHelper-init
				try {
					TimeUnit.MILLISECONDS.sleep(50);
				} catch (InterruptedException e) {
					if (!toStop) {
						logger.error(e.getMessage(), e);
					}
				}

				// monitor
				while (!toStop) {
					try {
						// 任务结果丢失处理：调度记录停留在 "运行中" 状态超过10min，且对应执行器心跳注册失败不在线，则将本地调度主动标记失败；
						Date losedTime = DateUtil.addMinutes(new Date(), -10); //调度日志表： 用于保存XXL-JOB任务调度的历史信息，如调度结果、执行结果、调度入参、调度机器和执行器等等；
						List<Long> losedJobIds  = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().findLostJobIds(losedTime);

						if (losedJobIds!=null && losedJobIds.size()>0) {
							for (Long logId: losedJobIds) {

								XxlJobLog jobLog = new XxlJobLog();
								jobLog.setId(logId);

								jobLog.setHandleTime(new Date());
								jobLog.setHandleCode(ReturnT.FAIL_CODE);
								jobLog.setHandleMsg( I18nUtil.getString("joblog_lost_fail") );

								//更改处理状态
								XxlJobCompleter.updateHandleInfoAndFinish(jobLog);
							}

						}
					} catch (Exception e) {
						if (!toStop) {
							logger.error(">>>>>>>>>>> xxl-job, job fail monitor thread error:{}", e);
						}
					}

                    try {
                        TimeUnit.SECONDS.sleep(60);
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                }

				logger.info(">>>>>>>>>>> xxl-job, JobLosedMonitorHelper stop");

			}
		});
		monitorThread.setDaemon(true);
		monitorThread.setName("xxl-job, admin JobLosedMonitorHelper");
		monitorThread.start();
	}
```
更改处理状态

```java
   public static int updateHandleInfoAndFinish(XxlJobLog xxlJobLog) {

        // finish 若父任务正常结束，则终止子任务,以及设置Childmsg
        finishJob(xxlJobLog);

        // text最大64kb 避免长度过长 截断超过长度限制字符
        if (xxlJobLog.getHandleMsg().length() > 15000) {
            xxlJobLog.setHandleMsg( xxlJobLog.getHandleMsg().substring(0, 15000) );
        }

        // fresh handle 更新超时joblog
        return XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateHandleInfo(xxlJobLog);
    }
```
## 1）5：统计一些失败成功报表,删除过期日志

```java
  public void start(){
        logrThread = new Thread(new Runnable() {
            //每分钟刷新一次
            @Override
            public void run() {

                // last clean log time   记录上次清除日志时间
                long lastCleanLogTime = 0;


                while (!toStop) {

                    // 1、log-report refresh: refresh log report in 3 days
                    try {

                        for (int i = 0; i < 3; i++) {

                            // today  分别统计今天,昨天,前天0~24点的数据
                            Calendar itemDay = Calendar.getInstance();
                            itemDay.add(Calendar.DAY_OF_MONTH, -i);
                            itemDay.set(Calendar.HOUR_OF_DAY, 0);
                            itemDay.set(Calendar.MINUTE, 0);
                            itemDay.set(Calendar.SECOND, 0);
                            itemDay.set(Calendar.MILLISECOND, 0);

                            Date todayFrom = itemDay.getTime();

                            itemDay.set(Calendar.HOUR_OF_DAY, 23);
                            itemDay.set(Calendar.MINUTE, 59);
                            itemDay.set(Calendar.SECOND, 59);
                            itemDay.set(Calendar.MILLISECOND, 999);

                            Date todayTo = itemDay.getTime();

                            // refresh log-report every minute
                            //设置默认值
                            XxlJobLogReport xxlJobLogReport = new XxlJobLogReport();
                            xxlJobLogReport.setTriggerDay(todayFrom);
                            xxlJobLogReport.setRunningCount(0);
                            xxlJobLogReport.setSucCount(0);
                            xxlJobLogReport.setFailCount(0);

                            //查询失败, 成功，总的调用次数
                            Map<String, Object> triggerCountMap = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().findLogReport(todayFrom, todayTo);
                            if (triggerCountMap!=null && triggerCountMap.size()>0) {
                                int triggerDayCount = triggerCountMap.containsKey("triggerDayCount")?Integer.valueOf(String.valueOf(triggerCountMap.get("triggerDayCount"))):0;
                                int triggerDayCountRunning = triggerCountMap.containsKey("triggerDayCountRunning")?Integer.valueOf(String.valueOf(triggerCountMap.get("triggerDayCountRunning"))):0;
                                int triggerDayCountSuc = triggerCountMap.containsKey("triggerDayCountSuc")?Integer.valueOf(String.valueOf(triggerCountMap.get("triggerDayCountSuc"))):0;
                                int triggerDayCountFail = triggerDayCount - triggerDayCountRunning - triggerDayCountSuc;

                                xxlJobLogReport.setRunningCount(triggerDayCountRunning);
                                xxlJobLogReport.setSucCount(triggerDayCountSuc);
                                xxlJobLogReport.setFailCount(triggerDayCountFail);
                            }

                            // do refresh
                            //刷新调用次数,若找不到则默认都是0
                            int ret = XxlJobAdminConfig.getAdminConfig().getXxlJobLogReportDao().update(xxlJobLogReport);
                            if (ret < 1) {
                                //没数据则保存
                                XxlJobAdminConfig.getAdminConfig().getXxlJobLogReportDao().save(xxlJobLogReport);
                            }
                        }

                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(">>>>>>>>>>> xxl-job, job log report thread error:{}", e);
                        }
                    }

                    // 2、log-clean: switch open & once each day
                    //设置了保留日志天数且日志保留了24小时，则进入
                    if (XxlJobAdminConfig.getAdminConfig().getLogretentiondays()>0
                            && System.currentTimeMillis() - lastCleanLogTime > 24*60*60*1000) {

                        // expire-time
                        //通过日志保留天数算出清除log时间
                        Calendar expiredDay = Calendar.getInstance();
                        expiredDay.add(Calendar.DAY_OF_MONTH, -1 * XxlJobAdminConfig.getAdminConfig().getLogretentiondays());
                        expiredDay.set(Calendar.HOUR_OF_DAY, 0);
                        expiredDay.set(Calendar.MINUTE, 0);
                        expiredDay.set(Calendar.SECOND, 0);
                        expiredDay.set(Calendar.MILLISECOND, 0);
                        Date clearBeforeTime = expiredDay.getTime();

                        // clean expired log
                        List<Long> logIds = null;
                        do {
                            //这里传了3个0表示查询所有,而不是单个任务id
                            logIds = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().findClearLogIds(0, 0, clearBeforeTime, 0, 1000);
                            //删除过期数据
                            if (logIds!=null && logIds.size()>0) {
                                XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().clearLog(logIds);
                            }
                        } while (logIds!=null && logIds.size()>0);

                        // update clean time
                        lastCleanLogTime = System.currentTimeMillis();
                    }

                    try {
                        TimeUnit.MINUTES.sleep(1);
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                }

                logger.info(">>>>>>>>>>> xxl-job, job log report thread stop");

            }
        });
        logrThread.setDaemon(true);
        logrThread.setName("xxl-job, admin JobLogReportHelper");
        logrThread.start();
    }
```
## 1）6：执行调度器
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210416083911523.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)


```java
   public void start(){

        // schedule thread
        scheduleThread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {  //保证5秒执行一次
                    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis()%1000 );
                } catch (InterruptedException e) {
                    if (!scheduleThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>> init xxl-job admin scheduler success.");

                // pre-read count: treadpool-size * trigger-qps (each trigger cost 50ms, qps = 1000/50 = 20)  每个触发器花费50ms,每个线程单位时间内处理20任务,最多同时处理300*20=6000任务
                int preReadCount = (XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax() + XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax()) * 20;

                while (!scheduleThreadToStop) {

                    // Scan Job
                    long start = System.currentTimeMillis();

                    Connection conn = null;
                    Boolean connAutoCommit = null;
                    PreparedStatement preparedStatement = null;

                    boolean preReadSuc = true;
                    try {
                        //设置手动提交
                        conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        connAutoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);
                        //获取任务调度锁表内数据信息,加写锁
                        preparedStatement = conn.prepareStatement(  "select * from xxl_job_lock where lock_name = 'schedule_lock' for update" );
                        preparedStatement.execute();

                        // tx start

                        // 1、pre read
                        long nowTime = System.currentTimeMillis();   //获取当前时间后5秒,同时最多负载的分页数
                        List<XxlJobInfo> scheduleList = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleJobQuery(nowTime + PRE_READ_MS, preReadCount);
                        if (scheduleList!=null && scheduleList.size()>0) {
                            // 2、push time-ring
                            for (XxlJobInfo jobInfo: scheduleList) {

                                // time-ring jump
                                if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {  //触发器过期时间>5s
                                    // 2.1、trigger-expire > 5s：pass && make next-trigger-time
                                    logger.warn(">>>>>>>>>>> xxl-job, schedule misfire, jobId = " + jobInfo.getId());

                                    // 1、misfire match
//                                    - 调度过期策略：
//                                    - 忽略：调度过期后，忽略过期的任务，从当前时间开始重新计算下次触发时间；
//                                    - 立即执行一次：调度过期后，立即执行一次，并从当前时间开始重新计算下次触发时间；
                                    MisfireStrategyEnum misfireStrategyEnum = MisfireStrategyEnum.match(jobInfo.getMisfireStrategy(), MisfireStrategyEnum.DO_NOTHING);
                                    if (MisfireStrategyEnum.FIRE_ONCE_NOW == misfireStrategyEnum) { //若过期策略为FIRE_ONCE_NOW,则立即执行一次
                                        // FIRE_ONCE_NOW 》 trigger //执行触发器
                                        JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.MISFIRE, -1, null, null, null);
                                        logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );
                                    }

                                    // 2、fresh next  更新下次执行时间
                                    refreshNextValidTime(jobInfo, new Date());

                                } else if (nowTime > jobInfo.getTriggerNextTime()) { //触发器过期时间<5s
                                    // 2.2、trigger-expire < 5s：direct-trigger && make next-trigger-time

                                    // 1、trigger //执行触发器
                                    JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null, null);
                                    logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );

                                    // 2、fresh next 更新下次执行时间
                                    refreshNextValidTime(jobInfo, new Date());

                                    // next-trigger-time in 5s, pre-read again 下次触发时间在当前时间往后5秒范围内
                                    if (jobInfo.getTriggerStatus()==1 && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {

                                        // 1、make ring second 获取下次执行秒
                                        int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                        // 2、push time ring
                                        pushTimeRing(ringSecond, jobInfo.getId());

                                        // 3、fresh next 更新下次执行时间
                                        refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                    }

                                } else {
                                    // 2.3、trigger-pre-read：time-ring trigger && make next-trigger-time
                                    //未来五秒以内执行的所有任务添加到ringData
                                    // 1、make ring second
                                    int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                    // 2、push time ring
                                    pushTimeRing(ringSecond, jobInfo.getId());

                                    // 3、fresh next
                                    refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                }

                            }

                            // 3、update trigger info 更新执行时间和上次执行时间到数据库
                            for (XxlJobInfo jobInfo: scheduleList) {
                                XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleUpdate(jobInfo);
                            }

                        } else {
                            preReadSuc = false;
                        }

                        // tx stop


                    } catch (Exception e) {
                        if (!scheduleThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread error:{}", e);
                        }
                    } finally {

                        // commit
                        if (conn != null) {
                            try {
                                conn.commit();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.setAutoCommit(connAutoCommit);
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }

                        // close PreparedStatement
                        if (null != preparedStatement) {
                            try {
                                preparedStatement.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                    long cost = System.currentTimeMillis()-start;


                    // Wait seconds, align second
                    if (cost < 1000) {  // scan-overtime, not wait
                        try {
                            // pre-read period: success > scan each second; fail > skip this period;
                            //若执行成功,每秒执行一次，失败则跳过5秒
                            TimeUnit.MILLISECONDS.sleep((preReadSuc?1000:PRE_READ_MS) - System.currentTimeMillis()%1000);
                        } catch (InterruptedException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }

                }

                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread stop");
            }
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("xxl-job, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();


        // ring thread
        ringThread = new Thread(new Runnable() {
            @Override
            public void run() {

                while (!ringThreadToStop) {

                    // align second
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                    } catch (InterruptedException e) {
                        if (!ringThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                    try {
                        // second data
                        List<Integer> ringItemData = new ArrayList<>();
                        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);   // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                        for (int i = 0; i < 2; i++) {
                            List<Integer> tmpData = ringData.remove( (nowSecond+60-i)%60 );
                            if (tmpData != null) {
                                ringItemData.addAll(tmpData);
                            }
                        }

                        // ring trigger
                        logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData) );
                        if (ringItemData.size() > 0) {
                            // do trigger
                            for (int jobId: ringItemData) {
                                // do trigger //执行触发器
                                JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
                            }
                            // clear
                            ringItemData.clear();
                        }
                    } catch (Exception e) {
                        if (!ringThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
            }
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }
```

# 2）客户端启动流程

**构建作业执行器**

这里将 <font color="red" size="3">XxlJobSpringExecutor</font> 交由容器托管
```java
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        logger.info(">>>>>>>>>>> xxl-job config init.");
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        //xxjob管理地址
        xxlJobSpringExecutor.setAdminAddresses(adminAddresses);
        //注册应用名称
        xxlJobSpringExecutor.setAppname(appname);
        //xxl作业执行器注册表地址
        xxlJobSpringExecutor.setAddress(address);
        xxlJobSpringExecutor.setIp(ip);
        xxlJobSpringExecutor.setPort(port);
        //注册地址的token
        xxlJobSpringExecutor.setAccessToken(accessToken);
        //日志路径
        xxlJobSpringExecutor.setLogPath(logPath);
        //日志保存天数
        xxlJobSpringExecutor.setLogRetentionDays(logRetentionDays);

        return xxlJobSpringExecutor;
    }
```
老规矩，直接看接口继承体系，可以发现注入了 <font color="red" size="3">ApplicationContext</font> 对象。以及实现了 <font color="red" size="3">SmartInitializingSingleton</font> 接口，实现该接口的当**spring**容器初始完成，紧接着执行监听器发送监听后，就会遍历所有的Bean然后初始化所有单例非懒加载的bean，最后在实例化阶段结束时触发回调接口。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210416085627256.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

```java
 // start
    @Override
    public void afterSingletonsInstantiated() {

        // init JobHandler Repository
        /*initJobHandlerRepository(applicationContext);*/

        // init JobHandler Repository (for method)   初始化调度器资源管理器
        initJobHandlerMethodRepository(applicationContext);

        // refresh GlueFactory 刷新GlueFactory
        GlueFactory.refreshInstance(1);

        // super start
        try {
            super.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
```
该方法主要做了如下事情：

 1. 初始化调度器资源管理器



## 2）1：初始化调度器资源管理器

该方法主要做了如下事情：

 1.从spring容器获取所有对象，并遍历查找方法上标记XxlJob注解的方法
 2. 将xxljob配置的jobname作为key，对象,反射的执行,初始,销毁方法作为value注册<font color="blue" size="3">jobHandlerRepository </font>中


```java
  private void initJobHandlerMethodRepository(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return;
        }
        // init job handler from method
        String[] beanDefinitionNames = applicationContext.getBeanNamesForType(Object.class, false, true);
        for (String beanDefinitionName : beanDefinitionNames) {//遍历每个容器对象
            Object bean = applicationContext.getBean(beanDefinitionName);

            Map<Method, XxlJob> annotatedMethods = null;   // referred to ：org.springframework.context.event.EventListenerMethodProcessor.processBean
            try { //获取每个注解XxlJob方法
                annotatedMethods = MethodIntrospector.selectMethods(bean.getClass(),
                        new MethodIntrospector.MetadataLookup<XxlJob>() {
                            @Override
                            public XxlJob inspect(Method method) {
                                return AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
                            }
                        });
            } catch (Throwable ex) {
                logger.error("xxl-job method-jobhandler resolve error for bean[" + beanDefinitionName + "].", ex);
            }
            if (annotatedMethods==null || annotatedMethods.isEmpty()) {
                continue;
            }
            //遍历标记了XxlJob注解的方法
            for (Map.Entry<Method, XxlJob> methodXxlJobEntry : annotatedMethods.entrySet()) {
                Method executeMethod = methodXxlJobEntry.getKey();
                XxlJob xxlJob = methodXxlJobEntry.getValue();
                if (xxlJob == null) {
                    continue;
                }

                String name = xxlJob.value();//获取配置xxjob的触发器名称
                if (name.trim().length() == 0) {
                    throw new RuntimeException("xxl-job method-jobhandler name invalid, for[" + bean.getClass() + "#" + executeMethod.getName() + "] .");
                }
                if (loadJobHandler(name) != null) { //工作处理资源库是否有相同命名
                    throw new RuntimeException("xxl-job jobhandler[" + name + "] naming conflicts.");
                }

                // execute method
                /*if (!(method.getParameterTypes().length == 1 && method.getParameterTypes()[0].isAssignableFrom(String.class))) {
                    throw new RuntimeException("xxl-job method-jobhandler param-classtype invalid, for[" + bean.getClass() + "#" + method.getName() + "] , " +
                            "The correct method format like \" public ReturnT<String> execute(String param) \" .");
                }
                if (!method.getReturnType().isAssignableFrom(ReturnT.class)) {
                    throw new RuntimeException("xxl-job method-jobhandler return-classtype invalid, for[" + bean.getClass() + "#" + method.getName() + "] , " +
                            "The correct method format like \" public ReturnT<String> execute(String param) \" .");
                }*/

                executeMethod.setAccessible(true);//设置可访问,设置后可通过反射调用私有方法

                // init and destory
                Method initMethod = null;
                Method destroyMethod = null;

                if (xxlJob.init().trim().length() > 0) {
                    try {  //获取XxlJob标记的方法,配置的init方法
                        initMethod = bean.getClass().getDeclaredMethod(xxlJob.init());
                        initMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler initMethod invalid, for[" + bean.getClass() + "#" + executeMethod.getName() + "] .");
                    }
                }
                if (xxlJob.destroy().trim().length() > 0) {
                    try {//获取XxlJob标记的方法,配置的destroy方法
                        destroyMethod = bean.getClass().getDeclaredMethod(xxlJob.destroy());
                        destroyMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler destroyMethod invalid, for[" + bean.getClass() + "#" + executeMethod.getName() + "] .");
                    }
                }

                // registry jobhandler 将xxljob配置的jobname作为key，对象,反射的执行,初始,销毁方法作为value注册jobHandlerRepository中
                registJobHandler(name, new MethodJobHandler(bean, executeMethod, initMethod, destroyMethod));
            }
        }

    }

```
## 2）2：刷新GlueFactory

```java
	public static void refreshInstance(int type){
		if (type == 0) {
			glueFactory = new GlueFactory();
		} else if (type == 1) {
			glueFactory = new SpringGlueFactory();
		}
	}
```
## 2）3：核心启动类 
该方法主要做了如下事情：

 1. 初始化日志文件
 2. 初始化admin链接路径存储集合
 3. 清除过期日志
 4. 回调调度中心任务执行状态
 5. 执行内嵌服务

 

```java
    public void start() throws Exception {

        // init logpath
        XxlJobFileAppender.initLogPath(logPath);//初始化日志文件

        // init invoker, admin-client
        initAdminBizList(adminAddresses, accessToken); //初始化admin链接路径存储集合


        // init JobLogFileCleanThread  清除过期日志
        JobLogFileCleanThread.getInstance().start(logRetentionDays);

        // init TriggerCallbackThread
        TriggerCallbackThread.getInstance().start();

        // init executor-server 执行内嵌服务
        initEmbedServer(address, ip, port, appname, accessToken);
    }
```

### 2）3）1：初始化日志文件

```java
	public static void initLogPath(String logPath){
		// init
		if (logPath!=null && logPath.trim().length()>0) {
			logBasePath = logPath;
		}
		// mk base dir 日志文件不存在则创建
		File logPathDir = new File(logBasePath);
		if (!logPathDir.exists()) {
			logPathDir.mkdirs();
		}
		logBasePath = logPathDir.getPath();

		// mk glue dir 创建glue目录
		File glueBaseDir = new File(logPathDir, "gluesource");
		if (!glueBaseDir.exists()) {
			glueBaseDir.mkdirs();
		}
		glueSrcPath = glueBaseDir.getPath();
	}
```
### 2）3）2：初始化admin链接路径存储集合

```java
   private void initAdminBizList(String adminAddresses, String accessToken) throws Exception {
        if (adminAddresses!=null && adminAddresses.trim().length()>0) {
            for (String address: adminAddresses.trim().split(",")) { //多个admin地址以,分隔
                if (address!=null && address.trim().length()>0) {

                    AdminBiz adminBiz = new AdminBizClient(address.trim(), accessToken);

                    if (adminBizList == null) {
                        adminBizList = new ArrayList<AdminBiz>();
                    } //将admin地址以及token添加adminBiz中
                    adminBizList.add(adminBiz);
                }
            }
        }
    }
```
### 2）3）3：主要清除过期日志

```java
 public void start(final long logRetentionDays){

        // limit min value 日志最大保存天数<3天,直接退出
        if (logRetentionDays < 3 ) {
            return;
        }
        //一天执行一次
        localThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!toStop) {
                    try {
                        // clean log dir, over logRetentionDays 查询目录下所有子文件(包含目录)
                        File[] childDirs = new File(XxlJobFileAppender.getLogPath()).listFiles();
                        if (childDirs!=null && childDirs.length>0) {

                            // today   获取今天0点时间
                            Calendar todayCal = Calendar.getInstance();
                            todayCal.set(Calendar.HOUR_OF_DAY,0);
                            todayCal.set(Calendar.MINUTE,0);
                            todayCal.set(Calendar.SECOND,0);
                            todayCal.set(Calendar.MILLISECOND,0);

                            Date todayDate = todayCal.getTime();

                            for (File childFile: childDirs) {

                                // valid  不是目录跳过
                                if (!childFile.isDirectory()) {
                                    continue;
                                }//查询不到'-'则跳过
                                if (childFile.getName().indexOf("-") == -1) {
                                    continue;
                                }

                                // file create date  获取文件创建时间,文件都是以年-月-日命名的
                                Date logFileCreateDate = null;
                                try {
                                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
                                    logFileCreateDate = simpleDateFormat.parse(childFile.getName());
                                } catch (ParseException e) {
                                    logger.error(e.getMessage(), e);
                                }
                                if (logFileCreateDate == null) {
                                    continue;
                                }
                                //大于日志最大存活时间则清除
                                if ((todayDate.getTime()-logFileCreateDate.getTime()) >= logRetentionDays * (24 * 60 * 60 * 1000) ) {
                                    FileUtil.deleteRecursively(childFile);//超过保存天数则清除日志
                                }

                            }
                        }

                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }

                    }

                    try { //睡眠一天处理
                        TimeUnit.DAYS.sleep(1);
                    } catch (InterruptedException e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, executor JobLogFileCleanThread thread destory.");

            }
        });
        localThread.setDaemon(true);
        localThread.setName("xxl-job, executor JobLogFileCleanThread");
        localThread.start();
    }
```
### 2）3）4：  回调调度中心任务执行状态
具体看主动触发->客户端回调服务端
```java
 public void start() {

        // valid 是否有配置admin路径
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor callback config fail, adminAddresses is null.");
            return;
        }

        // callback
        triggerCallbackThread = new Thread(new Runnable() {

            @Override
            public void run() {

                // normal callback
                while(!toStop){
                    try {
                        //获取回调参数
                        HandleCallbackParam callback = getInstance().callBackQueue.take();
                        if (callback != null) {

                            // callback list param
                            List<HandleCallbackParam> callbackParamList = new ArrayList<HandleCallbackParam>();
                            //移除队列中所有元素到callbackParamList中
                            int drainToNum = getInstance().callBackQueue.drainTo(callbackParamList);
                            callbackParamList.add(callback);

                            // callback, will retry if error 通知admin
                            if (callbackParamList!=null && callbackParamList.size()>0) {
                                doCallback(callbackParamList);
                            }
                        }
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }

                // last callback
                try {
                    List<HandleCallbackParam> callbackParamList = new ArrayList<HandleCallbackParam>();
                    int drainToNum = getInstance().callBackQueue.drainTo(callbackParamList);
                    if (callbackParamList!=null && callbackParamList.size()>0) {
                        doCallback(callbackParamList);
                    }
                } catch (Exception e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, executor callback thread destory.");

            }
        });
        triggerCallbackThread.setDaemon(true);
        triggerCallbackThread.setName("xxl-job, executor TriggerCallbackThread");
        triggerCallbackThread.start();


        // retry
        triggerRetryCallbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(!toStop){
                    try {
                        retryFailCallbackFile();
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }

                    }
                    try {
                        TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
                    } catch (InterruptedException e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, executor retry callback thread destory.");
            }
        });
        triggerRetryCallbackThread.setDaemon(true);
        triggerRetryCallbackThread.start();

    }
```

### 2）3）5：  执行内嵌服务
该方法主要做了如下事情：

 1. 使用netty开放端口，等待服务端调用
 2. 注册到服务端(心跳30S)
 3. 向服务端申请剔除服务

 

```java
    private void initEmbedServer(String address, String ip, int port, String appname, String accessToken) throws Exception {

        // fill ip port  若没设置端口,则寻找可用端口
        port = port>0?port: NetUtil.findAvailablePort(9999);
        ip = (ip!=null&&ip.trim().length()>0)?ip: IpUtil.getIp(); //若没设置IP，则获取本机Ip

        // generate address 构造地址,若没设置地址,则将ip,port拼接成地址
        if (address==null || address.trim().length()==0) {
            String ip_port_address = IpUtil.getIpPort(ip, port);   // registry-address：default use address to registry , otherwise use ip:port if address is null
            address = "http://{ip_port}/".replace("{ip_port}", ip_port_address);
        }

        // accessToken
        if (accessToken==null || accessToken.trim().length()==0) {
            logger.warn(">>>>>>>>>>> xxl-job accessToken is empty. To ensure system security, please set the accessToken.");
        }

        // start  启动嵌入服务器 ,向服务端注册,以及监听端口,主要服务服务端调用。
        embedServer = new EmbedServer();
        embedServer.start(address, port, appname, accessToken);
    }
```

```java
 public void start(final String address, final int port, final String appname, final String accessToken) {
        executorBiz = new ExecutorBizImpl();
        thread = new Thread(new Runnable() {

            @Override
            public void run() {

                // param
                EventLoopGroup bossGroup = new NioEventLoopGroup();
                EventLoopGroup workerGroup = new NioEventLoopGroup();
                ThreadPoolExecutor bizThreadPool = new ThreadPoolExecutor(
                        0,
                        200,
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>(2000),
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                return new Thread(r, "xxl-rpc, EmbedServer bizThreadPool-" + r.hashCode());
                            }
                        },
                        new RejectedExecutionHandler() {
                            @Override
                            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                                throw new RuntimeException("xxl-job, EmbedServer bizThreadPool is EXHAUSTED!");
                            }
                        });


                try {
                    // start server
                    ServerBootstrap bootstrap = new ServerBootstrap();
                    bootstrap.group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                @Override
                                public void initChannel(SocketChannel channel) throws Exception {
                                    channel.pipeline()
                                            .addLast(new IdleStateHandler(0, 0, 30 * 3, TimeUnit.SECONDS))  // beat 3N, close if idle
                                            .addLast(new HttpServerCodec())
                                            .addLast(new HttpObjectAggregator(5 * 1024 * 1024))  // merge request & reponse to FULL
                                            .addLast(new EmbedHttpServerHandler(executorBiz, accessToken, bizThreadPool));
                                }
                            })
                            .childOption(ChannelOption.SO_KEEPALIVE, true);

                    // bind 异步绑定port上
                    ChannelFuture future = bootstrap.bind(port).sync();

                    logger.info(">>>>>>>>>>> xxl-job remoting server start success, nettype = {}, port = {}", EmbedServer.class, port);

                    // start registry  //注册
                    startRegistry(appname, address);

                    // wait util stop
                    future.channel().closeFuture().sync();

                } catch (InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        logger.info(">>>>>>>>>>> xxl-job remoting server stop.");
                    } else {
                        logger.error(">>>>>>>>>>> xxl-job remoting server error.", e);
                    }
                } finally {
                    // stop
                    try {
                        workerGroup.shutdownGracefully();
                        bossGroup.shutdownGracefully();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }

            }

        });
        thread.setDaemon(true);	// daemon, service jvm, user thread leave >>> daemon leave >>> jvm leave
        thread.start();
    }
```
向服务端注册,默认30秒执行一次

```java
    public void startRegistry(final String appname, final String address) {
        // start registry
        ExecutorRegistryThread.getInstance().start(appname, address);
    }
```

```java
    public void start(final String appname, final String address){

        // valid appname不允许为null
        if (appname==null || appname.trim().length()==0) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, appname is null.");
            return;
        }//服务端地址不能为null
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, adminAddresses is null.");
            return;
        }

        registryThread = new Thread(new Runnable() {
            @Override
            public void run() {

                // registry
                while (!toStop) {
                    try {
                        RegistryParam registryParam = new RegistryParam(RegistryConfig.RegistType.EXECUTOR.name(), appname, address);
                        for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
                            try {
                                ReturnT<String> registryResult = adminBiz.registry(registryParam); //向server注册服务(http请求),注册内容appname,当前服务监听地址
                                if (registryResult!=null && ReturnT.SUCCESS_CODE == registryResult.getCode()) { //访问成功
                                    registryResult = ReturnT.SUCCESS;
                                    logger.debug(">>>>>>>>>>> xxl-job registry success, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                    break;
                                } else {
                                    logger.info(">>>>>>>>>>> xxl-job registry fail, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                }
                            } catch (Exception e) {
                                logger.info(">>>>>>>>>>> xxl-job registry error, registryParam:{}", registryParam, e);
                            }

                        }
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }

                    }

                    try {
                        if (!toStop) { //心跳时间30秒
                            TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
                        }
                    } catch (InterruptedException e) {
                        if (!toStop) {
                            logger.warn(">>>>>>>>>>> xxl-job, executor registry thread interrupted, error msg:{}", e.getMessage());
                        }
                    }
                }

                // registry remove  删除注册
                try {
                    RegistryParam registryParam = new RegistryParam(RegistryConfig.RegistType.EXECUTOR.name(), appname, address);
                    for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
                        try {  
                            ReturnT<String> registryResult = adminBiz.registryRemove(registryParam);
                            if (registryResult!=null && ReturnT.SUCCESS_CODE == registryResult.getCode()) {
                                registryResult = ReturnT.SUCCESS;
                                logger.info(">>>>>>>>>>> xxl-job registry-remove success, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                break;
                            } else {
                                logger.info(">>>>>>>>>>> xxl-job registry-remove fail, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                            }
                        } catch (Exception e) {
                            if (!toStop) {
                                logger.info(">>>>>>>>>>> xxl-job registry-remove error, registryParam:{}", registryParam, e);
                            }

                        }

                    }
                } catch (Exception e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, executor registry thread destory.");

            }
        });
        registryThread.setDaemon(true);
        registryThread.setName("xxl-job, executor ExecutorRegistryThread");
        registryThread.start();
    }
```





# 3) 服务注册
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210422215550939.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)



com.xxl.job.core.thread.ExecutorRegistryThread#start
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210416222043733.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

```java
    @Override
    public ReturnT<String> registry(RegistryParam registryParam) {
        return XxlJobRemotingUtil.postBody(addressUrl + "api/registry", accessToken, timeout, registryParam, String.class);
    }
```
服务端
com.xxl.job.admin.controller.JobApiController#api
![在这里插入图片描述](https://img-blog.csdnimg.cn/2021041622231310.png)

```java
    public ReturnT<String> registry(RegistryParam registryParam) {
        return JobRegistryHelper.getInstance().registry(registryParam);
    }
```

```java
	public ReturnT<String> registry(RegistryParam registryParam) {

		// valid  校验
		if (!StringUtils.hasText(registryParam.getRegistryGroup())
				|| !StringUtils.hasText(registryParam.getRegistryKey())
				|| !StringUtils.hasText(registryParam.getRegistryValue())) {
			return new ReturnT<String>(ReturnT.FAIL_CODE, "Illegal Argument.");
		}

		// async execute 异步注册
		registryOrRemoveThreadPool.execute(new Runnable() {
			@Override
			public void run() { //更新修改时间
				int ret = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().registryUpdate(registryParam.getRegistryGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue(), new Date());
				if (ret < 1) {//说明暂未数据,才新增
					XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().registrySave(registryParam.getRegistryGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue(), new Date());

					// fresh  空实现
					freshGroupRegistryInfo(registryParam);
				}
			}
		});

		return ReturnT.SUCCESS;
	}
```
客户端接收响应
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210416224706376.png)
# 4) 主动触发


## 4）1：服务端
触发地址：<font color="red" size="3">com.xxl.job.admin.controller.JobInfoController#triggerJob </font>

```java
	@RequestMapping("/trigger")
	@ResponseBody
	//@PermissionLimit(limit = false)
	public ReturnT<String> triggerJob(int id, String executorParam, String addressList) {
		// force cover job param 设置默认值
		if (executorParam == null) {
			executorParam = "";
		}
		//触发器类型，手动 ，重试次数，'执行器任务分片参数，格式如 1/2'，任务参数，机器地址
		JobTriggerPoolHelper.trigger(id, TriggerTypeEnum.MANUAL, -1, null, executorParam, addressList);
		return ReturnT.SUCCESS;
	}
```
执行触发器

```java
    public static void trigger(int jobId, TriggerTypeEnum triggerType, int failRetryCount, String executorShardingParam, String executorParam, String addressList) {
        helper.addTrigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam, addressList);
    }
```

```java
   public void addTrigger(final int jobId,
                           final TriggerTypeEnum triggerType,
                           final int failRetryCount,
                           final String executorShardingParam,
                           final String executorParam,
                           final String addressList) {

        // choose thread pool  获取线程池
        ThreadPoolExecutor triggerPool_ = fastTriggerPool;
        //获取超时次数
        AtomicInteger jobTimeoutCount = jobTimeoutCountMap.get(jobId);
        //一分钟内超时10次，则采用慢触发器执行
        if (jobTimeoutCount!=null && jobTimeoutCount.get() > 10) {      // job-timeout 10 times in 1 min
            triggerPool_ = slowTriggerPool;
        }

        // trigger
        triggerPool_.execute(new Runnable() {
            @Override
            public void run() {

                long start = System.currentTimeMillis();

                try {
                    // do trigger //执行触发器
                    XxlJobTrigger.trigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam, addressList);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                } finally {

                    // check timeout-count-map  更新成为下一分钟
                    long minTim_now = System.currentTimeMillis()/60000;
                    if (minTim != minTim_now) {
                        minTim = minTim_now; //当达到下一分钟则清除超时任务
                        jobTimeoutCountMap.clear();
                    }

                    // incr timeout-count-map
                    long cost = System.currentTimeMillis()-start;
                    if (cost > 500) {       // ob-timeout threshold 500ms
                        //执行时间超过500ms,则记录执行次数
                        AtomicInteger timeoutCount = jobTimeoutCountMap.putIfAbsent(jobId, new AtomicInteger(1));
                        if (timeoutCount != null) {
                            timeoutCount.incrementAndGet();
                        }
                    }

                }

            }
        });
    }
```
**<font color="red" size="4"  >注意当触发器在一分钟内超时10次，则采用慢触发器执行</font>**

```java
    public static void trigger(int jobId,
                               TriggerTypeEnum triggerType,
                               int failRetryCount,
                               String executorShardingParam,
                               String executorParam,
                               String addressList) {

        // load data  从数据库获取任务详情信息
        XxlJobInfo jobInfo = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().loadById(jobId);
        if (jobInfo == null) {
            logger.warn(">>>>>>>>>>>> trigger fail, jobId invalid，jobId={}", jobId);
            return;
        }
        if (executorParam != null) {
            //设置任务参数
            jobInfo.setExecutorParam(executorParam);
        }
        //获取失败重试次数
        int finalFailRetryCount = failRetryCount>=0?failRetryCount:jobInfo.getExecutorFailRetryCount();
        //获取job分组信息
        XxlJobGroup group = XxlJobAdminConfig.getAdminConfig().getXxlJobGroupDao().load(jobInfo.getJobGroup());

        // cover addressList  设置地址集合
        if (addressList!=null && addressList.trim().length()>0) {
            group.setAddressType(1);
            group.setAddressList(addressList.trim());
        }

        // sharding param  拆分executorParam任务参数,填入shardingParam数组
        int[] shardingParam = null;
        if (executorShardingParam!=null){
            String[] shardingArr = executorParam.split("/");
            if (shardingArr.length==2 && isNumeric(shardingArr[0]) && isNumeric(shardingArr[1])) {
                shardingParam = new int[2];
                shardingParam[0] = Integer.valueOf(shardingArr[0]);
                shardingParam[1] = Integer.valueOf(shardingArr[1]);
            }
        } //如果路由策略是分片广播模式，同时注册地址不为空
        if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST==ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null)
                && group.getRegistryList()!=null && !group.getRegistryList().isEmpty()
                && shardingParam==null) {
            for (int i = 0; i < group.getRegistryList().size(); i++) {  //遍历执行每个注册地址，集群广播
                processTrigger(group, jobInfo, finalFailRetryCount, triggerType, i, group.getRegistryList().size());
            }
        } else {
            if (shardingParam == null) {  //当shardingParam为空，设置默认值,分别标识
                shardingParam = new int[]{0, 1};
            } //执行触发器
            processTrigger(group, jobInfo, finalFailRetryCount, triggerType, shardingParam[0], shardingParam[1]);
        }

    }
```
这里会依据路由策略模式,选择对应的路由方式处理,采用了策略模式.

```java
  private static void processTrigger(XxlJobGroup group, XxlJobInfo jobInfo, int finalFailRetryCount, TriggerTypeEnum triggerType, int index, int total){

        // param  获取阻塞处理策略
        ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), ExecutorBlockStrategyEnum.SERIAL_EXECUTION);  // block strategy
        // route strategy 获取路由策略,默认first
        ExecutorRouteStrategyEnum executorRouteStrategyEnum = ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null);
        String shardingParam = (ExecutorRouteStrategyEnum.SHARDING_BROADCAST==executorRouteStrategyEnum)?String.valueOf(index).concat("/").concat(String.valueOf(total)):null;

        // 1、save log-id 保存执行日志
        XxlJobLog jobLog = new XxlJobLog();
        jobLog.setJobGroup(jobInfo.getJobGroup());
        jobLog.setJobId(jobInfo.getId());
        jobLog.setTriggerTime(new Date());
        XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().save(jobLog);
        logger.debug(">>>>>>>>>>> xxl-job trigger start, jobId:{}", jobLog.getId());

        // 2、init trigger-param
        TriggerParam triggerParam = new TriggerParam();
        triggerParam.setJobId(jobInfo.getId());
        triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());
        triggerParam.setExecutorParams(jobInfo.getExecutorParam());
        triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
        triggerParam.setExecutorTimeout(jobInfo.getExecutorTimeout());
        triggerParam.setLogId(jobLog.getId());
        triggerParam.setLogDateTime(jobLog.getTriggerTime().getTime());
        triggerParam.setGlueType(jobInfo.getGlueType());
        triggerParam.setGlueSource(jobInfo.getGlueSource());
        triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
        triggerParam.setBroadcastIndex(index);
        triggerParam.setBroadcastTotal(total);

        // 3、init address  获取触发器执行地址
        String address = null;
        ReturnT<String> routeAddressResult = null;
        if (group.getRegistryList()!=null && !group.getRegistryList().isEmpty()) {  //如果是集群广播模式
            if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == executorRouteStrategyEnum) {
                if (index < group.getRegistryList().size()) {  //查询匹配地址执行
                    address = group.getRegistryList().get(index);
                } else { //超过size,则默认执行第一个.
                    address = group.getRegistryList().get(0);
                }
            } else {
                //根据设置的路由策略,执行路由器,获取返回结果   ,这里用到了策略模式
                routeAddressResult = executorRouteStrategyEnum.getRouter().route(triggerParam, group.getRegistryList());
                if (routeAddressResult.getCode() == ReturnT.SUCCESS_CODE) {
                    address = routeAddressResult.getContent();
                }
            }
        } else { //获取不到注册地址，返回失败值
            routeAddressResult = new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobconf_trigger_address_empty"));
        }

        // 4、trigger remote executor
        ReturnT<String> triggerResult = null;
        if (address != null) { //这里真正的执行触发器
            triggerResult = runExecutor(triggerParam, address);
        } else {//获取不到执行地址直接返回
            triggerResult = new ReturnT<String>(ReturnT.FAIL_CODE, null);
        }

        // 5、collection trigger info
        StringBuffer triggerMsgSb = new StringBuffer();
        triggerMsgSb.append(I18nUtil.getString("jobconf_trigger_type")).append("：").append(triggerType.getTitle());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_admin_adress")).append("：").append(IpUtil.getIp());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_exe_regtype")).append("：")
                .append( (group.getAddressType() == 0)?I18nUtil.getString("jobgroup_field_addressType_0"):I18nUtil.getString("jobgroup_field_addressType_1") );
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_exe_regaddress")).append("：").append(group.getRegistryList());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorRouteStrategy")).append("：").append(executorRouteStrategyEnum.getTitle());
        if (shardingParam != null) {
            triggerMsgSb.append("("+shardingParam+")");
        }
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorBlockStrategy")).append("：").append(blockStrategy.getTitle());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_timeout")).append("：").append(jobInfo.getExecutorTimeout());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorFailRetryCount")).append("：").append(finalFailRetryCount);

        triggerMsgSb.append("<br><br><span style=\"color:#00c0ef;\" > >>>>>>>>>>>"+ I18nUtil.getString("jobconf_trigger_run") +"<<<<<<<<<<< </span><br>")
                .append((routeAddressResult!=null&&routeAddressResult.getMsg()!=null)?routeAddressResult.getMsg()+"<br><br>":"").append(triggerResult.getMsg()!=null?triggerResult.getMsg():"");

        // 6、save log trigger-info  更改执行日志状态
        jobLog.setExecutorAddress(address);
        jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
        jobLog.setExecutorParam(jobInfo.getExecutorParam());
        jobLog.setExecutorShardingParam(shardingParam);
        jobLog.setExecutorFailRetryCount(finalFailRetryCount);
        //jobLog.setTriggerTime();
        jobLog.setTriggerCode(triggerResult.getCode());//设置执行触发器返回值
        jobLog.setTriggerMsg(triggerMsgSb.toString());//设置返回结果信息
        XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateTriggerInfo(jobLog);

        logger.debug(">>>>>>>>>>> xxl-job trigger end, jobId:{}", jobLog.getId());
    }
```
路由策略

>
>     FIRST(I18nUtil.getString("jobconf_route_first"), new ExecutorRouteFirst()),  //（第一个）固定选择第一个机器
>       LAST(I18nUtil.getString("jobconf_route_last"), new ExecutorRouteLast()),       //（最后一个）固定选择最后一个机器
>       ROUND(I18nUtil.getString("jobconf_route_round"), new ExecutorRouteRound()),     //（轮询）轮询
>       RANDOM(I18nUtil.getString("jobconf_route_random"), new ExecutorRouteRandom()),  //（随机）随机选择在线的机器；
>       CONSISTENT_HASH(I18nUtil.getString("jobconf_route_consistenthash"), new ExecutorRouteConsistentHash()),    //（一致性HASH）每个任务按照Hash算法固定选择某一台机器，且所有任务均匀散列在不同机器上。
>       LEAST_FREQUENTLY_USED(I18nUtil.getString("jobconf_route_lfu"), new ExecutorRouteLFU()),   //（最不经常使用）使用频率最低的机器优先被选举；
>       LEAST_RECENTLY_USED(I18nUtil.getString("jobconf_route_lru"), new ExecutorRouteLRU()),       //（最近最久未使用）最久未使用的机器优先被选举；
>       FAILOVER(I18nUtil.getString("jobconf_route_failover"), new ExecutorRouteFailover()),   //（故障转移）按照顺序依次进行心跳检测，第一个心跳检测成功的机器选定为目标执行器并发起调度；
>       BUSYOVER(I18nUtil.getString("jobconf_route_busyover"), new ExecutorRouteBusyover()),  //（忙碌转移）按照顺序依次进行空闲检测，第一个空闲检测成功的机器选定为目标执行器并发起调度；
>       SHARDING_BROADCAST(I18nUtil.getString("jobconf_route_shard"), null);        //(分片广播)广播触发对应集群中所有机器执行一次任务，同时系统自动传递分片参数；可根据分片参数开发分片任务；

这里默认固定选择第一个机器

```java
    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList){
        return new ReturnT<String>(addressList.get(0));
    }
```

执行触发器
拼接http+token地址


```java
    public static ReturnT<String> runExecutor(TriggerParam triggerParam, String address){
        ReturnT<String> runResult = null;
        try {
            ExecutorBiz executorBiz = XxlJobScheduler.getExecutorBiz(address);//获取业务执行器地址,就执行器地址后面拼接token
            runResult = executorBiz.run(triggerParam);
        } catch (Exception e) {
            logger.error(">>>>>>>>>>> xxl-job trigger error, please check if the executor[{}] is running.", address, e);
            runResult = new ReturnT<String>(ReturnT.FAIL_CODE, ThrowableUtil.toString(e));
        }
        //返回结果设置msg
        StringBuffer runResultSB = new StringBuffer(I18nUtil.getString("jobconf_trigger_run") + "：");
        runResultSB.append("<br>address：").append(address);
        runResultSB.append("<br>code：").append(runResult.getCode());
        runResultSB.append("<br>msg：").append(runResult.getMsg());

        runResult.setMsg(runResultSB.toString());
        return runResult;
    }
```
访问执行
```java
  @Override
    public ReturnT<String> run(TriggerParam triggerParam) {
        return XxlJobRemotingUtil.postBody(addressUrl + "run", accessToken, timeout, triggerParam, String.class);
    }
```
## 4）2：客户端

![在这里插入图片描述](https://img-blog.csdnimg.cn/20210426090820752.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)


```java
   @Override
        protected void channelRead0(final ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {

            // request parse
            //final byte[] requestBytes = ByteBufUtil.getBytes(msg.content());    // byteBuf.toString(io.netty.util.CharsetUtil.UTF_8);
            String requestData = msg.content().toString(CharsetUtil.UTF_8);//解析请求数据
            String uri = msg.uri();//获取uri,后面通过uri来处理不同的请求
            HttpMethod httpMethod = msg.method();//获取请求方式,Post/Get
            boolean keepAlive = HttpUtil.isKeepAlive(msg);//保持长连接
            String accessTokenReq = msg.headers().get(XxlJobRemotingUtil.XXL_JOB_ACCESS_TOKEN);

            // invoke
            bizThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    // do invoke
                    Object responseObj = process(httpMethod, uri, requestData, accessTokenReq);

                    // to json  响应结果转json
                    String responseJson = GsonTool.toJson(responseObj);

                    // write response
                    writeResponse(ctx, keepAlive, responseJson);
                }
            });
        }

```

执行触发器

```java
   private Object process(HttpMethod httpMethod, String uri, String requestData, String accessTokenReq) {

            // valid 不是POST直接返回异常
            if (HttpMethod.POST != httpMethod) {
                return new ReturnT<String>(ReturnT.FAIL_CODE, "invalid request, HttpMethod not support.");
            }
            if (uri==null || uri.trim().length()==0) { //校验uri
                return new ReturnT<String>(ReturnT.FAIL_CODE, "invalid request, uri-mapping empty.");
            }//校验token是否正确
            if (accessToken!=null
                    && accessToken.trim().length()>0
                    && !accessToken.equals(accessTokenReq)) {
                return new ReturnT<String>(ReturnT.FAIL_CODE, "The access token is wrong.");
            }

            // services mapping
            try {
                if ("/beat".equals(uri)) {
                    return executorBiz.beat();
                } else if ("/idleBeat".equals(uri)) {
                    IdleBeatParam idleBeatParam = GsonTool.fromJson(requestData, IdleBeatParam.class);
                    return executorBiz.idleBeat(idleBeatParam);
                } else if ("/run".equals(uri)) { //执行触发器
                    TriggerParam triggerParam = GsonTool.fromJson(requestData, TriggerParam.class);//请求参数解析成TriggerParam
                    return executorBiz.run(triggerParam);
                } else if ("/kill".equals(uri)) {
                    KillParam killParam = GsonTool.fromJson(requestData, KillParam.class);
                    return executorBiz.kill(killParam);
                } else if ("/log".equals(uri)) {
                    LogParam logParam = GsonTool.fromJson(requestData, LogParam.class);
                    return executorBiz.log(logParam);
                } else {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "invalid request, uri-mapping("+ uri +") not found.");
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return new ReturnT<String>(ReturnT.FAIL_CODE, "request error:" + ThrowableUtil.toString(e));
            }
        }

```

 1. 绑定作业到具体线程，启动线程
 2. 任务丢入线程处理

```java
 @Override
    public ReturnT<String> run(TriggerParam triggerParam) {
        // load old：jobHandler + jobThread
        //获取job绑定线程
        JobThread jobThread = XxlJobExecutor.loadJobThread(triggerParam.getJobId());
        //获取作业处理器
        IJobHandler jobHandler = jobThread!=null?jobThread.getHandler():null;
        String removeOldReason = null;

        // valid：jobHandler + jobThread
        //获取任务运行模式
        GlueTypeEnum glueTypeEnum = GlueTypeEnum.match(triggerParam.getGlueType());
        //任务以JobHandler方式维护在执行器端；需要结合 "JobHandler" 属性匹配执行器中任务；
        if (GlueTypeEnum.BEAN == glueTypeEnum) {
            // new jobhandler  获取job处理器,也就是方法声明
            IJobHandler newJobHandler = XxlJobExecutor.loadJobHandler(triggerParam.getExecutorHandler());

            // valid old jobThread 如果job处理器不一样,则kill旧处理器绑定的线程
            if (jobThread!=null && jobHandler != newJobHandler) {
                // change handler, need kill old thread
                removeOldReason = "change jobhandler or glue type, and terminate the old job thread.";

                jobThread = null;
                jobHandler = null;
            }

            // valid handler
            //执行到这儿,要么新旧处理器不一致,要么没有绑定过任何线程
            if (jobHandler == null) {
                jobHandler = newJobHandler;
                //没找到处理器,直接返回异常
                if (jobHandler == null) {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "job handler [" + triggerParam.getExecutorHandler() + "] not found.");
                }
            }

        } else if (GlueTypeEnum.GLUE_GROOVY == glueTypeEnum) {

            // valid old jobThread
            if (jobThread != null &&
                    !(jobThread.getHandler() instanceof GlueJobHandler
                        && ((GlueJobHandler) jobThread.getHandler()).getGlueUpdatetime()==triggerParam.getGlueUpdatetime() )) {
                // change handler or gluesource updated, need kill old thread
                removeOldReason = "change job source or glue type, and terminate the old job thread.";

                jobThread = null;
                jobHandler = null;
            }

            // valid handler
            if (jobHandler == null) {
                try {
                    IJobHandler originJobHandler = GlueFactory.getInstance().loadNewInstance(triggerParam.getGlueSource());
                    jobHandler = new GlueJobHandler(originJobHandler, triggerParam.getGlueUpdatetime());
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    return new ReturnT<String>(ReturnT.FAIL_CODE, e.getMessage());
                }
            }
        } else if (glueTypeEnum!=null && glueTypeEnum.isScript()) {

            // valid old jobThread
            if (jobThread != null &&
                    !(jobThread.getHandler() instanceof ScriptJobHandler
                            && ((ScriptJobHandler) jobThread.getHandler()).getGlueUpdatetime()==triggerParam.getGlueUpdatetime() )) {
                // change script or gluesource updated, need kill old thread
                removeOldReason = "change job source or glue type, and terminate the old job thread.";

                jobThread = null;
                jobHandler = null;
            }

            // valid handler
            if (jobHandler == null) {
                jobHandler = new ScriptJobHandler(triggerParam.getJobId(), triggerParam.getGlueUpdatetime(), triggerParam.getGlueSource(), GlueTypeEnum.match(triggerParam.getGlueType()));
            }
        } else {
            return new ReturnT<String>(ReturnT.FAIL_CODE, "glueType[" + triggerParam.getGlueType() + "] is not valid.");
        }

        // executor block strategy
        //获取任务阻塞策略
        if (jobThread != null) {
            ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(triggerParam.getExecutorBlockStrategy(), null);
            if (ExecutorBlockStrategyEnum.DISCARD_LATER == blockStrategy) {
                // discard when running
                if (jobThread.isRunningOrHasQueue()) {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, "block strategy effect："+ExecutorBlockStrategyEnum.DISCARD_LATER.getTitle());
                }
            } else if (ExecutorBlockStrategyEnum.COVER_EARLY == blockStrategy) {
                // kill running jobThread
                if (jobThread.isRunningOrHasQueue()) {
                    removeOldReason = "block strategy effect：" + ExecutorBlockStrategyEnum.COVER_EARLY.getTitle();

                    jobThread = null;
                }
            } else {
                // just queue trigger
            }
        }

        // replace thread (new or exists invalid)
        //作业没绑定过线程,则绑定作业到具体线程
        if (jobThread == null) {
            jobThread = XxlJobExecutor.registJobThread(triggerParam.getJobId(), jobHandler, removeOldReason);
        }

        // push data to queue  任务丢入线程处理
        ReturnT<String> pushResult = jobThread.pushTriggerQueue(triggerParam);
        return pushResult;
    }
```
绑定作业到具体线程，启动线程

```java
    public static JobThread registJobThread(int jobId, IJobHandler handler, String removeOldReason){
        JobThread newJobThread = new JobThread(jobId, handler);//启动新线程处理工作任务
        newJobThread.start();
        logger.info(">>>>>>>>>>> xxl-job regist JobThread success, jobId:{}, handler:{}", new Object[]{jobId, handler});
        //存储jobId与绑定工作的线程
        JobThread oldJobThread = jobThreadRepository.put(jobId, newJobThread);	// putIfAbsent | oh my god, map's put method return the old value!!!
        if (oldJobThread != null) {//中断并删除旧线程
            oldJobThread.toStop(removeOldReason);
            oldJobThread.interrupt();
        }

        return newJobThread;
    }
```
任务丢入线程处理

```java
	public ReturnT<String> pushTriggerQueue(TriggerParam triggerParam) {
		// avoid repeat  若包含，则说明重复执行
		if (triggerLogIdSet.contains(triggerParam.getLogId())) {
			logger.info(">>>>>>>>>>> repeate trigger job, logId:{}", triggerParam.getLogId());
			return new ReturnT<String>(ReturnT.FAIL_CODE, "repeate trigger job, logId:" + triggerParam.getLogId());
		}

		triggerLogIdSet.add(triggerParam.getLogId());
		triggerQueue.add(triggerParam);
        return ReturnT.SUCCESS;
	}
```

具体执行触发器逻辑

```java
  @Override
	public void run() {

    	// init
    	try {
			handler.init();//执行初始化任务
		} catch (Throwable e) {
    		logger.error(e.getMessage(), e);
		}

		// execute
		while(!toStop){
			running = false;
			idleTimes++;//统计空闲执行次数

            TriggerParam triggerParam = null;
            try {
				// to check toStop signal, we need cycle, so wo cannot use queue.take(), instand of poll(timeout)
				triggerParam = triggerQueue.poll(3L, TimeUnit.SECONDS); //获取触发器任务
				if (triggerParam!=null) {
					running = true;
					idleTimes = 0;//空闲次数重置
					triggerLogIdSet.remove(triggerParam.getLogId());  //删除logId主要用来判断是否重复执行

					// log filename, like "logPath/yyyy-MM-dd/9999.log"  写入log文件
					String logFileName = XxlJobFileAppender.makeLogFileName(new Date(triggerParam.getLogDateTime()), triggerParam.getLogId());
					XxlJobContext xxlJobContext = new XxlJobContext(
							triggerParam.getJobId(),
							triggerParam.getExecutorParams(),
							logFileName,
							triggerParam.getBroadcastIndex(),
							triggerParam.getBroadcastTotal());

					// init job context
					XxlJobContext.setXxlJobContext(xxlJobContext);

					// execute
					XxlJobHelper.log("<br>----------- xxl-job job execute start -----------<br>----------- Param:" + xxlJobContext.getJobParam());
					//设置了超时就异步线程处理
					if (triggerParam.getExecutorTimeout() > 0) {
						// limit timeout
						Thread futureThread = null;
						try {
							FutureTask<Boolean> futureTask = new FutureTask<Boolean>(new Callable<Boolean>() {
								@Override
								public Boolean call() throws Exception {

									// init job context
									XxlJobContext.setXxlJobContext(xxlJobContext);

									handler.execute();
									return true;
								}
							});
							futureThread = new Thread(futureTask);
							futureThread.start();
							//异步线程处理并获取返回值
							Boolean tempResult = futureTask.get(triggerParam.getExecutorTimeout(), TimeUnit.SECONDS);
						} catch (TimeoutException e) {

							XxlJobHelper.log("<br>----------- xxl-job job execute timeout");
							XxlJobHelper.log(e);

							// handle result
							XxlJobHelper.handleTimeout("job execute timeout ");
						} finally {
							futureThread.interrupt();
						}
					} else {
						// just execute  没设置超时时间，则立刻执行触发器
						handler.execute();
					}

					// valid execute handle data
					if (XxlJobContext.getXxlJobContext().getHandleCode() <= 0) {
						XxlJobHelper.handleFail("job handle result lost.");
					} else {
						String tempHandleMsg = XxlJobContext.getXxlJobContext().getHandleMsg();
						tempHandleMsg = (tempHandleMsg!=null&&tempHandleMsg.length()>50000)
								?tempHandleMsg.substring(0, 50000).concat("...")
								:tempHandleMsg;
						XxlJobContext.getXxlJobContext().setHandleMsg(tempHandleMsg);
					}
					XxlJobHelper.log("<br>----------- xxl-job job execute end(finish) -----------<br>----------- Result: handleCode="
							+ XxlJobContext.getXxlJobContext().getHandleCode()
							+ ", handleMsg = "
							+ XxlJobContext.getXxlJobContext().getHandleMsg()
					);

				} else {
					if (idleTimes > 30) { //空闲执行次数超过30次,且队列没任务,则删除并终止线程
						if(triggerQueue.size() == 0) {	// avoid concurrent trigger causes jobId-lost
							XxlJobExecutor.removeJobThread(jobId, "excutor idel times over limit.");
						}
					}
				}
			} catch (Throwable e) {
				if (toStop) {
					XxlJobHelper.log("<br>----------- JobThread toStop, stopReason:" + stopReason);
				}

				// handle result
				StringWriter stringWriter = new StringWriter();
				e.printStackTrace(new PrintWriter(stringWriter));
				String errorMsg = stringWriter.toString();

				XxlJobHelper.handleFail(errorMsg);

				XxlJobHelper.log("<br>----------- JobThread Exception:" + errorMsg + "<br>----------- xxl-job job execute end(error) -----------");
			} finally {
                if(triggerParam != null) {
                    // callback handler info
                    if (!toStop) {
                        // commonm  加入回调队列
                        TriggerCallbackThread.pushCallBack(new HandleCallbackParam(
                        		triggerParam.getLogId(),
								triggerParam.getLogDateTime(),
								XxlJobContext.getXxlJobContext().getHandleCode(),
								XxlJobContext.getXxlJobContext().getHandleMsg() )
						);
                    } else {
                        // is killed
                        TriggerCallbackThread.pushCallBack(new HandleCallbackParam(
                        		triggerParam.getLogId(),
								triggerParam.getLogDateTime(),
								XxlJobContext.HANDLE_COCE_FAIL,
								stopReason + " [job running, killed]" )
						);
                    }
                }
            }
        }

		// callback trigger request in queue
		while(triggerQueue !=null && triggerQueue.size()>0){
			TriggerParam triggerParam = triggerQueue.poll();
			if (triggerParam!=null) {
				// is killed 加入回调队列
				TriggerCallbackThread.pushCallBack(new HandleCallbackParam(
						triggerParam.getLogId(),
						triggerParam.getLogDateTime(),
						XxlJobContext.HANDLE_COCE_FAIL,
						stopReason + " [job not executed, in the job queue, killed.]")
				);
			}
		}

		// destroy
		try { //销毁,执行调度器设置的销毁方法
			handler.destroy();
		} catch (Throwable e) {
			logger.error(e.getMessage(), e);
		}

		logger.info(">>>>>>>>>>> xxl-job JobThread stoped, hashCode:{}", Thread.currentThread());
	}
```

## 4）3：客户端回调服务端
当服务端调用客户端执行后，会直接返回

<font color="red" size="3">com.xxl.job.core.biz.impl.ExecutorBizImpl#run</font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/2021042021130447.png)


```java
	public ReturnT<String> pushTriggerQueue(TriggerParam triggerParam) {
		// avoid repeat  若包含，则说明重复执行
		if (triggerLogIdSet.contains(triggerParam.getLogId())) {
			logger.info(">>>>>>>>>>> repeate trigger job, logId:{}", triggerParam.getLogId());
			return new ReturnT<String>(ReturnT.FAIL_CODE, "repeate trigger job, logId:" + triggerParam.getLogId());
		}

		triggerLogIdSet.add(triggerParam.getLogId());
		triggerQueue.add(triggerParam);
        return ReturnT.SUCCESS;
	}
```
服务端接收响应
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210420211427949.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
随后封装成这样返回![在这里插入图片描述](https://img-blog.csdnimg.cn/20210420211444903.png)
这里xxl_job_log表trigger_code，200标识调用客户端成功,500标识调用失败(获取不到客户端地址)
当客户端执行完成后，会将处理回调参数任务加入触发器回调处理线程

<font color="red" size="3">com.xxl.job.core.thread.JobThread#run</font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210420213549598.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
<font color="red" size="3">com.xxl.job.core.thread.TriggerCallbackThread#start</font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210420213639560.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
调用服务端回调
```java
    private void doCallback(List<HandleCallbackParam> callbackParamList){
        boolean callbackRet = false;
        // callback, will retry if error 获取admin地址
        for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
            try { //回调admin,返回执行结果
                ReturnT<String> callbackResult = adminBiz.callback(callbackParamList);
                if (callbackResult!=null && ReturnT.SUCCESS_CODE == callbackResult.getCode()) {
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback finish.");
                    callbackRet = true;
                    break;
                } else {
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback fail, callbackResult:" + callbackResult);
                }
            } catch (Exception e) {
                callbackLog(callbackParamList, "<br>----------- xxl-job job callback error, errorMsg:" + e.getMessage());
            }
        }
        if (!callbackRet) {
            appendFailCallbackFile(callbackParamList);
        }
    }
```

```java
    public ReturnT<String> callback(List<HandleCallbackParam> callbackParamList) {
        return XxlJobRemotingUtil.postBody(addressUrl+"api/callback", accessToken, timeout, callbackParamList, String.class);
    }
```
<font color="red" size="3">com.xxl.job.admin.controller.JobApiController#api</font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210420214426466.png)

```java
	public ReturnT<String> callback(List<HandleCallbackParam> callbackParamList) {

		callbackThreadPool.execute(new Runnable() {
			@Override
			public void run() {
				for (HandleCallbackParam handleCallbackParam: callbackParamList) {
					ReturnT<String> callbackResult = callback(handleCallbackParam);
					logger.debug(">>>>>>>>> JobApiController.callback {}, handleCallbackParam={}, callbackResult={}",
							(callbackResult.getCode()== ReturnT.SUCCESS_CODE?"success":"fail"), handleCallbackParam, callbackResult);
				}
			}
		});

		return ReturnT.SUCCESS;
	}
```

```java
	private ReturnT<String> callback(HandleCallbackParam handleCallbackParam) {
		// valid log item  加载log
		XxlJobLog log = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().load(handleCallbackParam.getLogId());
		if (log == null) {  //日志没找到返回异常
			return new ReturnT<String>(ReturnT.FAIL_CODE, "log item not found.");
		}
		if (log.getHandleCode() > 0) { //说明重复执行
			return new ReturnT<String>(ReturnT.FAIL_CODE, "log repeate callback.");     // avoid repeat callback, trigger child job etc
		}

		// handle msg
		StringBuffer handleMsg = new StringBuffer();
		if (log.getHandleMsg()!=null) {
			handleMsg.append(log.getHandleMsg()).append("<br>");
		}
		if (handleCallbackParam.getHandleMsg() != null) {
			handleMsg.append(handleCallbackParam.getHandleMsg());
		}

		// success, save log  更改
		log.setHandleTime(new Date());
		log.setHandleCode(handleCallbackParam.getHandleCode()); //更改处理状态,200正常,500错误
		log.setHandleMsg(handleMsg.toString());
		XxlJobCompleter.updateHandleInfoAndFinish(log);

		return ReturnT.SUCCESS;
	}
```

### 4）3) 1：执行子任务

```java
    public static int updateHandleInfoAndFinish(XxlJobLog xxlJobLog) {

        // finish 若父任务正常结束，则终止子任务,以及设置Childmsg
        finishJob(xxlJobLog);

        // text最大64kb 避免长度过长 截断超过长度限制字符
        if (xxlJobLog.getHandleMsg().length() > 15000) {
            xxlJobLog.setHandleMsg( xxlJobLog.getHandleMsg().substring(0, 15000) );
        }

        // fresh handle 更新joblog
        return XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateHandleInfo(xxlJobLog);
    }
```

```java
private static void finishJob(XxlJobLog xxlJobLog){

        // 1、handle success, to trigger child job
        String triggerChildMsg = null;
        if (XxlJobContext.HANDLE_COCE_SUCCESS == xxlJobLog.getHandleCode()) {
            XxlJobInfo xxlJobInfo = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().loadById(xxlJobLog.getJobId());
            if (xxlJobInfo!=null && xxlJobInfo.getChildJobId()!=null && xxlJobInfo.getChildJobId().trim().length()>0) {
                triggerChildMsg = "<br><br><span style=\"color:#00c0ef;\" > >>>>>>>>>>>"+ I18nUtil.getString("jobconf_trigger_child_run") +"<<<<<<<<<<< </span><br>";
                //获取子任务id
                String[] childJobIds = xxlJobInfo.getChildJobId().split(",");
                for (int i = 0; i < childJobIds.length; i++) {
                    int childJobId = (childJobIds[i]!=null && childJobIds[i].trim().length()>0 && isNumeric(childJobIds[i]))?Integer.valueOf(childJobIds[i]):-1;
                    if (childJobId > 0) {

                        //触发子任务
                        JobTriggerPoolHelper.trigger(childJobId, TriggerTypeEnum.PARENT, -1, null, null, null);
                        ReturnT<String> triggerChildResult = ReturnT.SUCCESS;

                        // add msg
                        triggerChildMsg += MessageFormat.format(I18nUtil.getString("jobconf_callback_child_msg1"),
                                (i+1),
                                childJobIds.length,
                                childJobIds[i],
                                (triggerChildResult.getCode()==ReturnT.SUCCESS_CODE?I18nUtil.getString("system_success"):I18nUtil.getString("system_fail")),
                                triggerChildResult.getMsg());
                    } else {
                        triggerChildMsg += MessageFormat.format(I18nUtil.getString("jobconf_callback_child_msg2"),
                                (i+1),
                                childJobIds.length,
                                childJobIds[i]);
                    }
                }

            }
        }

        if (triggerChildMsg != null) {
            xxlJobLog.setHandleMsg( xxlJobLog.getHandleMsg() + triggerChildMsg );
        }

        // 2、fix_delay trigger next
        // on the way

    }
```
### 4）3) 2：针对超时任务设置返回值
<font color="red" size="3"> com.xxl.job.admin.core.thread.JobCompleteHelper#start </font>
```java
// for monitor
		monitorThread = new Thread(new Runnable() {

			@Override
			public void run() {

				// wait for JobTriggerPoolHelper-init
				try {
					TimeUnit.MILLISECONDS.sleep(50);
				} catch (InterruptedException e) {
					if (!toStop) {
						logger.error(e.getMessage(), e);
					}
				}

				// monitor
				while (!toStop) {
					try {
						// 任务结果丢失处理：调度记录停留在 "运行中" 状态超过10min，且对应执行器心跳注册失败不在线，则将本地调度主动标记失败；
						Date losedTime = DateUtil.addMinutes(new Date(), -10);
						//调度日志表： 用于保存XXL-JOB任务调度的历史信息，如调度结果、执行结果、调度入参、调度机器和执行器等等；
						List<Long> losedJobIds  = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().findLostJobIds(losedTime);

						if (losedJobIds!=null && losedJobIds.size()>0) {
							for (Long logId: losedJobIds) {

								XxlJobLog jobLog = new XxlJobLog();
								jobLog.setId(logId);

								jobLog.setHandleTime(new Date());
								jobLog.setHandleCode(ReturnT.FAIL_CODE);
								jobLog.setHandleMsg( I18nUtil.getString("joblog_lost_fail") );

								//更改处理状态
								XxlJobCompleter.updateHandleInfoAndFinish(jobLog);
							}

						}
					} catch (Exception e) {
						if (!toStop) {
							logger.error(">>>>>>>>>>> xxl-job, job fail monitor thread error:{}", e);
						}
					}

                    try {
                        TimeUnit.SECONDS.sleep(60);
                    } catch (Exception e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                }

				logger.info(">>>>>>>>>>> xxl-job, JobLosedMonitorHelper stop");

			}
		});
		monitorThread.setDaemon(true);
		monitorThread.setName("xxl-job, admin JobLosedMonitorHelper");
		monitorThread.start();
```

# 5) 被动触发 todo

# 6) 设计模式

## 6) 1：策略模式

<font color="red" size="3">com.xxl.job.admin.core.trigger.XxlJobTrigger#processTrigger</font>

![在这里插入图片描述](https://img-blog.csdnimg.cn/20210419212745721.png)
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210419212755194.png)
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210419212811715.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

> 路由策略：当执行器集群部署时，提供丰富的路由策略，包括；
>    FIRST（第一个）：固定选择第一个机器；
>    LAST（最后一个）：固定选择最后一个机器；
>    ROUND（轮询）：；
>    RANDOM（随机）：随机选择在线的机器；
>    CONSISTENT_HASH（一致性HASH）：每个任务按照Hash算法固定选择某一台机器，且所有任务均匀散列在不同机器上。
>    LEAST_FREQUENTLY_USED（最不经常使用）：使用频率最低的机器优先被选举；
>    LEAST_RECENTLY_USED（最近最久未使用）：最久未使用的机器优先被选举；
>    FAILOVER（故障转移）：按照顺序依次进行心跳检测，第一个心跳检测成功的机器选定为目标执行器并发起调度；
>    BUSYOVER（忙碌转移）：按照顺序依次进行空闲检测，第一个空闲检测成功的机器选定为目标执行器并发起调度；
>    SHARDING_BROADCAST(分片广播)：广播触发对应集群中所有机器执行一次任务，同时系统自动传递分片参数；可根据分片参数开发分片任务；



```java
public class ExecutorRouteFirst extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList){
        return new ReturnT<String>(addressList.get(0));
    }

}
```

## 6) 2：单例

```java
    private static TriggerCallbackThread instance = new TriggerCallbackThread();
    public static TriggerCallbackThread getInstance(){
        return instance;
    }
```


# 7) 亮瞎眼的设计
## 7) 1：路由策略
> 路由策略：当执行器集群部署时，提供丰富的路由策略，包括；
>    FIRST（第一个）：固定选择第一个机器；
>    LAST（最后一个）：固定选择最后一个机器；
>    ROUND（轮询）：；
>    RANDOM（随机）：随机选择在线的机器；
>    CONSISTENT_HASH（一致性HASH）：每个任务按照Hash算法固定选择某一台机器，且所有任务均匀散列在不同机器上。
>    LEAST_FREQUENTLY_USED（最不经常使用）：使用频率最低的机器优先被选举；
>    LEAST_RECENTLY_USED（最近最久未使用）：最久未使用的机器优先被选举；
>    FAILOVER（故障转移）：按照顺序依次进行心跳检测，第一个心跳检测成功的机器选定为目标执行器并发起调度；
>    BUSYOVER（忙碌转移）：按照顺序依次进行空闲检测，第一个空闲检测成功的机器选定为目标执行器并发起调度；
>    SHARDING_BROADCAST(分片广播)：广播触发对应集群中所有机器执行一次任务，同时系统自动传递分片参数；可根据分片参数开发分片任务；
>   - 子任务：每个任务都拥有一个唯一的任务ID(任务ID可以从任务列表获取)，当本任务执行结束并且执行成功时，将会触发子任务ID所对应的任务的一次主动调度。

```java
public enum ExecutorRouteStrategyEnum {

    FIRST(I18nUtil.getString("jobconf_route_first"), new ExecutorRouteFirst()),  //（第一个）固定选择第一个机器
    LAST(I18nUtil.getString("jobconf_route_last"), new ExecutorRouteLast()),       //（最后一个）固定选择最后一个机器
    ROUND(I18nUtil.getString("jobconf_route_round"), new ExecutorRouteRound()),     //（轮询）轮询
    RANDOM(I18nUtil.getString("jobconf_route_random"), new ExecutorRouteRandom()),  //（随机）随机选择在线的机器；
    CONSISTENT_HASH(I18nUtil.getString("jobconf_route_consistenthash"), new ExecutorRouteConsistentHash()),    //（一致性HASH）每个任务按照Hash算法固定选择某一台机器，且所有任务均匀散列在不同机器上。
    LEAST_FREQUENTLY_USED(I18nUtil.getString("jobconf_route_lfu"), new ExecutorRouteLFU()),   //（最不经常使用）使用频率最低的机器优先被选举；
    LEAST_RECENTLY_USED(I18nUtil.getString("jobconf_route_lru"), new ExecutorRouteLRU()),       //（最近最久未使用）最久未使用的机器优先被选举；
    FAILOVER(I18nUtil.getString("jobconf_route_failover"), new ExecutorRouteFailover()),   //（故障转移）按照顺序依次进行心跳检测，第一个心跳检测成功的机器选定为目标执行器并发起调度；
    BUSYOVER(I18nUtil.getString("jobconf_route_busyover"), new ExecutorRouteBusyover()),  //（忙碌转移）按照顺序依次进行空闲检测，第一个空闲检测成功的机器选定为目标执行器并发起调度；
    SHARDING_BROADCAST(I18nUtil.getString("jobconf_route_shard"), null);        //(分片广播)广播触发对应集群中所有机器执行一次任务，同时系统自动传递分片参数；可根据分片参数开发分片任务；
}
```

### 7) 1）1： FIRST  &  LAST

```java
public class ExecutorRouteFirst extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList){
        return new ReturnT<String>(addressList.get(0));
    }

}

```

```java
public class ExecutorRouteLast extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        return new ReturnT<String>(addressList.get(addressList.size()-1));
    }

}
```
### 7) 1）2： 轮询 & 随机
```java
public class ExecutorRouteRound extends ExecutorRouter {

    private static ConcurrentMap<Integer, AtomicInteger> routeCountEachJob = new ConcurrentHashMap<>();
    private static long CACHE_VALID_TIME = 0;

    private static int count(int jobId) {
        // cache clear
        //当超过缓存时间，则清除缓存,这里存储内存中
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            routeCountEachJob.clear();
            //缓存有效时间一天
            CACHE_VALID_TIME = System.currentTimeMillis() + 1000*60*60*24;
        }

        AtomicInteger count = routeCountEachJob.get(jobId);
        if (count == null || count.get() > 1000000) {
            // 初始化时主动Random一次，缓解首次压力
            // 将首次寻址分担到每个addressList上
            count = new AtomicInteger(new Random().nextInt(100));
        } else {
            // count++
            count.addAndGet(1);
        }
        routeCountEachJob.put(jobId, count);
        return count.get();
    }

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        String address = addressList.get(count(triggerParam.getJobId())%addressList.size());
        return new ReturnT<String>(address);
    }

}
```

```java
public class ExecutorRouteRandom extends ExecutorRouter {

    private static Random localRandom = new Random();

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        String address = addressList.get(localRandom.nextInt(addressList.size()));
        return new ReturnT<String>(address);
    }

}
```
### 7) 1）3： 一致性HASH
每个任务按照Hash算法固定选择某一台机器，且所有任务均匀散列在不同机器上。
### 7) 1）4： LFU & LRU

```java
/**
 * 单个JOB对应的每个执行器，使用频率最低的优先被选举
 *      a(*)、LFU(Least Frequently Used)：最不经常使用，频率/次数
 *      b、LRU(Least Recently Used)：最近最久未使用，时间
 *
 * Created by xuxueli on 17/3/10.
 */
public class ExecutorRouteLFU extends ExecutorRouter {

    private static ConcurrentMap<Integer, HashMap<String, Integer>> jobLfuMap = new ConcurrentHashMap<Integer, HashMap<String, Integer>>();
    private static long CACHE_VALID_TIME = 0;

    public String route(int jobId, List<String> addressList) {

        // cache clear  超过一天，则清除缓存
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            jobLfuMap.clear();
            CACHE_VALID_TIME = System.currentTimeMillis() + 1000*60*60*24;
        }

        // lfu item init
        // Key排序可以用TreeMap+构造入参Compare；Value排序暂时只能通过ArrayList；
        HashMap<String, Integer> lfuItemMap = jobLfuMap.get(jobId);
        if (lfuItemMap == null) {
            //说明第一次进来,则新建
            lfuItemMap = new HashMap<String, Integer>();
            // 避免重复覆盖
            jobLfuMap.putIfAbsent(jobId, lfuItemMap);
        }

        // put new
        //存储新的地址,同时随机设置访问次数,缓解第一次访问第一个元素的压力
        for (String address: addressList) {
            if (!lfuItemMap.containsKey(address) || lfuItemMap.get(address) >1000000 ) {
                // 初始化时主动Random一次，缓解首次压力
                lfuItemMap.put(address, new Random().nextInt(addressList.size()));
            }
        }
        // remove old
        //删除不存在的address
        List<String> delKeys = new ArrayList<>();
        for (String existKey: lfuItemMap.keySet()) {
            if (!addressList.contains(existKey)) {
                delKeys.add(existKey);
            }
        }
        if (delKeys.size() > 0) {
            for (String delKey: delKeys) {
                lfuItemMap.remove(delKey);
            }
        }

        // load least userd count address
        //排序次数,降序排序
        List<Map.Entry<String, Integer>> lfuItemList = new ArrayList<Map.Entry<String, Integer>>(lfuItemMap.entrySet());
        Collections.sort(lfuItemList, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                return o1.getValue().compareTo(o2.getValue());
            }
        });

        //获取最少访问次数的元素
        Map.Entry<String, Integer> addressItem = lfuItemList.get(0);
        String minAddress = addressItem.getKey();
        //访问次数+1
        addressItem.setValue(addressItem.getValue() + 1);

        return addressItem.getKey();
    }

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        String address = route(triggerParam.getJobId(), addressList);
        return new ReturnT<String>(address);
    }

}
```

```java
package com.xxl.job.admin.core.route.strategy;

import com.xxl.job.admin.core.route.ExecutorRouter;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.biz.model.TriggerParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单个JOB对应的每个执行器，最久为使用的优先被选举
 *      a、LFU(Least Frequently Used)：最不经常使用，频率/次数
 *      b(*)、LRU(Least Recently Used)：最近最久未使用，时间
 *
 * Created by xuxueli on 17/3/10.
 */
public class ExecutorRouteLRU extends ExecutorRouter {

    private static ConcurrentMap<Integer, LinkedHashMap<String, String>> jobLRUMap = new ConcurrentHashMap<Integer, LinkedHashMap<String, String>>();
    private static long CACHE_VALID_TIME = 0;

    public String route(int jobId, List<String> addressList) {

        // cache clear
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            jobLRUMap.clear();
            CACHE_VALID_TIME = System.currentTimeMillis() + 1000*60*60*24;
        }

        // init lru
        LinkedHashMap<String, String> lruItem = jobLRUMap.get(jobId);
        if (lruItem == null) {
            /**
             * LinkedHashMap
             *      a、accessOrder：true=访问顺序排序（get/put时排序）；false=插入顺序排期；
             *      b、removeEldestEntry：新增元素时将会调用，返回true时会删除最老元素；可封装LinkedHashMap并重写该方法，比如定义最大容量，超出是返回true即可实现固定长度的LRU算法；
             */
            lruItem = new LinkedHashMap<String, String>(16, 0.75f, true);
            jobLRUMap.putIfAbsent(jobId, lruItem);
        }

        // put new 添加新地址
        for (String address: addressList) {
            if (!lruItem.containsKey(address)) {
                lruItem.put(address, address);
            }
        }
        //删除旧地址
        // remove old
        List<String> delKeys = new ArrayList<>();
        for (String existKey: lruItem.keySet()) {
            if (!addressList.contains(existKey)) {
                delKeys.add(existKey);
            }
        }
        if (delKeys.size() > 0) {
            for (String delKey: delKeys) {
                lruItem.remove(delKey);
            }
        }

        // load 获取第一个元素返回
        String eldestKey = lruItem.entrySet().iterator().next().getKey();
        //
        String eldestValue = lruItem.get(eldestKey);
        return eldestValue;
    }

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        String address = route(triggerParam.getJobId(), addressList);
        return new ReturnT<String>(address);
    }

}

```

### 7) 1）5： 故障转移
按照顺序依次进行心跳检测，第一个心跳检测成功的机器选定为目标执行器并发起调度；

```java
public class ExecutorRouteFailover extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {

        StringBuffer beatResultSB = new StringBuffer();
        //遍历配置的每个地址
        for (String address : addressList) {
            // beat
            ReturnT<String> beatResult = null;
            try {//获取executor-client
                ExecutorBiz executorBiz = XxlJobScheduler.getExecutorBiz(address);
                //检测心跳
                beatResult = executorBiz.beat();
            } catch (Exception e) {
                //检测异常直接封装返回
                logger.error(e.getMessage(), e);
                beatResult = new ReturnT<String>(ReturnT.FAIL_CODE, ""+e );
            }
            beatResultSB.append( (beatResultSB.length()>0)?"<br><br>":"")
                    .append(I18nUtil.getString("jobconf_beat") + "：")
                    .append("<br>address：").append(address)
                    .append("<br>code：").append(beatResult.getCode())
                    .append("<br>msg：").append(beatResult.getMsg());

            // beat success
            if (beatResult.getCode() == ReturnT.SUCCESS_CODE) {
                //设置访问client请求结果,以及地址返回
                beatResult.setMsg(beatResultSB.toString());
                beatResult.setContent(address);
                return beatResult;
            }
        }
        return new ReturnT<String>(ReturnT.FAIL_CODE, beatResultSB.toString());

    }
}
```

```java
    private static ConcurrentMap<String, ExecutorBiz> executorBizRepository = new ConcurrentHashMap<String, ExecutorBiz>();
    public static ExecutorBiz getExecutorBiz(String address) throws Exception {
        // valid
        if (address==null || address.trim().length()==0) {
            return null;
        }

        // load-cache
        //从缓冲中通过地址获取ExecutorBiz
        address = address.trim();
        ExecutorBiz executorBiz = executorBizRepository.get(address);
        if (executorBiz != null) {
            return executorBiz;
        }

        // set-cache 找不到就新建
        executorBiz = new ExecutorBizClient(address, XxlJobAdminConfig.getAdminConfig().getAccessToken());

        executorBizRepository.put(address, executorBiz);
        return executorBiz;
    }
```
### 7) 1）6： 忙碌转移
按照顺序依次进行空闲检测，第一个空闲检测成功的机器选定为目标执行器并发起调度；

```java
public class ExecutorRouteBusyover extends ExecutorRouter {

    @Override
    public ReturnT<String> route(TriggerParam triggerParam, List<String> addressList) {
        StringBuffer idleBeatResultSB = new StringBuffer();
        //遍历每个地址
        for (String address : addressList) {
            // beat
            ReturnT<String> idleBeatResult = null;
            try { //缓存获取ExecutorBiz
                ExecutorBiz executorBiz = XxlJobScheduler.getExecutorBiz(address);
                //访问客户端是否空闲
                idleBeatResult = executorBiz.idleBeat(new IdleBeatParam(triggerParam.getJobId()));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                idleBeatResult = new ReturnT<String>(ReturnT.FAIL_CODE, ""+e );
            }
            idleBeatResultSB.append( (idleBeatResultSB.length()>0)?"<br><br>":"")
                    .append(I18nUtil.getString("jobconf_idleBeat") + "：")
                    .append("<br>address：").append(address)
                    .append("<br>code：").append(idleBeatResult.getCode())
                    .append("<br>msg：").append(idleBeatResult.getMsg());

            // beat success
            if (idleBeatResult.getCode() == ReturnT.SUCCESS_CODE) {
                idleBeatResult.setMsg(idleBeatResultSB.toString());
                idleBeatResult.setContent(address);
                return idleBeatResult;
            }
        }

        return new ReturnT<String>(ReturnT.FAIL_CODE, idleBeatResultSB.toString());
    }

}
```
### 7) 1）7： 分片广播
广播触发对应集群中所有机器执行一次任务，同时系统自动传递分片参数；可根据分片参数开发分片任务；
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210422150536789.png)

![在这里插入图片描述](https://img-blog.csdnimg.cn/2021042215060510.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

## 7) 2 ：加锁
<font color="red" size="3"> com.xxl.job.admin.core.thread.JobScheduleHelper#start  </font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210422203816536.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210422203829465.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)

表数据结构

![在这里插入图片描述](https://img-blog.csdnimg.cn/20210422203858780.png)

一般情况下查询的时候，默认数据库加读锁，这种级别是很低的，当xxjob存在集群部署的时候，存在多个线程争抢查询数据库，会造成触发器重复执行。为了防止这种情况，设置手动提交(这里spring会默认自动提交)，查询添加写锁。在此期间其他线程访问的时候都会阻塞等待。


## 7) 3 ：调度过期策略

```java
public enum MisfireStrategyEnum {

    /**
     * do nothing
     */
    //调度过期后，忽略过期的任务，从当前时间开始重新计算下次触发时间；
    DO_NOTHING(I18nUtil.getString("misfire_strategy_do_nothing")),

    /**
     * fire once now
     */
    //立即执行一次：调度过期后，立即执行一次，并从当前时间开始重新计算下次触发时间；
    FIRE_ONCE_NOW(I18nUtil.getString("misfire_strategy_fire_once_now"));
    }
```

<font color="red" size="3"> com.xxl.job.admin.core.thread.JobScheduleHelper#start </font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210426231438305.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)


## 7) 4 ：任务阻塞策略
```java
public enum ExecutorBlockStrategyEnum {
    //单机串行（默认）：调度请求进入单机执行器后，调度请求进入FIFO队列并以串行方式运行；
    SERIAL_EXECUTION("Serial execution"),
    /*CONCURRENT_EXECUTION("并行"),*/ //丢弃后续调度：调度请求进入单机执行器后，发现执行器存在运行的调度任务，本次请求将会被丢弃并标记为失败；
    DISCARD_LATER("Discard Later"),
    //覆盖之前调度：调度请求进入单机执行器后，发现执行器存在运行的调度任务，将会终止运行中的调度任务并清空队列，然后运行本地调度任务；
    COVER_EARLY("Cover Early");
    }
```

<font color="red" size="3"> com.xxl.job.core.biz.impl.ExecutorBizImpl#run </font>
![在这里插入图片描述](https://img-blog.csdnimg.cn/2021042623165885.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
## 7) 5 ：调度类型
```java
public enum ScheduleTypeEnum {

    //该类型不会主动触发调度；
    NONE(I18nUtil.getString("schedule_type_none")),

    /**
     * schedule by cron
     */
    //该类型将会通过CRON，触发任务调度；
    CRON(I18nUtil.getString("schedule_type_cron")),

    /**
     * schedule by fixed rate (in seconds)
     */
    //固定速度：该类型将会以固定速度，触发任务调度；按照固定的间隔时间，周期性触发；
    FIX_RATE(I18nUtil.getString("schedule_type_fix_rate")),;
    }
```

<font color="red" size="3"> com.xxl.job.admin.core.thread.JobScheduleHelper#generateNextValidTime </font>

```java
   public static Date generateNextValidTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        //匹配调度类型
        ScheduleTypeEnum scheduleTypeEnum = ScheduleTypeEnum.match(jobInfo.getScheduleType(), null);
        if (ScheduleTypeEnum.CRON == scheduleTypeEnum) {
            //通过CRON，触发任务调度；
            //通过cron表达式,获取下一次执行时间
            Date nextValidTime = new CronExpression(jobInfo.getScheduleConf()).getNextValidTimeAfter(fromTime);
            return nextValidTime;
        } else if (ScheduleTypeEnum.FIX_RATE == scheduleTypeEnum /*|| ScheduleTypeEnum.FIX_DELAY == scheduleTypeEnum*/) {
            //以固定速度，触发任务调度；按照固定的间隔时间，周期性触发；
            return new Date(fromTime.getTime() + Integer.valueOf(jobInfo.getScheduleConf())*1000 );
        }//没有匹配到调度类型,则null
        return null;
    }
```
固定速度和固定延迟是一样的..

## 7) 6 ：上下文对象，便于jobhandler获取上下文数据
当执行jobhandler时，会提前将triggerParam一下参数赋值给XxlJobContext,该对象绑定执行jobhandler线程,便于执行调度器时获取服务端执行命令参数

```
XxlJobContext xxlJobContext = new XxlJobContext(
							triggerParam.getJobId(),
							triggerParam.getExecutorParams(),
							logFileName,
							triggerParam.getBroadcastIndex(),
							triggerParam.getBroadcastTotal());

					// init job context
					XxlJobContext.setXxlJobContext(xxlJobContext);

					// execute
					XxlJobHelper.log("<br>----------- xxl-job job execute start -----------<br>----------- Param:" + xxlJobContext.getJobParam());
					//设置了超时就异步线程处理
					if (triggerParam.getExecutorTimeout() > 0) {
						// limit timeout
						Thread futureThread = null;
						try {
							FutureTask<Boolean> futureTask = new FutureTask<Boolean>(new Callable<Boolean>() {
								@Override
								public Boolean call() throws Exception {

									// init job context
									//便于jobhandler获取数据
									XxlJobContext.setXxlJobContext(xxlJobContext);

									handler.execute();
									return true;
								}
							});
							futureThread = new Thread(futureTask);
							futureThread.start();
							//异步线程处理并获取返回值
							Boolean tempResult = futureTask.get(triggerParam.getExecutorTimeout(), TimeUnit.SECONDS);
						} catch (TimeoutException e) {

							XxlJobHelper.log("<br>----------- xxl-job job execute timeout");
							XxlJobHelper.log(e);

							// handle result
							XxlJobHelper.handleTimeout("job execute timeout ");
						} finally {
							futureThread.interrupt();
						}
					} else {
						// just execute  没设置超时时间，则立刻执行触发器
						handler.execute();
					}
```

jobhandler从上下文获取数据

![在这里插入图片描述](https://img-blog.csdnimg.cn/20210429090204811.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)
![在这里插入图片描述](https://img-blog.csdnimg.cn/20210429090208303.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L051YW5fRmVuZw==,size_16,color_FFFFFF,t_70)