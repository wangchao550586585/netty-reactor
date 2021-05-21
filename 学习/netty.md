



# 1：环境配置

1. 注释所有

```
   <!-- <module>all</module>-->
<!--    <module>resolver-dns-native-macos</module>-->

<!--    <module>transport-native-unix-common-tests</module>
    <module>transport-native-unix-common</module>
    <module>transport-native-epoll</module>
    <module>transport-native-kqueue</module>-->

  <!--  <module>testsuite</module>
    <module>testsuite-autobahn</module>
    <module>testsuite-http2</module>
    <module>testsuite-osgi</module>
    <module>testsuite-shading</module>
    <module>testsuite-native</module>
    <module>testsuite-native-image</module>
    <module>testsuite-native-image-client</module>
    <module>testsuite-native-image-client-runtime-init</module>
    <module>transport-blockhound-tests</module>-->
```

注释<classifier>${tcnative.classifier}</classifier>

更改maven jdk编译版本

```
<maven.compiler.source>12</maven.compiler.source>
<maven.compiler.target>12</maven.compiler.target>
```

2：跳过运行时error

3：关闭校验

```
checkstyle.xml
```

4：maven deprecation 错误

```
<!--   <showDeprecation>true</showDeprecation>-->
```

```
<!--    <module>microbench</module>-->
```

5： 修改jdk版本

```
<!-- JDK12 NEW -->
<profile>
  <id>java1.8</id>
  <activation>
    <jdk>16</jdk>
  </activation>
  <properties>
    <!-- Not use alpn agent as Java11+ supports alpn out of the box -->
    <argLine.alpnAgent />
    <argLine.java9.extras />
    <!-- Export some stuff which is used during our tests -->
    <argLine.java9>--illegal-access=deny ${argLine.java9.extras}</argLine.java9>
    <forbiddenapis.skip>true</forbiddenapis.skip>
    <!-- Needed because of https://issues.apache.org/jira/browse/MENFORCER-275 -->
    <enforcer.plugin.version>3.0.0-M3</enforcer.plugin.version>
    <!-- 1.4.x does not work in Java10+ -->
    <jboss.marshalling.version>2.0.5.Final</jboss.marshalling.version>
    <!-- This is the minimum supported by Java12+ -->
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <!-- pax-exam does not work on latest Java12 EA 22 build -->
    <skipOsgiTestsuite>true</skipOsgiTestsuite>
  </properties>
</profile>
```

![image-20210514225811386](C:\Users\GIGA25\Desktop\学习\image-20210514225811386.png)



6：程序包sun.misc不存在 

![image-20210514230131104](C:\Users\GIGA25\Desktop\学习\image-20210514230131104.png)

7：注释异常

![image-20210514230247691](C:\Users\GIGA25\Desktop\学习\image-20210514230247691.png)

## 与其他对比

![image-20210518131114680](C:\Users\GIGA25\AppData\Roaming\Typora\typora-user-images\image-20210518131114680.png)

![image-20210518131124117](C:\Users\GIGA25\Desktop\学习\image-20210518131124117.png)

Callable	call  = 》 run Runnable

Future继承run作为callable桥接类

## future缺陷

join：无法获取结果

future：获取结果线程需要阻塞

最终结果：通过Guava异步回调获取结果处理

# 2：结构

```
EventLoopGroup
```

```
EventLoop  Executor   ,Reactor,绑定一个线程和Selector
SocketChannel   ：NioServerSocketChannel   channel  包含SelectableChannel
ChannelHandler
ChannelPipeline
ChannelFuture  ：因为所有操作都是异步的，所以future返回结果
ChannelPipeline
ByteBuf            buffer
```

handler  n-》1  pipeline 1-》1 Channel  n - 》1  EventLoop   n - 》 1  EventLoopGroup

EventLoop    1 -》 1  Thread 



# 3：设计模式  delay

### 策略模式  todo

### 责任链模式 todo

### 单例模式模式 todo

### 装饰者模式 todo

### 观察者模式 todo

### 迭代器模式 todo

### 工厂模式 todo

# 4：netty源码解析

## Channel  todo



## Future  todo

对Future增强，调用以非阻塞处理回调的结果

