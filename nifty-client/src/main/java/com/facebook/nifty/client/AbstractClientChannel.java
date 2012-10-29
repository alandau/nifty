/**
 * Copyright 2012 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.nifty.client;

import io.airlift.units.Duration;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class AbstractClientChannel extends SimpleChannelHandler implements
        NiftyClientChannel {
    private final Channel nettyChannel;
    private Duration sendTimeout = null;
    private Duration requestTimeout = null;
    private final HashMap<Integer, Request> requestMap = new HashMap<Integer, Request>();
    private volatile TException channelError;
    private final Timer timer;

    public AbstractClientChannel(Channel nettyChannel, Timer timer) {
        this.nettyChannel = nettyChannel;
        this.timer = timer;
    }

    @Override
    public Channel getNettyChannel() {
        return nettyChannel;
    }

    protected abstract ChannelBuffer extractResponse(Object message);

    protected abstract int extractSequenceId(ChannelBuffer message)
            throws TTransportException;

    protected abstract ChannelFuture writeRequest(ChannelBuffer request);

    public void close()
    {
        getNettyChannel().close();
    }

    @Override
    public void setSendTimeout(Duration sendTimeout)
    {
        this.sendTimeout = sendTimeout;
    }

    @Override
    public Duration getSendTimeout()
    {
        return sendTimeout;
    }

    @Override
    public void setReceiveTimeout(Duration receiveTimeout)
    {
        this.requestTimeout = receiveTimeout;
    }

    @Override
    public Duration getReceiveTimeout()
    {
        return this.requestTimeout;
    }

    @Override
    public boolean hasError()
    {
        return channelError != null;
    }

    @Override
    public TException getError()
    {
        return channelError;
    }

    @Override
    public void executeInIoThread(Runnable runnable)
    {
        NioSocketChannel nioSocketChannel = (NioSocketChannel) getNettyChannel();
        nioSocketChannel.getWorker().executeInIoThread(runnable, true);
    }

    private boolean hasRequestTimeout()
    {
        return requestTimeout != null;
    }

    @Override
    public void sendAsynchronousRequest(final ChannelBuffer message,
                                        final boolean oneway,
                                        final Listener listener)
            throws TException
    {
        final int sequenceId = extractSequenceId(message);

        // Ensure channel listeners are always called on the channel's I/O thread
        executeInIoThread(new Runnable()
        {
            @Override
            public void run()
            {
                ChannelFuture sendFuture = writeRequest(message);
                final Request request = makeRequest(sequenceId, listener);
                queueSendTimeout(request);

                sendFuture.addListener(new ChannelFutureListener()
                {
                    @Override
                    public void operationComplete(ChannelFuture future)
                            throws Exception
                    {
                        if (future.isSuccess()) {
                            cancelRequestTimeouts(request);
                            listener.requestSent();
                            if (oneway) {
                                retireRequest(sequenceId);
                            } else {
                                queueReceiveTimeout(request);
                            }
                        } else {
                            TTransportException transportException =
                                    new TTransportException("Sending request failed",
                                                            future.getCause());
                            listener.channelError(transportException);
                            onError(transportException);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
    {
        ChannelBuffer response = extractResponse(e.getMessage());

        if (response != null) {
            try {
                int sequenceId = extractSequenceId(response);
                onResponseReceived(sequenceId, response);
            }
            catch (Throwable t) {
                onError(t);
            }
        }
        else {
            ctx.sendUpstream(e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent event)
            throws Exception
    {
        Throwable t = event.getCause();
        ctx.getChannel().close();
        onError(t);
    }

    private Request makeRequest(int sequenceId, Listener listener)
    {
        Request request = new Request(sequenceId, listener);
        requestMap.put(sequenceId, request);
        return request;
    }

    private Request retireRequest(int sequenceId)
    {
        Request request = requestMap.remove(sequenceId);
        cancelRequestTimeouts(request);
        return request;
    }

    private void cancelRequestTimeouts(Request request)
    {
        Timeout sendTimeout = request.getSendTimeout();
        if (sendTimeout != null) {
            sendTimeout.cancel();
        }

        Timeout responseTimeout = request.getReceiveTimeout();
        if (responseTimeout != null) {
            responseTimeout.cancel();
        }
    }

    private void cancelAllTimeouts()
    {
        for (Request request : requestMap.values()) {
            cancelRequestTimeouts(request);
        }
    }

    private void onResponseReceived(int sequenceId, ChannelBuffer response)
    {
        Request request = retireRequest(sequenceId);
        if (request == null) {
            onError(new TTransportException("Bad sequence id in response: " + sequenceId));
        } else {
            request.getListener().responseReceived(response);
        }
    }

    private void onError(Throwable t)
    {
        TException wrappedException = wrapException(t);

        if (channelError == null) {
            channelError = wrappedException;
        }

        cancelAllTimeouts();

        Collection<Request> requests = new ArrayList<Request>();
        requests.addAll(requestMap.values());
        requestMap.clear();
        for (Request request : requests) {
            request.getListener().channelError(wrappedException);
        }
    }

    protected TException wrapException(Throwable t)
    {
        if (t instanceof TException) {
            return (TException) t;
        } else {
            return new TException(t);
        }
    }

    private void onSendTimeoutExpired(Request request)
    {
        Timeout expiredTimeout = request.getSendTimeout();

        if (!expiredTimeout.isCancelled()) {
            cancelAllTimeouts();
            TimeoutException timeoutException =
                    new TimeoutException("Timed out waiting " + (long) getSendTimeout().toMillis() +
                                         " ms to send request");

            request.getListener().channelError(new TTransportException(timeoutException));
        }
    }

    private void onReceiveTimeoutExpired(Request request)
    {
        Timeout expiredTimeout = request.getReceiveTimeout();

        if (!expiredTimeout.isCancelled()) {
            cancelAllTimeouts();

            TimeoutException timeoutException =
                    new TimeoutException(
                            "Timed out waiting " + (long) getReceiveTimeout().toMillis() +
                            " ms to receive response");

            request.getListener().channelError(new TTransportException(timeoutException));
        }
    }

    private void queueSendTimeout(Request request)
    {
        if (this.sendTimeout != null) {
            double sendTimeoutMs = this.sendTimeout.toMillis();
            if (sendTimeoutMs > 0) {
                final TimerTask sendTimeoutTask = new SendTimeoutTask(this, request);

                Timeout sendTimeout = timer.newTimeout(sendTimeoutTask,
                                                       (long) sendTimeoutMs,
                                                       TimeUnit.MILLISECONDS);
                request.setSendTimeout(sendTimeout);
            }
        }
    }

    private void queueReceiveTimeout(Request request)
    {
        if (this.requestTimeout != null) {
            double requestTimeoutMs = this.requestTimeout.toMillis();
            if (requestTimeoutMs > 0) {
                final TimerTask receiveTimeoutTask = new ReceiveTimeoutTask(this, request);

                Timeout timeout = timer.newTimeout(receiveTimeoutTask,
                                                   (long) requestTimeoutMs,
                                                   TimeUnit.MILLISECONDS);
                request.setReceiveTimeout(timeout);
            }
        }
    }

    private static class SendTimeoutTask implements TimerTask
    {
        private final Request request;
        private final AbstractClientChannel channel;

        public SendTimeoutTask(AbstractClientChannel channel, Request request)
        {
            this.request = request;
            this.channel = channel;
        }

        @Override
        public void run(Timeout timeout)
                throws Exception
        {
            channel.executeInIoThread(new Runnable()
            {
                @Override
                public void run()
                {
                    channel.onSendTimeoutExpired(request);
                }
            });
        }
    }

    private static class ReceiveTimeoutTask implements TimerTask
    {
        private final Request request;
        private final AbstractClientChannel channel;

        public ReceiveTimeoutTask(AbstractClientChannel channel, Request request)
        {
            this.request = request;
            this.channel = channel;
        }

        @Override
        public void run(Timeout timeout)
                throws Exception
        {
            channel.executeInIoThread(new Runnable()
            {
                @Override
                public void run()
                {
                    channel.onReceiveTimeoutExpired(request);
                }
            });
        }
    }

    /**
     * Bundles the details of a client request that has started, but for which a response hasn't
     * yet been received (or in the one-way case, the send operation hasn't completed yet).
     */
    private static class Request
    {
        private final int sequenceId;
        private final Listener listener;
        private Timeout sendTimeout;
        private Timeout receiveTimeout;

        public Request(int sequenceId, Listener listener)
        {
            this.sequenceId = sequenceId;
            this.listener = listener;
        }

        public int getSequenceId()
        {
            return sequenceId;
        }

        public Listener getListener()
        {
            return listener;
        }

        public Timeout getReceiveTimeout()
        {
            return receiveTimeout;
        }

        public void setReceiveTimeout(Timeout receiveTimeout)
        {
            this.receiveTimeout = receiveTimeout;
        }

        public Timeout getSendTimeout()
        {
            return sendTimeout;
        }

        public void setSendTimeout(Timeout sendTimeout)
        {
            this.sendTimeout = sendTimeout;
        }
    }
}
