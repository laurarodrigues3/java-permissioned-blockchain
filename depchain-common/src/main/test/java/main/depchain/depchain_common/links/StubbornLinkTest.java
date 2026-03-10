package main.depchain.depchain_common.links;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

public class StubbornLinkTest {

	private static final String HOST = "127.0.0.1";

	@Test
	void testSingleMessageDelivery() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 17001);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 17002);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		StubbornLink linkA = new StubbornLink(null, addrA, addrB);
		StubbornLink linkB = new StubbornLink((data, remote) -> {
			received.set(data);
			latch.countDown();
		}, addrB, addrA);

		byte[] message = "Stubborn hello".getBytes();
		linkA.transmit(message);

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Message should be delivered");
		assertArrayEquals(message, received.get());
	}

	@Test
	void testMultipleMessagesAllDelivered() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 17003);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 17004);

		int messageCount = 3;
		CountDownLatch latch = new CountDownLatch(messageCount);
		Set<String> receivedMessages = ConcurrentHashMap.newKeySet();

		StubbornLink linkA = new StubbornLink((data, remote) -> {}, addrA, addrB);
		StubbornLink linkB = new StubbornLink((data, remote) -> {
			receivedMessages.add(new String(data));
			latch.countDown();
		}, addrB, addrA);

		for (int i = 0; i < messageCount; i++) {
			linkA.transmit(("Message " + i).getBytes());
		}

		assertTrue(latch.await(10, TimeUnit.SECONDS), "All messages should be delivered");

		for (int i = 0; i < messageCount; i++) {
			assertTrue(receivedMessages.contains("Message " + i),
					"Should have received 'Message " + i + "'");
		}
	}

	@Test
	void testBidirectionalCommunication() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 17005);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 17006);

		CountDownLatch latchA = new CountDownLatch(1);
		CountDownLatch latchB = new CountDownLatch(1);
		AtomicReference<byte[]> receivedByA = new AtomicReference<>();
		AtomicReference<byte[]> receivedByB = new AtomicReference<>();

		StubbornLink linkA = new StubbornLink((data, remote) -> {
			receivedByA.set(data);
			latchA.countDown();
		}, addrA, addrB);
		StubbornLink linkB = new StubbornLink((data, remote) -> {
			receivedByB.set(data);
			latchB.countDown();
		}, addrB, addrA);

		linkA.transmit("From A".getBytes());
		linkB.transmit("From B".getBytes());

		assertTrue(latchB.await(5, TimeUnit.SECONDS), "B should receive from A");
		assertTrue(latchA.await(5, TimeUnit.SECONDS), "A should receive from B");
		assertArrayEquals("From A".getBytes(), receivedByB.get());
		assertArrayEquals("From B".getBytes(), receivedByA.get());
	}

	@Test
	void testEmptyMessageRejected() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 17007);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 17008);

		StubbornLink linkA = new StubbornLink((data, remote) -> {}, addrA, addrB);

		assertThrows(IllegalArgumentException.class, () -> {
			linkA.transmit(new byte[0]);
		});
	}

	@Test
	void testRetransmissionUntilAck() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 17009);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 17010);

		// Use raw UDP socket on B's port — this way no automatic ACKs are sent
		DatagramSocket rawB = new DatagramSocket(new InetSocketAddress(HOST, 17010));
		rawB.setSoTimeout(100);

		StubbornLink linkA = new StubbornLink((data, remote) -> {}, addrA, addrB);
		linkA.transmit("Retransmit me".getBytes());

		// Phase 1: Collect retransmissions WITHOUT sending ACK
		int retransmitCount = 0;
		byte[] rxBuffer = new byte[1024];
		byte[] msgId = null;

		long deadline = System.currentTimeMillis() + 1500;
		while (System.currentTimeMillis() < deadline) {
			DatagramPacket pkt = new DatagramPacket(rxBuffer, rxBuffer.length);
			try {
				rawB.receive(pkt);
				retransmitCount++;
				if (msgId == null) {
					// Extract the 8-byte message ID (first 8 bytes of stubborn payload)
					msgId = new byte[8];
					System.arraycopy(pkt.getData(), pkt.getOffset(), msgId, 0, 8);
				}
			} catch (SocketTimeoutException e) {
				// expected between retransmissions
			}
		}

		assertTrue(retransmitCount > 1,
				"Stubborn link should retransmit when no ACK is received, but got only " + retransmitCount + " transmission(s)");

		// Phase 2: Send ACK back to A (ACK = just the 8-byte message ID)
		DatagramPacket ackPkt = new DatagramPacket(msgId, 8, addrA);
		rawB.send(ackPkt);

		// Phase 3: Drain any in-flight retransmissions already in the network/buffer
		Thread.sleep(300);
		while (true) {
			try {
				rawB.receive(new DatagramPacket(rxBuffer, rxBuffer.length));
			} catch (SocketTimeoutException e) {
				break;
			}
		}

		// Phase 4: Verify retransmissions stopped
		int afterAckCount = 0;
		deadline = System.currentTimeMillis() + 1000;
		while (System.currentTimeMillis() < deadline) {
			try {
				rawB.receive(new DatagramPacket(rxBuffer, rxBuffer.length));
				afterAckCount++;
			} catch (SocketTimeoutException e) {
				// expected — no more messages
			}
		}

		rawB.close();

		assertEquals(0, afterAckCount,
				"Retransmission should stop after ACK, but received " + afterAckCount + " more message(s)");
	}
}