引入GenericFutureListener,表示异步执行完成的监听器。采用监听器模式,监听异步任务执行从而执行回调方法。

## ChannelHandler  todo



### ChannelInboundHandler

**ChannelInboundHandlerAdapter** 或**SimpleChannelInboundHandler**类，在这里顺便说下它们两的区别吧。 继承**SimpleChannelInboundHandler**类之后，会在接收到数据后会自动**release**掉数据占用的**Bytebuffer**资源。并且继承该类需要指定数据格式。 而继承**ChannelInboundHandlerAdapter**则不会自动释放，需要手动调用**ReferenceCountUtil.release()**等方法进行释放。继承该类不需要指定数据格式。 所以在这里，个人推荐服务端继承**ChannelInboundHandlerAdapter**，手动进行释放，防止数据未处理完就自动释放了。而且服务端可能有多个客户端进行连接，并且每一个客户端请求的数据格式都不一致，这时便可以进行相应的处理。 客户端根据情况可以继承**SimpleChannelInboundHandler**类。好处是直接指定好传输的数据格式，就不需要再进行格式的转换了。



![image-20210513215652796](C:\Users\GIGA25\Desktop\学习\image-20210513215652796.png)

- **HttpRequestDecoder** 即把 ByteBuf 解码到 HttpRequest 和 HttpContent。

- **HttpResponseEncoder** 即把 HttpResponse 或 HttpContent 编码到 ByteBuf。

- **HttpServerCodec** 即 HttpRequestDecoder 和 HttpResponseEncoder 的结合。

- ```
  HttpObjectAggregator 即通过它可以把 HttpMessage 和 HttpContent 聚合成一个 FullHttpRequest 或者 FullHttpResponse （取决于是处理请求还是响应），而且它还可以帮助你在解码时忽略是否为“块”传输方式。
  
  因此，在解析 HTTP POST 请求时，请务必在 ChannelPipeline 中加上 HttpObjectAggregator。
  ```

### 

```
handlerAdded:  ch.pipeline().addLast(inHandler); 触发
channelRegistered:通道注册完成后,成功绑定NioEventLoop线程后,会调用fireChannelRegistered,触发通道注册事件
channelActive:当通道激活完成后(所有的业务处理器添加,注册的异步任务完成,并且NioEventLoop线程绑定的异步任务完成),会调用fireChannelActive,触发通道激活事件
channelRead:当通道缓冲区可读,会调用fireChannelRead,触发通道可读事件
channelReadComplete:通道缓冲区读完,调用fireChannelReadComplete,触发通道读完事件

channelInactive:连接断开或不可用(当通道的底层连接已经不是ESTABLISH状态,或者底层连接已经关闭时),调用fireChannelInactive,触发连接不可用
exceptionCaught:抛出异常,调用fireExceptionCaught,触发异常捕获事件

handlerRemoved:netty移除通道上所有的业务处理器
```

#### 释放buf

若继承ChannelInboundHandlerAdapter,

```
//手动释放
in.release();
//调用父类释放
super.channelRead(ctx,msg);  会调用末端TailHandler末尾处理器自动释放缓冲区
```

SimpleChannelInboundHandler释放

```
//手动释放
in.release();
实现channelRead0方法,使用自动释放
```



### ChannelOutboundHandler

```
bind:监听地址,完成底层io通道的ip地址绑定,若使用Tcp,则只能服务端用
connect:连接服务端,如果使用TCP传输协议,只作用客户端
disconnect:断开与服务端连接,如果使用TCP传输协议,只作用客户端
close:主动关闭通道
```

#### 释放buf

HeadHandler释放



## ChannelHandlerContext

表示ChannelHandler和ChannelPipeline  之间关联

作用

1. 获取上下文所关联netty组件实例,如关联的通道,关联的流水线,上下文内部handler业务处理器实例等
2. 入站和出站处理方法

和ChannelHandler和ChannelPipeline不同,如果用ChannelHandlerContext则只会从当前的节点开始执行Handler业务处理器,并传播到同类型处理器的下一节点.



## ChannelPipeline  todo

双向链表

支持热插拔:动态删除,增加流水线上处理器

