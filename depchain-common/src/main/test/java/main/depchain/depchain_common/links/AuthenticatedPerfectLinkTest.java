package main.depchain.depchain_common.links;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_common.links.AuthenticatedPerfectLink;

public class AuthenticatedPerfectLinkTest {

	private static final String HOST = "127.0.0.1";

	/** Generate a fresh Ed25519 key pair */
	private static KeyPair generateKeyPair() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
		return kpg.generateKeyPair();
	}

	@Test
	void testSingleMessageDelivery() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18001);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18002);

		KeyPair kpA = generateKeyPair();
		KeyPair kpB = generateKeyPair();

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		// A signs with its private key, B verifies with A's public key
		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink((data, remote) -> {}, addrA, addrB, kpA.getPrivate(), kpB.getPublic());
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink((data, remote) -> {
			received.set(data);
			latch.countDown();
		}, addrB, addrA, kpB.getPrivate(), kpA.getPublic());

		byte[] message = "Hello authenticated world".getBytes();
		linkA.transmit(message);

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Message should be delivered");
		assertArrayEquals(message, received.get());
	}

	@Test
	void testMultipleMessagesDelivered() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18003);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18004);

		KeyPair kpA = generateKeyPair();
		KeyPair kpB = generateKeyPair();

		int messageCount = 5;
		CountDownLatch latch = new CountDownLatch(messageCount);
		java.util.Set<String> receivedMessages = java.util.concurrent.ConcurrentHashMap.newKeySet();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink((data, remote) -> {}, addrA, addrB, kpA.getPrivate(), kpB.getPublic());
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink((data, remote) -> {
			receivedMessages.add(new String(data));
			latch.countDown();
		}, addrB, addrA, kpB.getPrivate(), kpA.getPublic());


		for (int i = 0; i < messageCount; i++) {
			linkA.transmit(("Msg " + i).getBytes());
		}

		assertTrue(latch.await(10, TimeUnit.SECONDS), "All messages should be delivered");

		for (int i = 0; i < messageCount; i++) {
			assertTrue(receivedMessages.contains("Msg " + i),
					"Should have received 'Msg " + i + "'");
		}
	}

	@Test
	void testBidirectionalCommunication() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18005);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18006);

		KeyPair kpA = generateKeyPair();
		KeyPair kpB = generateKeyPair();

		CountDownLatch latchA = new CountDownLatch(1);
		CountDownLatch latchB = new CountDownLatch(1);
		AtomicReference<byte[]> receivedByA = new AtomicReference<>();
		AtomicReference<byte[]> receivedByB = new AtomicReference<>();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink((data, remote) -> {
			receivedByA.set(data);
			latchA.countDown();
		}, addrA, addrB, kpA.getPrivate(), kpB.getPublic());
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink((data, remote) -> {
			receivedByB.set(data);
			latchB.countDown();
		}, addrB, addrA, kpB.getPrivate(), kpA.getPublic());

		linkA.transmit("A says hi".getBytes());
		linkB.transmit("B says hi".getBytes());

		assertTrue(latchB.await(5, TimeUnit.SECONDS), "B should receive from A");
		assertTrue(latchA.await(5, TimeUnit.SECONDS), "A should receive from B");
		assertArrayEquals("A says hi".getBytes(), receivedByB.get());
		assertArrayEquals("B says hi".getBytes(), receivedByA.get());
	}

	@Test
	void testWrongKeyRejected() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18007);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18008);

		KeyPair kpA = generateKeyPair();
		KeyPair kpB = generateKeyPair();
		KeyPair kpWrong = generateKeyPair(); // B uses wrong public key to verify

		CountDownLatch latch = new CountDownLatch(1);

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink((data, remote) -> {}, addrA, addrB, kpA.getPrivate(), kpB.getPublic());
		// B verifies with wrong public key instead of A's → should reject all from A
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink((data, remote) -> {
			latch.countDown(); // Should NOT be called
		}, addrB, addrA, kpB.getPrivate(), kpWrong.getPublic());

		linkA.transmit("This should be rejected".getBytes());

		// Wait a bit and verify nothing was delivered
		assertFalse(latch.await(3, TimeUnit.SECONDS),
				"Message with wrong key should be silently rejected");
	}

	@Test
	void testDeduplication() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18009);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18010);

		KeyPair kpA = generateKeyPair();
		KeyPair kpB = generateKeyPair();

		AtomicInteger deliveryCount = new AtomicInteger(0);
		CountDownLatch firstDelivery = new CountDownLatch(1);

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink((data, remote) -> {}, addrA, addrB, kpA.getPrivate(), kpB.getPublic());
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink((data, remote) -> {
			deliveryCount.incrementAndGet();
			firstDelivery.countDown();
		}, addrB, addrA, kpB.getPrivate(), kpA.getPublic());

		// Send a single messagestubborn link will retransmit it multiple times
		// but AuthenticatedPerfectLink should deliver it exactly once
		linkA.transmit("Deliver me once".getBytes());

		assertTrue(firstDelivery.await(5, TimeUnit.SECONDS), "Message should be delivered");

		// Give time for potential duplicate deliveries
		Thread.sleep(1000);

		assertEquals(1, deliveryCount.get(),
				"Message should be delivered exactly once (deduplication)");
	}

	@Test
	void testMessageContentIntegrity() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18011);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18012);

		KeyPair kpA = generateKeyPair();
		KeyPair kpB = generateKeyPair();

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink((data, remote) -> {}, addrA, addrB, kpA.getPrivate(), kpB.getPublic());
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink((data, remote) -> {
			received.set(data);
			latch.countDown();
		}, addrB, addrA, kpB.getPrivate(), kpA.getPublic());

		// Send binary data to ensure no corruption through the layers
		byte[] binaryData = new byte[256];
		for (int i = 0; i < 256; i++) {
			binaryData[i] = (byte) i;
		}

		linkA.transmit(binaryData);

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Binary message should be delivered");
		assertArrayEquals(binaryData, received.get(),
				"Binary content should be preserved through all layers");
	}
}
