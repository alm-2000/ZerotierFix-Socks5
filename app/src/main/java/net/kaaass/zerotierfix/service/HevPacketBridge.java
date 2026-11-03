package net.kaaass.zerotierfix.service;

import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.FileDescriptor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import hev.htproxy.TProxyService;

/** Owns the app end of HEV's packet-oriented external-fd bridge. */
final class HevPacketBridge {
    private static final String TAG = "HEV_Bridge";
    private static final int QUEUE_CAPACITY = 256;

    interface PacketSink {
        void onPacket(byte[] packet);
    }

    private final Context context;
    private final PacketSink sink;
    private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private ParcelFileDescriptor hevFd;
    private ParcelFileDescriptor appFd;
    private FileInputStream input;
    private FileOutputStream output;
    private Thread writerThread;
    private Thread readerThread;
    private Thread healthThread;
    private volatile boolean running;
    private volatile boolean failed;
    private boolean nativeStarted;

    HevPacketBridge(Context context, PacketSink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
    }

    synchronized void start(String host, int port, int mtu) throws IOException {
        if (running) return;
        if (Build.VERSION.SDK_INT < 28) {
            throw new IOException("HEV bridge is verified only on Android API 28 or later");
        }
        FileDescriptor rawHev = new FileDescriptor();
        FileDescriptor rawApp = new FileDescriptor();
        try {
            Api21.socketpair(rawHev, rawApp);
            hevFd = ParcelFileDescriptor.dup(rawHev);
            appFd = ParcelFileDescriptor.dup(rawApp);
        } catch (Exception e) {
            closeRaw(rawHev);
            closeRaw(rawApp);
            closeDescriptors();
            throw new IOException("Unable to create HEV SOCK_SEQPACKET bridge", e);
        }
        closeRaw(rawHev);
        closeRaw(rawApp);
        input = new FileInputStream(appFd.getFileDescriptor());
        output = new FileOutputStream(appFd.getFileDescriptor());
        File config = new File(context.getFilesDir(), "hev-socks5.yml");
        String yaml = "tunnel:\n"
                + "  name: tun0\n"
                + "  mtu: " + mtu + "\n"
                + "  ipv4: 198.18.0.1\n"
                + "  icmp: 'reply'\n"
                + "socks5:\n"
                + "  address: '" + host.replace("'", "''") + "'\n"
                + "  port: " + port + "\n"
                + "  udp: 'tcp'\n";
        try (FileOutputStream stream = new FileOutputStream(config)) {
            stream.write(yaml.getBytes(StandardCharsets.UTF_8));
            if (!TProxyService.TProxyStartService(config.getAbsolutePath(), hevFd.getFd())) {
                closeDescriptors();
                throw new IOException("HEV rejected external bridge fd");
            }
            nativeStarted = true;
            long startDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < startDeadline && !TProxyService.TProxyIsRunning()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for HEV startup", e);
                }
            }
            if (!TProxyService.TProxyIsRunning()) throw new IOException("HEV did not enter running state");
        } catch (IOException | RuntimeException | LinkageError e) {
            stopNativeService();
            closeDescriptors();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("HEV startup failed", e);
        }
        running = true;
        writerThread = new Thread(this::writePackets, "HEV bridge writer");
        readerThread = new Thread(this::readPackets, "HEV bridge reader");
        healthThread = new Thread(this::monitorService, "HEV bridge health");
        writerThread.start();
        readerThread.start();
        healthThread.start();
        Log.i(TAG, "HEV_STARTED");
    }

    boolean isRunning() {
        return running && !failed;
    }

    boolean send(byte[] packet) {
        return isRunning() && outbound.offer(packet);
    }

    private void writePackets() {
        while (running) {
            try {
                byte[] packet = outbound.poll(250, TimeUnit.MILLISECONDS);
                if (packet != null) output.write(packet);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                fail("bridge packet write failed", e);
                return;
            }
        }
    }

    private void readPackets() {
        byte[] buffer = new byte[32767];
        while (running) {
            try {
                int length = input.read(buffer);
                if (length < 0) throw new IOException("bridge reached EOF");
                if (length > 0) {
                    byte[] packet = new byte[length];
                    System.arraycopy(buffer, 0, packet, 0, length);
                    sink.onPacket(packet);
                }
            } catch (IOException e) {
                if (running) fail("bridge packet read failed", e);
                return;
            }
        }
    }

    private void monitorService() {
        while (running) {
            try {
                Thread.sleep(1000);
                if (running && !TProxyService.TProxyIsRunning()) {
                    fail("HEV native service stopped", new IOException("HEV is no longer running"));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException | LinkageError e) {
                fail("HEV status check failed", new IOException("HEV status check failed", e));
                return;
            }
        }
    }

    private void fail(String message, IOException error) {
        if (failed) return;
        failed = true;
        running = false;
        Log.e(TAG, "HEV_BRIDGE_ERROR: " + message, error);
        new Thread(this::stop, "HEV bridge cleanup").start();
    }

    synchronized void stop() {
        boolean stopNative = nativeStarted;
        running = false;
        outbound.clear();
        if (stopNative) {
            Log.i(TAG, "HEV_STOP");
            stopNativeService();
        }
        closeDescriptors();
        join(writerThread);
        join(readerThread);
        join(healthThread);
        writerThread = null;
        readerThread = null;
        healthThread = null;
        failed = false;
        Log.i(TAG, "HEV_STOPPED");
    }

    private void stopNativeService() {
        if (!nativeStarted) return;
        nativeStarted = false;
        try {
            TProxyService.TProxyStopService();
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "HEV_STOP failed", e);
        }
    }

    private void closeDescriptors() {
        close(input);
        close(output);
        input = null;
        output = null;
        close(hevFd);
        close(appFd);
        hevFd = null;
        appFd = null;
    }

    private static void closeRaw(FileDescriptor descriptor) {
        try { if (descriptor.valid()) Api21.close(descriptor); } catch (Exception ignored) { }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) { }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        thread.interrupt();
        try {
            thread.join(2000);
            if (thread.isAlive()) Log.e(TAG, "HEV bridge worker did not stop: " + thread.getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while joining HEV bridge worker: " + thread.getName(), e);
        }
    }

    /** Keep API 21-only libcore references out of this class's verifier-visible methods. */
    private static final class Api21 {
        static void socketpair(FileDescriptor first, FileDescriptor second) throws Exception {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, first, second);
        }

        static void close(FileDescriptor descriptor) throws Exception {
            Os.close(descriptor);
        }
    }
}