```
addFirst(String name, ChannelHandler handler); 头部添加handler
addLast(String name, ChannelHandler handler); 尾部添加handler
addBefore(String baseName, String name, ChannelHandler handler); 在baseName处理器的前面添加handler
addAfter(String baseName, String name, ChannelHandler handler);在baseName处理器的后面添加handler
```

## EventLoop    

![image-20210518231730078](C:\Users\GIGA25\Desktop\学习\image-20210518231730078.png)



## ByteBuf

### 池化

​	创建一个buf池,需要buf从池拿出,不需要则放回.然后通过引用计数方式来维护释放分配

计数=0则:

1. Pooled池化Buf放入池中，等待下次分配
2. 未池化buf，若是堆，则被jvm回收，若是直接内存类型，调用本地方法释放外部内存

```
UnpooledByteBufAllocator / PooledByteBufAllocator ：采用jemalloc高效内存分配策略
```

读取和写入索引分开，目前javanio是flip/clean来切换读写模式，而netty不一样，通过维护2套读写索引



![image-20210520192227151](C:\Users\GIGA25\Desktop\学习\image-20210520192227151.png)



![image-20210520192701813](C:\Users\GIGA25\Desktop\学习\image-20210520192701813.png)



```
创建完一个buf,引用计数=1,为0表示buf没有任何进程引用,占用的内存需要回收
refCnt:获取引用次数
retain:引用次数+1,引用为0,在此引用抛出异常
release:释放引用次数-1
```

![image-20210520225040444](C:\Users\GIGA25\Desktop\学习\image-20210520225040444.png)

![image-20210520225048609](C:\Users\GIGA25\Desktop\学习\image-20210520225048609.png)

### 浅层复制

```
slice():返回buf可读部分切片
slice(int index, int length):指定位置切片
duplicate():全部浅层复制.不会改变源引用计数.读写指针,最大容量与源一样.
存在问题,buf释放资源了，浅层复制的就不能读写了。可通过调用一次retain()增加引用.使用浅层buf后释放.
```



# 5:常见问题  todo

## 如何实现断线重连

## 如何实现长连接心跳保活机制

## 如何解决epoll空轮训导致cpu100%的bug

## 对空闲线程心跳的处理

## 粘包

## 拆包





# 6:内存模型 todo

## AIO

## NIO

## BIO

## mmap

## zero-copy





# 7:线程模型 todo

## Reactor

# 8:protobuf编解码机制 todo

# 9:Reactor响应式编程 todo



# 参考

https://netty.io/4.1/api/index.html     netty-api

 

https://netty.io/wiki/related-articles.html

https://www.iocoder.cn/Netty/Netty-collection/

https://www.bianchengquan.com/article/597368.html

https://github.com/sanshengshui/netty-learning-example

https://www.cnblogs.com/sanshengshui/p/9774746.html

https://segmentfault.com/a/1190000007282789

https://mp.weixin.qq.com/s?__biz=MzIxMDAwMDAxMw==&mid=2650725011&idx=1&sn=741b290093788f820cbb61905cc214bb&chksm=8f613b71b816b26775629757c9aec632ce645f8cdee5848e056300b09f1874a28205ed54151b&mpshare=1&scene=23&srcid=&sharer_sharetime=1570838084571&sharer_shareid=12ae0c9c538778cd36ca6e4500b81b6f#rd

​	 github https://github.com/fuzhengwei/itstack-demo-netty



Netty实战   <人邮><工信>

Java高并发核心编程 卷1：NIO、Netty、Redis、ZooKeeper   <机械工业出版社]> 

​	 github：https://gitee.com/crazymaker/crazy_tourist_circle__im

Netty权威指南  <李林峰> <电子工业出版社>

Netty进阶之路跟着案例学Netty    李林锋  <电子工业出版社>

Netty 4核心原理与手写RPC框架实战   <电子工业出版社>

​	github  https://github.com/gupaoedu-tom/netty4-samples

Netty原理解析与开发实战

Netty4开发手册

Netty手册

Netty5.0架构剖析和源码解读



https://www.52doc.com/download/7341  下载所有电子书



***\**\*[《 Java 高并发 三部曲 》](https://www.cnblogs.com/crazymakercircle/p/14493539.html)\*\**\***

https://www.cnblogs.com/crazymakercircle/p/9904544.html





理论场景实践