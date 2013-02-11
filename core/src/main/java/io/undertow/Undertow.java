package io.undertow;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.undertow.ajp.AjpOpenListener;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpOpenListener;
import io.undertow.server.HttpTransferEncodingHandler;
import io.undertow.server.handlers.CookieHandler;
import io.undertow.server.handlers.NameVirtualHostHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.error.SimpleErrorPageHandler;
import io.undertow.server.handlers.form.FormEncodedDataHandler;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Pool;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.ConnectedStreamChannel;

/**
 * Convenience class used to build an Undertow server.
 *
 * TODO: This API is still a work in progress
 *
 * @author Stuart Douglas
 */
public class Undertow {

    private final int bufferSize;
    private final int buffersPerRegion;
    private final int ioThreads;
    private final int workerThreads;
    private final boolean directBuffers;
    private final List<ListenerConfig> listeners = new ArrayList<ListenerConfig>();
    private final List<VirtualHost> hosts = new ArrayList<VirtualHost>();

    private XnioWorker worker;
    private List<AcceptingChannel<? extends ConnectedStreamChannel>> channels;
    private Xnio xnio;

    private Undertow(Builder builder) {
        this.bufferSize = builder.bufferSize;
        this.buffersPerRegion = builder.buffersPerRegion;
        this.ioThreads = builder.ioThreads;
        this.workerThreads = builder.workerThreads;
        this.directBuffers = builder.directBuffers;
        this.listeners.addAll(builder.listeners);
        this.hosts.addAll(builder.hosts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public synchronized void start() {
        xnio = Xnio.getInstance("nio", Undertow.class.getClassLoader());
        channels = new ArrayList<AcceptingChannel<? extends ConnectedStreamChannel>>();
        try {
            worker = xnio.createWorker(OptionMap.builder()
                    .set(Options.WORKER_WRITE_THREADS, ioThreads)
                    .set(Options.WORKER_READ_THREADS, ioThreads)
                    .set(Options.CONNECTION_HIGH_WATER, 1000000)
                    .set(Options.CONNECTION_LOW_WATER, 1000000)
                    .set(Options.WORKER_TASK_CORE_THREADS, workerThreads)
                    .set(Options.WORKER_TASK_MAX_THREADS, workerThreads)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.CORK, true)
                    .getMap());

            OptionMap serverOptions = OptionMap.builder()
                    .set(Options.WORKER_ACCEPT_THREADS, ioThreads)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.REUSE_ADDRESSES, true)
                    .getMap();

            Pool<ByteBuffer> buffers = new ByteBufferSlicePool(directBuffers ? BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR : BufferAllocator.BYTE_BUFFER_ALLOCATOR, bufferSize, bufferSize * buffersPerRegion);

            HttpHandler rootHandler = buildHandlerChain();

            for (ListenerConfig listener : listeners) {
                if (listener.type == ListenerType.AJP) {
                    AjpOpenListener openListener = new AjpOpenListener(buffers, bufferSize);
                    openListener.setRootHandler(rootHandler);
                    ChannelListener<AcceptingChannel<ConnectedStreamChannel>> acceptListener = ChannelListeners.openListenerAdapter(openListener);
                    AcceptingChannel<? extends ConnectedStreamChannel> server = worker.createStreamServer(new InetSocketAddress(Inet4Address.getByName(listener.host), listener.port), acceptListener, serverOptions);
                    server.resumeAccepts();
                    channels.add(server);
                } else if (listener.type == ListenerType.HTTP) {
                    HttpOpenListener openListener = new HttpOpenListener(buffers, OptionMap.create(UndertowOptions.BUFFER_PIPELINED_DATA, true), bufferSize);
                    openListener.setRootHandler(new HttpTransferEncodingHandler(rootHandler));
                    ChannelListener<AcceptingChannel<ConnectedStreamChannel>> acceptListener = ChannelListeners.openListenerAdapter(openListener);
                    AcceptingChannel<? extends ConnectedStreamChannel> server = worker.createStreamServer(new InetSocketAddress(Inet4Address.getByName(listener.host), listener.port), acceptListener, serverOptions);
                    server.resumeAccepts();
                    channels.add(server);
                }
                //TODO: https
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void stop() {
        for(AcceptingChannel<? extends ConnectedStreamChannel> channel : channels) {
            IoUtils.safeClose(channel);
        }
        channels = null;
        worker.shutdownNow();
        worker = null;
        xnio = null;
    }

    private HttpHandler buildHandlerChain() {
        final NameVirtualHostHandler virtualHostHandler = new NameVirtualHostHandler();
        for (VirtualHost host : hosts) {
            final PathHandler paths = new PathHandler();
            paths.setDefaultHandler(host.defaultHandler);
            for (final Map.Entry<String, HttpHandler> entry : host.handlers.entrySet()) {
                paths.addPath(entry.getKey(), entry.getValue());
            }
            HttpHandler handler = paths;
            for (HandlerWrapper<HttpHandler> wrapper : host.wrappers) {
                handler = wrapper.wrap(handler);
            }
            if (host.defaultHost) {
                virtualHostHandler.setDefaultHandler(handler);
            }
            for (String hostName : host.hostNames) {
                virtualHostHandler.addHost(hostName, handler);
            }
        }

        HttpHandler root = virtualHostHandler;
        root = new CookieHandler(root);
        root = new FormEncodedDataHandler(root);
        root = new SimpleErrorPageHandler(root);
        //TODO: multipart

        return root;
    }


    public static enum ListenerType {
        HTTP,
        HTTPS,
        AJP
    }

    private static class ListenerConfig {
        final ListenerType type;
        final int port;
        final String host;

        private ListenerConfig(final ListenerType type, final int port, final String host) {
            this.type = type;
            this.port = port;
            this.host = host;
        }
    }

    public interface Host<T> {

        T addPathHandler(final String path, final HttpHandler handler);

        T setDefaultHandler(final HttpHandler handler);

        T addHandlerWrapper(final HandlerWrapper<HttpHandler> wrapper);

    }

    public static class VirtualHost implements Host<VirtualHost> {

        private final List<String> hostNames = new ArrayList<String>();
        private final Map<String, HttpHandler> handlers = new HashMap<String, HttpHandler>();
        private final List<HandlerWrapper<HttpHandler>> wrappers = new ArrayList<HandlerWrapper<HttpHandler>>();
        private final boolean defaultHost;
        private HttpHandler defaultHandler = ResponseCodeHandler.HANDLE_404;

        public VirtualHost(final boolean defaultHost) {
            this.defaultHost = defaultHost;
        }


        public VirtualHost addHostName(final String hostName) {
            hostNames.add(hostName);
            return this;
        }

        public VirtualHost addPathHandler(final String path, final HttpHandler handler) {
            handlers.put(path, handler);
            return this;
        }

        public VirtualHost setDefaultHandler(final HttpHandler handler) {
            this.defaultHandler = handler;
            return this;
        }

        public VirtualHost addHandlerWrapper(final HandlerWrapper<HttpHandler> wrapper) {
            wrappers.add(wrapper);
            return this;
        }
    }


    public static final class Builder implements Host<Builder> {

        private int bufferSize;
        private int buffersPerRegion;
        private int ioThreads;
        private int workerThreads;
        private boolean directBuffers;
        private final List<ListenerConfig> listeners = new ArrayList<ListenerConfig>();
        private final List<VirtualHost> hosts = new ArrayList<VirtualHost>();
        private final VirtualHost defaultHost = new VirtualHost(true);

        private Builder() {
            ioThreads = Runtime.getRuntime().availableProcessors();
            workerThreads = ioThreads * 8;
            long maxMemory = Runtime.getRuntime().maxMemory();
            //smaller than 64mb of ram we use 512b buffers
            if (maxMemory < 64 * 1024 * 1024) {
                //use 512b buffers
                directBuffers = false;
                bufferSize = 512;
                buffersPerRegion = 10;
            } else if (maxMemory < 128 * 1024 * 1024) {
                //use 1k buffers
                directBuffers = true;
                bufferSize = 1024;
                buffersPerRegion = 10;
            } else {
                //use 4k buffers
                directBuffers = true;
                bufferSize = 1024 * 4;
                buffersPerRegion = 20;
            }
            hosts.add(defaultHost);
        }

        public Undertow build() {
            return new Undertow(this);
        }

        public Builder addListener(int port, String host) {
            listeners.add(new ListenerConfig(ListenerType.HTTP, port, host));
            return this;
        }

        public Builder setBufferSize(final int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public Builder setBuffersPerRegion(final int buffersPerRegion) {
            this.buffersPerRegion = buffersPerRegion;
            return this;
        }

        public Builder setIoThreads(final int ioThreads) {
            this.ioThreads = ioThreads;
            return this;
        }

        public Builder setWorkerThreads(final int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder setDirectBuffers(final boolean directBuffers) {
            this.directBuffers = directBuffers;
            return this;
        }

        public VirtualHost addVirtualHost(final String hostName) {
            VirtualHost host = new VirtualHost(false);
            host.addHostName(hostName);
            hosts.add(host);
            return host;
        }

        @Override
        public Builder addPathHandler(final String path, final HttpHandler handler) {
            defaultHost.addPathHandler(path, handler);
            return this;
        }

        @Override
        public Builder setDefaultHandler(final HttpHandler handler) {
            defaultHost.setDefaultHandler(handler);
            return this;
        }

        @Override
        public Builder addHandlerWrapper(final HandlerWrapper<HttpHandler> wrapper) {
            defaultHost.addHandlerWrapper(wrapper);
            return this;
        }

    }

}