package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tecnico.depchain.depchain_common.links.AuthenticatedPerfectLink;
import tecnico.depchain.depchain_common.links.FairLossLink;
import tecnico.depchain.depchain_common.links.P2PLink;
import tecnico.depchain.depchain_common.links.StubbornLink;

/**
 * Step 3 — Links Layer Tests
 *
 * Tests the P2P link stack used by HotStuff for inter-replica communication:
 *   FairLossLink → StubbornLink → AuthenticatedPerfectLink
 */
@Timeout(15)
public class HotStuffStep3Test {

    private static final AtomicInteger PORT = new AtomicInteger(41000);
    private final List<P2PLink> toClose = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (P2PLink l : toClose) {
            try { l.close(); } catch (Exception ignored) {}
        }
        toClose.clear();
    }

    private int port() { return PORT.getAndIncrement(); }

    // ── FairLossLink ────────────────────────────────────────────────────

    @Test
    public void testFairLossLinkSendReceive() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        CountDownLatch latch = new CountDownLatch(1);
        byte[][] received = {null};

        FairLossLink linkA = new FairLossLink(null, addrA, addrB);
        FairLossLink linkB = new FairLossLink((data, remote) -> {
            received[0] = data;
            latch.countDown();
        }, addrB, addrA);
        toClose.add(linkA);
        toClose.add(linkB);

        linkA.transmit("hello".getBytes());
        assertTrue(latch.await(3, TimeUnit.SECONDS), "B should receive the message");
        assertArrayEquals("hello".getBytes(), received[0]);
    }

    @Test
    public void testFairLossLinkLargeMessage() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        byte[] payload = new byte[8000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i % 256);

        CountDownLatch latch = new CountDownLatch(1);
        byte[][] received = {null};

        FairLossLink linkA = new FairLossLink(null, addrA, addrB);
        FairLossLink linkB = new FairLossLink((data, remote) -> {
            received[0] = data;
            latch.countDown();
        }, addrB, addrA);
        toClose.add(linkA);
        toClose.add(linkB);

        linkA.transmit(payload);
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Should handle large messages");
        assertArrayEquals(payload, received[0]);
    }

    // ── StubbornLink ────────────────────────────────────────────────────

    @Test
    public void testStubbornLinkReliableDelivery() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        CountDownLatch latch = new CountDownLatch(1);
        byte[][] received = {null};

        StubbornLink linkA = new StubbornLink(null, addrA, addrB);
        StubbornLink linkB = new StubbornLink((data, remote) -> {
            received[0] = data;
            latch.countDown();
        }, addrB, addrA);
        toClose.add(linkA);
        toClose.add(linkB);

        linkA.transmit("stubborn-msg".getBytes());
        assertTrue(latch.await(5, TimeUnit.SECONDS), "StubbornLink should deliver reliably");
        assertArrayEquals("stubborn-msg".getBytes(), received[0]);
    }

    @Test
    public void testStubbornLinkMultipleMessages() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        int count = 5;
        CountDownLatch latch = new CountDownLatch(count);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        StubbornLink linkA = new StubbornLink(null, addrA, addrB);
        StubbornLink linkB = new StubbornLink((data, remote) -> {
            received.add(new String(data));
            latch.countDown();
        }, addrB, addrA);
        toClose.add(linkA);
        toClose.add(linkB);

        for (int i = 0; i < count; i++) {
            linkA.transmit(("msg-" + i).getBytes());
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "All " + count + " messages should be delivered, got " + received.size());
        assertEquals(count, received.size());
    }

    @Test
    public void testStubbornLinkRejectsEmptyMessage() throws Exception {
        int pA = port(), pB = port();
        StubbornLink link = new StubbornLink(null,
                new InetSocketAddress("127.0.0.1", pA),
                new InetSocketAddress("127.0.0.1", pB));
        toClose.add(link);

        assertThrows(IllegalArgumentException.class, () -> link.transmit(new byte[0]),
                "StubbornLink must reject empty messages");
    }

    // ── AuthenticatedPerfectLink ────────────────────────────────────────

    @Test
    public void testAuthPerfectLinkDelivery() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyA = kpg.generateKeyPair();
        KeyPair keyB = kpg.generateKeyPair();

        CountDownLatch latch = new CountDownLatch(1);
        byte[][] received = {null};

        AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(
                null, addrA, addrB, keyA.getPrivate(), keyB.getPublic());
        AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(
                (data, remote) -> { received[0] = data; latch.countDown(); },
                addrB, addrA, keyB.getPrivate(), keyA.getPublic());
        toClose.add(linkA);
        toClose.add(linkB);

        linkA.transmit("authenticated".getBytes());
        assertTrue(latch.await(5, TimeUnit.SECONDS), "APL should deliver authenticated message");
        assertArrayEquals("authenticated".getBytes(), received[0]);
    }

    @Test
    public void testAuthPerfectLinkRejectsWrongKey() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyA = kpg.generateKeyPair();
        KeyPair keyB = kpg.generateKeyPair();
        KeyPair keyC = kpg.generateKeyPair(); // wrong key

        AtomicInteger deliveryCount = new AtomicInteger(0);

        // B signs with keyB, but A expects messages signed with keyC (wrong)
        AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(
                (data, remote) -> deliveryCount.incrementAndGet(),
                addrA, addrB, keyA.getPrivate(), keyC.getPublic()); // wrong remote key
        AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(
                null, addrB, addrA, keyB.getPrivate(), keyA.getPublic());
        toClose.add(linkA);
        toClose.add(linkB);

        // B sends to A — A should reject because it verifies with keyC, not keyB
        linkB.transmit("spoofed".getBytes());
        Thread.sleep(2000);
        assertEquals(0, deliveryCount.get(),
                "APL should reject messages signed with wrong key");
    }

    @Test
    public void testAuthPerfectLinkMultipleMessagesAllDelivered() throws Exception {
        int pA = port(), pB = port();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", pA);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", pB);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyA = kpg.generateKeyPair();
        KeyPair keyB = kpg.generateKeyPair();

        int count = 5;
        CountDownLatch latch = new CountDownLatch(count);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(
                null, addrA, addrB, keyA.getPrivate(), keyB.getPublic());
        AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(
                (data, remote) -> { received.add(new String(data)); latch.countDown(); },
                addrB, addrA, keyB.getPrivate(), keyA.getPublic());
        toClose.add(linkA);
        toClose.add(linkB);

        for (int i = 0; i < count; i++) {
            linkA.transmit(("apl-" + i).getBytes());
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "All APL messages should be delivered, got " + received.size());
        assertEquals(count, received.size());
    }
}
