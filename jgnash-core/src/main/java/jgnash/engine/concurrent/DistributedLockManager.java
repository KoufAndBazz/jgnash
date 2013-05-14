/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2013 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.engine.concurrent;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.BufType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import jgnash.net.ConnectionFactory;
import jgnash.util.EncodeDecode;

import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lock manager for distributed engine instances
 */
public class DistributedLockManager implements LockManager {

    private static final Logger logger = Logger.getLogger(DistributedLockManager.class.getName());

    private Map<String, DistributedReadWriteLock> lockMap = new ConcurrentHashMap<>();

    private Map<String, CountDownLatch> latchMap = new ConcurrentHashMap<>();

    /** lock_action, lock_id, thread_id, lock_type */
    private static final String PATTERN = "{0},{1},{2},{3}";

    private Bootstrap bootstrap;

    private final int port;

    private final String host;

    private Channel channel;

    private ChannelFuture lastWriteFuture = null;

    public static final String EOL_DELIMITER = "\r\n";

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public DistributedLockManager(final String host, final int port) {
        this.host = host;
        this.port = port;
    }

    public boolean connectToServer() {
        boolean result = false;

        bootstrap = new Bootstrap();

        bootstrap.group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new MessageBusClientInitializer())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ConnectionFactory.getConnectionTimeout() * 1000);

        try {
            // Start the connection attempt.
            channel = bootstrap.connect(host, port).sync().channel();

            result = true;
            logger.info("Connection made with Distributed Lock Server");
        } catch (final InterruptedException e) {
            logger.log(Level.SEVERE, "Failed to connect to Distributed Lock Server", e);
            disconnectFromServer();
        }

        return result;
    }

    public void disconnectFromServer() {

        // Wait until all messages are flushed before closing the channel.
        if (lastWriteFuture != null) {
            try {
                lastWriteFuture.sync();
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
            }
        }

        try {
            channel.close().sync();
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
        }

        executorService.shutdown();
        bootstrap.shutdown();

        channel = null;
        lastWriteFuture = null;
        bootstrap = null;

        logger.info("Disconnected from the Distributed Lock Server");
    }

    @Override
    public ReadWriteLock getLock(final String lockId) {
        DistributedReadWriteLock lock = lockMap.get(lockId);

        if (lock == null) {
            lock = new DistributedReadWriteLock(this, lockId);
            lockMap.put(lockId, lock);
        }

        return lock;
    }

    CountDownLatch getLatch(final String lockId) {
        CountDownLatch semaphore = latchMap.get(lockId);

        if (semaphore == null) {
            semaphore = new CountDownLatch(1);
            latchMap.put(lockId, semaphore);
        }

        return semaphore;
    }

    void lock(final String lockId, final String type) {
        changeLockState(lockId, type, "lock");
    }

    void unlock(final String lockId, final String type) {
        changeLockState(lockId, type, "unlock");
    }

    void changeLockState(final String lockId, final String type, final String lockState) {
        final Integer threadId = Thread.currentThread().hashCode();
        final String message = MessageFormat.format(PATTERN, lockState, lockId, threadId.toString(), type);
        final CountDownLatch responseLatch = getLatch(lockId);

        // send the message to the server
        lastWriteFuture = channel.write(message + EOL_DELIMITER);

        try {
            responseLatch.await();    // block until a response is received
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
        }
    }

    public void processMessage(final String message) {

        logger.info("message: " + message);

        /** lock_action, lock_id, thread_id, lock_type */
        // unlock,account,3456384756384563,read
        // lock,account,3456384756384563,write

        // decode the message into it's parts
        final String[] strings = EncodeDecode.decodeStringCollection(message).toArray(new String[4]);

        final String lockId = strings[1];   // get the lock id
        final CountDownLatch responseLatch = getLatch(lockId);

        responseLatch.countDown();  // this should release the responseLatch allowing the thread to continue

        latchMap.remove(lockId);    // remove the used up latch
    }

    private class MessageBusClientInitializer extends ChannelInitializer<SocketChannel> {
        private final StringDecoder DECODER = new StringDecoder();
        private final StringEncoder ENCODER = new StringEncoder(BufType.BYTE);
        private final ClientHandler CLIENT_HANDLER = new ClientHandler();

        @Override
        public void initChannel(final SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();

            // Add the text line codec combination first,
            pipeline.addLast("framer", new DelimiterBasedFrameDecoder(8192, true, Delimiters.lineDelimiter()));
            pipeline.addLast("decoder", DECODER);
            pipeline.addLast("encoder", ENCODER);

            // and then business logic.
            pipeline.addLast("handler", CLIENT_HANDLER);
        }
    }

    /**
     * Handles a client-side channel.
     */
    @ChannelHandler.Sharable
    private class ClientHandler extends ChannelInboundMessageHandlerAdapter<String> {

        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final String msg) throws Exception {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    processMessage(msg);
                }
            });
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            logger.log(Level.WARNING, "Unexpected exception from downstream.", cause);
            ctx.close();
        }
    }

    private class DistributedReadWriteLock extends ReentrantReadWriteLock {

        private final String lockId;

        private final DistributedReadWriteLock.ReadLock readLock;

        private final DistributedReadWriteLock.WriteLock writeLock;

        private final DistributedLockManager lockManager;

        DistributedReadWriteLock(final DistributedLockManager lockManager, final String lockId) {
            super();

            this.lockId = lockId;
            this.lockManager = lockManager;

            readLock = new DistributedReadWriteLock.ReadLock(this);
            writeLock = new DistributedReadWriteLock.WriteLock(this);
        }

        @Override
        public ReentrantReadWriteLock.ReadLock readLock() {
            return readLock;
        }

        @Override
        public ReentrantReadWriteLock.WriteLock writeLock() {
            return writeLock;
        }

        protected class ReadLock extends ReentrantReadWriteLock.ReadLock {

            protected ReadLock(final ReentrantReadWriteLock lock) {
                super(lock);
            }

            @Override
            public void lock() {
                lockManager.lock(lockId, DistributedLockServer.LOCK_TYPE_READ);
                super.lock();
            }

            @Override
            public void unlock() {
                lockManager.unlock(lockId, DistributedLockServer.LOCK_TYPE_READ);
                super.unlock();
            }
        }

        protected class WriteLock extends ReentrantReadWriteLock.WriteLock {

            protected WriteLock(final ReentrantReadWriteLock lock) {
                super(lock);
            }

            @Override
            public void lock() {
                lockManager.lock(lockId, DistributedLockServer.LOCK_TYPE_WRITE);
                super.lock();
            }

            @Override
            public void unlock() {
                lockManager.unlock(lockId, DistributedLockServer.LOCK_TYPE_WRITE);
                super.unlock();
            }
        }
    }
}