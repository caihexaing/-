package cn.quietguard.app;

import android.net.VpnService;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local direct-connect SOCKS5 endpoint used by the optional SNI tunnel. */
final class EnhancedSocks5Proxy implements AutoCloseable {
    private static final int MAX_CLIENTS = 96, MAX_TLS_HELLO = 65539;
    private final VpnService vpn;
    private final RuleSet rules;
    private final InetAddress resolver;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final Semaphore permits = new Semaphore(MAX_CLIENTS);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<DatagramSocket> datagrams = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 64, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "quietguard-socks-worker"); t.setDaemon(true); return t; });
    private final ServerSocket server;
    private final int port;

    EnhancedSocks5Proxy(VpnService vpn, RuleSet rules, String resolverAddress) throws Exception {
        this.vpn = vpn; this.rules = rules; resolver = InetAddress.getByName(resolverAddress);
        server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")); port = server.getLocalPort();
        Thread accept = new Thread(this::acceptLoop, "quietguard-socks-accept"); accept.setDaemon(true); accept.start();
    }
    int port() { return port; }

    private void acceptLoop() {
        while (alive.get()) {
            try {
                Socket client = server.accept(); client.setTcpNoDelay(true); sockets.add(client);
                try { workers.execute(() -> handle(client)); }
                catch (RejectedExecutionException e) { closeSocket(client); }
            } catch (IOException e) { if (alive.get()) FilterVpnService.failed.incrementAndGet(); }
        }
    }

    private void handle(Socket client) {
        boolean admitted = permits.tryAcquire();
        try {
            if (!admitted) return;
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            int ver = readByte(in), count = readByte(in);
            if (ver != 5 || count <= 0 || count > 32) return;
            byte[] methods = readExact(in, count); boolean noAuth = false;
            for (byte method : methods) if ((method & 255) == 0) noAuth = true;
            out.write(new byte[]{5, (byte)(noAuth ? 0 : 255)}); out.flush();
            if (!noAuth) return;
            ver = readByte(in); int command = readByte(in); readByte(in); int atyp = readByte(in);
            if (ver != 5) return;
            if (command == 3) { udpAssociate(client, in, out, atyp); return; }
            if (command != 1) { socksReply(out, 7); return; }
            String host = readHost(in, atyp); int targetPort = readByte(in) << 8 | readByte(in);
            if (host == null || targetPort <= 0) { socksReply(out, 8); return; }
            socksReply(out, 0);
            if (targetPort == 53) { dnsTcp(client, host); return; }
            byte[] prefix = targetPort == 443 ? readTlsPrefix(client) : new byte[0];
            if (prefix == null) return;
            if (targetPort == 443) {
                SniClientHello.Result hello = SniClientHello.inspect(prefix, prefix.length);
                if (hello.state == SniClientHello.FOUND_SNI && rules.blocks(hello.host)) {
                    FilterVpnService.sniBlocked.incrementAndGet(); return;
                }
            }
            try (Socket upstream = new Socket()) {
                sockets.add(upstream);
                if (!vpn.protect(upstream)) throw new IOException("protect() rejected upstream socket");
                upstream.connect(new InetSocketAddress(host, targetPort), 10000); upstream.setTcpNoDelay(true);
                if (prefix.length > 0) { upstream.getOutputStream().write(prefix); upstream.getOutputStream().flush(); }
                relay(client, upstream);
            } finally { sockets.removeIf(s -> s != client && s.isClosed()); }
        } catch (Exception e) { if (alive.get()) FilterVpnService.failed.incrementAndGet(); }
        finally { if (admitted) permits.release(); closeSocket(client); }
    }

    private byte[] readTlsPrefix(Socket client) throws IOException {
        byte[] buffer = new byte[MAX_TLS_HELLO]; int used = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        InputStream in = client.getInputStream();
        while (used < buffer.length && alive.get() && System.nanoTime() < deadline) {
            client.setSoTimeout(750);
            int n;
            try { n = in.read(buffer, used, Math.min(4096, buffer.length - used)); }
            catch (SocketTimeoutException e) { continue; }
            if (n < 0) break;
            used += n;
            SniClientHello.Result result = SniClientHello.inspect(buffer, used);
            if (result.state != SniClientHello.NEED_MORE) break;
        }
        client.setSoTimeout(0);
        return Arrays.copyOf(buffer, used);
    }

    private void dnsTcp(Socket client, String requestedHost) throws IOException {
        try (Socket upstream = new Socket()) {
            sockets.add(upstream);
            if (!vpn.protect(upstream)) throw new IOException("protect() rejected DNS socket");
            upstream.connect(new InetSocketAddress(resolver, 53), 5000); upstream.setSoTimeout(3500);
            DataInputStream clientIn = new DataInputStream(client.getInputStream());
            DataInputStream dnsIn = new DataInputStream(upstream.getInputStream());
            DataOutputStream clientOut = new DataOutputStream(client.getOutputStream());
            DataOutputStream dnsOut = new DataOutputStream(upstream.getOutputStream());
            while (alive.get()) {
                int size;
                try { size = clientIn.readUnsignedShort(); } catch (EOFException e) { break; }
                if (size < 12 || size > 4096) throw new IOException("Invalid DNS/TCP frame");
                byte[] query = new byte[size]; clientIn.readFully(query);
                String domain = DnsPacket.domain(query); if (domain == null) { FilterVpnService.failed.incrementAndGet(); continue; }
                FilterVpnService.queries.incrementAndGet();
                byte[] answer;
                if (rules.blocks(domain)) { FilterVpnService.blocked.incrementAndGet(); answer = DnsPacket.error(query, 3); }
                else {
                    dnsOut.writeShort(query.length); dnsOut.write(query); dnsOut.flush();
                    int responseSize = dnsIn.readUnsignedShort(); if (responseSize < 12 || responseSize > 4096) throw new IOException("Invalid DNS/TCP response");
                    answer = new byte[responseSize]; dnsIn.readFully(answer);
                    if (!DnsPacket.validReply(query, answer)) throw new IOException("DNS/TCP response mismatch");
                }
                clientOut.writeShort(answer.length); clientOut.write(answer); clientOut.flush();
            }
        } finally { sockets.removeIf(s -> s.isClosed()); }
    }

    private void udpAssociate(Socket control, InputStream in, OutputStream out, int atyp) throws IOException {
        String ignored = readHost(in, atyp); readByte(in); readByte(in);
        DatagramSocket relay = new DatagramSocket(null);
        relay.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); datagrams.add(relay);
        byte[] portBytes = new byte[]{(byte)(relay.getLocalPort() >>> 8), (byte)relay.getLocalPort()};
        out.write(new byte[]{5,0,0,1,127,0,0,1,portBytes[0],portBytes[1]}); out.flush();
        control.setSoTimeout(500); relay.setSoTimeout(500);
        byte[] buffer = new byte[65535];
        try {
            while (alive.get() && !control.isClosed()) {
                try { if (control.getInputStream().read() < 0) break; }
                catch (SocketTimeoutException ignoredTimeout) { }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { relay.receive(packet); } catch (SocketTimeoutException ignoredTimeout) { continue; }
                UdpRequest request = UdpRequest.parse(packet.getData(), packet.getLength());
                if (request == null) { FilterVpnService.failed.incrementAndGet(); continue; }
                if (request.port == 443) { FilterVpnService.quicBlocked.incrementAndGet(); continue; }
                byte[] response = forwardUdp(request);
                if (response == null) continue;
                byte[] framed = request.frame(response);
                relay.send(new DatagramPacket(framed, framed.length, packet.getSocketAddress()));
            }
        } finally { datagrams.remove(relay); relay.close(); }
    }

    private byte[] forwardUdp(UdpRequest request) {
        boolean dns = request.port == 53;
        InetAddress target;
        try { target = dns ? resolver : InetAddress.getByName(request.host); }
        catch (Exception e) { FilterVpnService.failed.incrementAndGet(); return null; }
        if (dns) {
            String domain = DnsPacket.domain(request.payload);
            if (domain != null) {
                FilterVpnService.queries.incrementAndGet();
                if (rules.blocks(domain)) { FilterVpnService.blocked.incrementAndGet(); return DnsPacket.error(request.payload, 3); }
            }
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            datagrams.add(socket);
            if (!vpn.protect(socket)) throw new IOException("protect() rejected UDP socket");
            socket.setSoTimeout(3500); socket.connect(target, dns ? 53 : request.port);
            socket.send(new DatagramPacket(request.payload, request.payload.length));
            byte[] bytes = new byte[65535]; DatagramPacket answer = new DatagramPacket(bytes, bytes.length); socket.receive(answer);
            byte[] result = Arrays.copyOf(bytes, answer.getLength());
            if (dns && DnsPacket.domain(request.payload) != null && !DnsPacket.validReply(request.payload, result))
                throw new IOException("DNS/UDP response mismatch");
            return result;
        } catch (Exception e) { if (alive.get()) FilterVpnService.failed.incrementAndGet(); return null; }
        finally { datagrams.removeIf(DatagramSocket::isClosed); }
    }

    private static final class UdpRequest {
        final byte[] header, payload; final String host; final int port;
        UdpRequest(byte[] header, byte[] payload, String host, int port) { this.header=header; this.payload=payload; this.host=host; this.port=port; }
        static UdpRequest parse(byte[] b, int length) {
            if (length < 10 || b[0] != 0 || b[1] != 0 || b[2] != 0) return null;
            try {
                int p=4, type=b[3]&255; String host;
                if (type==1) { if(length<p+4+2)return null; host=InetAddress.getByAddress(Arrays.copyOfRange(b,p,p+4)).getHostAddress(); p+=4; }
                else if(type==4) { if(length<p+16+2)return null; host=InetAddress.getByAddress(Arrays.copyOfRange(b,p,p+16)).getHostAddress(); p+=16; }
                else if(type==3) { if(length<=p)return null; int n=b[p++]&255; if(length<p+n+2)return null; host=new String(b,p,n,java.nio.charset.StandardCharsets.US_ASCII); p+=n; }
                else return null;
                int port=((b[p]&255)<<8)|(b[p+1]&255); p+=2;
                if (p>=length) return null;
                return new UdpRequest(Arrays.copyOf(b,p),Arrays.copyOfRange(b,p,length),host,port);
            } catch (Exception e) { return null; }
        }
        byte[] frame(byte[] data) { byte[] out=Arrays.copyOf(header,header.length+data.length); System.arraycopy(data,0,out,header.length,data.length); return out; }
    }

    private static void relay(Socket client, Socket upstream) throws Exception {
        Thread back = new Thread(null, () -> copy(upstream, client), "quietguard-socks-down", 64*1024); back.setDaemon(true); back.start();
        copy(client, upstream); back.join(15000);
    }
    private static void copy(Socket from, Socket to) {
        try {
            byte[] b=new byte[16384]; InputStream in=from.getInputStream(); OutputStream out=to.getOutputStream(); int n;
            while((n=in.read(b))>=0) { out.write(b,0,n); out.flush(); }
        } catch (IOException ignored) { }
        finally { try { to.shutdownOutput(); } catch(IOException ignored) { } }
    }
    private static int readByte(InputStream in) throws IOException { int n=in.read(); if(n<0) throw new EOFException(); return n; }
    private static byte[] readExact(InputStream in,int length) throws IOException { byte[] b=new byte[length]; new DataInputStream(in).readFully(b); return b; }
    private static String readHost(InputStream in,int type) throws IOException {
        if(type==1) return InetAddress.getByAddress(readExact(in,4)).getHostAddress();
        if(type==4) return InetAddress.getByAddress(readExact(in,16)).getHostAddress();
        if(type==3) { int n=readByte(in); if(n==0)return null; return new String(readExact(in,n),java.nio.charset.StandardCharsets.US_ASCII); }
        return null;
    }
    private static void socksReply(OutputStream out,int code) throws IOException { out.write(new byte[]{5,(byte)code,0,1,0,0,0,0,0,0}); out.flush(); }
    private void closeSocket(Socket socket) { sockets.remove(socket); try { socket.close(); } catch(IOException ignored) { } }
    @Override public void close() {
        if(!alive.compareAndSet(true,false))return;
        try { server.close(); } catch(IOException ignored) { }
        for(Socket socket:sockets) closeSocket(socket);
        for(DatagramSocket socket:datagrams) socket.close(); datagrams.clear(); workers.shutdownNow();
    }
}
