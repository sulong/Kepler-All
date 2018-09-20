package com.kepler.registry.etcd;

import com.coreos.jetcd.Watch;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.kv.GetResponse;
import com.coreos.jetcd.kv.PutResponse;
import com.coreos.jetcd.watch.WatchEvent;
import com.google.common.collect.Lists;
import com.kepler.config.Profile;
import com.kepler.config.PropertiesUtils;
import com.kepler.etcd.EtcdClient;
import com.kepler.host.Host;
import com.kepler.host.HostsContext;
import com.kepler.host.impl.ServerHost;
import com.kepler.main.Demotion;
import com.kepler.registry.Registry;
import com.kepler.registry.RegistryContext;
import com.kepler.serial.Serials;
import com.kepler.service.ImportedListener;
import com.kepler.service.Service;
import com.kepler.service.ServiceInstance;
import com.kepler.zookeeper.ZkSerial;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * etcd注册中心
 *
 * @author longyaokun
 */
public class EtcdContext implements Registry, Demotion {

    private static final Log LOGGER = LogFactory.getLog(EtcdContext.class);

    /**
     * 保存服务信息路径
     */
    private static final String ROOT = PropertiesUtils.get(EtcdContext.class.getName().toLowerCase() + ".root", "/kepler");

    /**
     * Watcher 线程数
     */
    private static final int WATCHER_THREAD = PropertiesUtils.get(EtcdContext.class.getName().toLowerCase() + ".watcher_thread", 200);

    /**
     * 是否发布
     */
    private static final String EXPORT_KEY = EtcdContext.class.getName().toLowerCase() + ".export";

    private static final boolean EXPORT_VAL = PropertiesUtils.get(EtcdContext.EXPORT_KEY, true);

    /**
     * 是否导入
     */
    private static final String IMPORT_KEY = EtcdContext.class.getName().toLowerCase() + ".import";

    private static final boolean IMPORT_VAL = PropertiesUtils.get(EtcdContext.IMPORT_KEY, true);

    /**
     * 服务keep alive 任务配置
     */
    private static final long KEEP_ALIVE_INTERVAL = PropertiesUtils.get(EtcdContext.class.getName().toLowerCase() + ".kl_interval", 10 * 1000);

    private static final int KEEP_ALIVE_DELAY = PropertiesUtils.get(EtcdContext.class.getName().toLowerCase() + ".kl_delay", 30000);

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(WATCHER_THREAD);

    private final Map<Service, Future<?>> serviceWatcher = new ConcurrentHashMap<>();

    /**
     * 需发布服务
     */
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    /**
     * 待发布服务
     */
    private final Map<String, ServiceInstance> retry = new ConcurrentHashMap<>();

    /**
     * 已导入服务
     */
    private final Set<Service> imported = new CopyOnWriteArraySet<>();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final ImportedListener listener;

    private final HostsContext hosts;

    private final ServerHost local;

    private final Profile profile;

    private final Serials serials;

    private final EtcdClient etcdClient;

    private volatile boolean shutdown;

    public EtcdContext(ImportedListener listener, HostsContext hosts, ServerHost local, Profile profile, Serials serials, EtcdClient etcdClient) {
        this.listener = listener;
        this.hosts = hosts;
        this.local = local;
        this.profile = profile;
        this.serials = serials;
        this.etcdClient = etcdClient;
    }

    public void init() throws Exception {
        //启动keepalive线程定时去检查etcd租约状态，过期则重发
        this.executor.scheduleAtFixedRate(new KeepAlive(), KEEP_ALIVE_DELAY, KEEP_ALIVE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void destroy() throws Exception {
        this.shutdown = true;
        this.etcdClient.close();
        this.executor.shutdownNow();
    }

    @Override
    public void registration(Service service, Object instance) throws Exception {
        // 是否发布远程服务
        if (!PropertiesUtils.profile(this.profile.profile(service), EtcdContext.EXPORT_KEY, EtcdContext.EXPORT_VAL)) {
            EtcdContext.LOGGER.warn("Disabled export service: " + service + " ... ");
            return;
        }

        // 生成节点信息，复用原zk的对象(Profile Tag, Priority)
        ServiceInstance serial = new ZkSerial(new ServerHost.Builder(this.local).setTag(PropertiesUtils.profile(this.profile.profile(service), Host.TAG_KEY, Host.TAG_VAL)).setPriority(PropertiesUtils.profile(this.profile.profile(service), Host.PRIORITY_KEY, Host.PRIORITY_DEF)).toServerHost(), service);

        // 存入etcd
        String key = key(service);
        this.instances.put(key, serial);
        if (!export(key, serial, true)) {
            this.retry.put(key, serial);
        }
        EtcdContext.LOGGER.info("Export service to etcd: " + service + " ... ");
    }

    @Override
    public void unRegistration(Service service) throws Exception {
        String key = key(service);
        try {
            lock.writeLock().lock();

            this.instances.remove(key);
            this.retry.remove(key);
            this.etcdClient.delete(key);

            EtcdContext.LOGGER.info("Logout service: " + service + " ... ");
        } catch (Exception e) {
            throw e;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void discovery(Service service) throws Exception {
        // 是否加载远程服务
        if (!PropertiesUtils.profile(this.profile.profile(service), EtcdContext.IMPORT_KEY, EtcdContext.IMPORT_VAL)) {
            EtcdContext.LOGGER.warn("Disabled import service from etcd: " + service + " ... ");
            return;
        }

        List<ServiceInstance> instances = Lists.newArrayList();
        long revision = -1;

        GetResponse getResponse = etcdClient.getAllByPrefix(prefix(service)).get();
        for (KeyValue keyValue : getResponse.getKvs()) {
            instances.add(deSerialize(keyValue.getValue().getBytes()));
            long modRevision = keyValue.getModRevision();
            if (modRevision > revision) {
                revision = modRevision;
            }
        }

        if (!CollectionUtils.isEmpty(instances)) {
            instances.forEach(instance -> {
                try {
                    listener.add(instance);
                } catch (Throwable e) {
                    EtcdContext.LOGGER.error(e.getMessage(), e);
                }
            });
        }
        if (!imported.contains(service)) {
            serviceWatcher.put(service, executor.submit(new ImportServiceWatcher(service, revision + 1)));
            imported.add(service);
        }
    }

    @Override
    public void unDiscovery(Service service) throws Exception {
        Future<?> serviceWatcherFuture = serviceWatcher.remove(service);
        if (serviceWatcherFuture != null) {
            serviceWatcherFuture.cancel(true);
        }
        imported.remove(service);
    }

    @Override
    public String registryName() {
        return RegistryContext.ETCD;
    }

    @Override
    public void onRefreshEvent(ContextRefreshedEvent event) {

    }

    @Override
    public void demote() throws Exception {
        this.instances.forEach((key, serviceInstance) -> {
            try {
                export(key, new ZkSerial(new ServerHost.Builder(serviceInstance.host()).setPriority(0).toServerHost(), serviceInstance), false);
            } catch (Exception e) {
                EtcdContext.LOGGER.error("Demote service failed for " + serviceInstance.toString() + ", message=" + e.getMessage(), e);
            }
        });
    }

    private String key(Service service) {
        return prefix(service) + "/" + this.local.sid();
    }

    private String prefix(Service service) {
        return EtcdContext.ROOT + "/" + service.service() + "/" + service.versionAndCatalog();
    }

    private boolean export(String key, ServiceInstance serial, boolean watch) throws Exception {
        PutResponse putResponse = this.etcdClient.put(key, serialize(serial)).handle((r, e) -> {
            if (e != null) {
                EtcdContext.LOGGER.error("Failed to put etcd for " + key + ", message = " + e.getMessage(), e);
                return null;
            }
            return r;
        }).get();
        if (putResponse == null) {
            return false;
        }

        if (watch) {
            executor.submit(new ExportServiceWatcher(key, serial, putResponse.getHeader().getRevision() + 1));
        }

        return true;
    }

    private ServiceInstance deSerialize(byte[] bytes) {
        return this.serials.def4input().input(bytes, ServiceInstance.class);
    }

    private byte[] serialize(ServiceInstance serial) {
        return this.serials.def4output().output(serial, ServiceInstance.class);
    }

    private class ExportServiceWatcher implements Runnable {

        private String key;

        private ServiceInstance serial;

        private long revision;

        ExportServiceWatcher(String key, ServiceInstance serial, long revision) {
            this.key = key;
            this.serial = serial;
            this.revision = revision;
        }

        @Override
        public void run() {
            Watch.Watcher watcher = EtcdContext.this.etcdClient.watch(key, revision);
            OUTER:
            while (!EtcdContext.this.shutdown) {
                try {
                    for (WatchEvent watchEvent : watcher.listen().getEvents()) {
                        KeyValue prev = watchEvent.getPrevKV();
                        switch (watchEvent.getEventType()) {
                            case DELETE:
                                try {
                                    EtcdContext.this.lock.readLock().lock();

                                    if (EtcdContext.this.instances.containsKey(key)) {
                                        EtcdContext.this.retry.put(key, serial);
                                    }
                                } finally {
                                    EtcdContext.this.lock.readLock().unlock();
                                }
                                break OUTER;
                            default:
                                break;
                        }
                    }
                } catch (Exception e) {
                    EtcdContext.LOGGER.error("Export service watcher failed for " + serial.toString() + ", message=" + e.getMessage(), e);
                }
            }
            EtcdContext.LOGGER.info("Export service watcher thread shutdown for " + serial.toString());
        }
    }

    private class ImportServiceWatcher implements Runnable {

        private Service service;

        private long revision;

        ImportServiceWatcher(Service service, long revision) {
            this.service = service;
            this.revision = revision;
        }

        @Override
        public void run() {
            Watch.Watcher watcher = EtcdContext.this.etcdClient.watch(prefix(service), revision);
            while (!EtcdContext.this.shutdown) {
                try {
                    for (WatchEvent watchEvent : watcher.listen().getEvents()) {
                        KeyValue current = watchEvent.getKeyValue();
                        KeyValue prev = watchEvent.getPrevKV();
                        switch (watchEvent.getEventType()) {
                            case PUT:
                                if (prev.getKey().getBytes().length == 0) {
                                    EtcdContext.this.listener.add(deSerialize(current.getValue().getBytes()));
                                } else {
                                    EtcdContext.this.listener.change(deSerialize(prev.getValue().getBytes()), deSerialize(current.getValue().getBytes()));
                                }
                                break;
                            case DELETE:
                                EtcdContext.this.listener.delete(deSerialize(prev.getValue().getBytes()));
                                break;
                            default:
                                break;
                        }
                    }
                } catch (Exception e) {
                    EtcdContext.LOGGER.error("Import service watcher failed for " + service.toString() + ", message=" + e.getMessage(), e);
                }
            }
            EtcdContext.LOGGER.info("Import service watcher thread shutdown for " + service.toString());
        }
    }

    private class KeepAlive implements Runnable {

        private volatile boolean running = false;

        @Override
        public void run() {
            if (this.running) {
                return;
            }
            try {
                this.running = true;
                if (EtcdContext.this.etcdClient.leaseExpired()) {
                    etcdClient.leaseAndKeepAlive();
                    instances.keySet().forEach(key -> {
                        try {
                            export(key, instances.get(key), true);
                        } catch (Exception e) {
                            EtcdContext.LOGGER.error(e.getMessage(), e);
                        }
                    });
                } else {
                    retry.keySet().forEach(key -> {
                        try {
                            if (export(key, retry.get(key), true)) {
                                retry.remove(key);
                            }
                        } catch (Exception e) {
                            EtcdContext.LOGGER.error(e.getMessage(), e);
                        }
                    });
                }
            } catch (Exception e) {
                EtcdContext.LOGGER.error(e.getMessage(), e);
            } finally {
                this.running = false;
            }
        }
    }
}
