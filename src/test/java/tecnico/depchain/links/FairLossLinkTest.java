package tecnico.depchain.links;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

public class FairLossLinkTest {

	private static final String HOST = "127.0.0.1";

	@Test
	void testSingleMessageDelivery() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 16001);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 16002);

		FairLossLink linkA = new FairLossLink(addrA, addrB);
		FairLossLink linkB = new FairLossLink(addrB, addrA);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		linkB.rxHandler = (data, link) -> {
			received.set(data);
			latch.countDown();
		};

		byte[] message = "Hello from A".getBytes();
		linkA.Transmit(message);

		assertTrue(latch.await(2, TimeUnit.SECONDS), "Message should be received within timeout");
		assertArrayEquals(message, received.get());
	}

	@Test
	void testMultipleMessagesDelivery() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 16003);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 16004);

		FairLossLink linkA = new FairLossLink(addrA, addrB);
		FairLossLink linkB = new FairLossLink(addrB, addrA);

		int messageCount = 5;
		CountDownLatch latch = new CountDownLatch(messageCount);

		linkB.rxHandler = (data, link) -> {
			latch.countDown();
		};

		for (int i = 0; i < messageCount; i++) {
			linkA.Transmit(("Message " + i).getBytes());
		}

		assertTrue(latch.await(3, TimeUnit.SECONDS), "All messages should be received");
	}

	@Test
	void testBidirectionalCommunication() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 16005);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 16006);

		FairLossLink linkA = new FairLossLink(addrA, addrB);
		FairLossLink linkB = new FairLossLink(addrB, addrA);

		CountDownLatch latchA = new CountDownLatch(1);
		CountDownLatch latchB = new CountDownLatch(1);
		AtomicReference<byte[]> receivedByA = new AtomicReference<>();
		AtomicReference<byte[]> receivedByB = new AtomicReference<>();

		linkA.rxHandler = (data, link) -> {
			receivedByA.set(data);
			latchA.countDown();
		};
		linkB.rxHandler = (data, link) -> {
			receivedByB.set(data);
			latchB.countDown();
		};

		linkA.Transmit("A to B".getBytes());
		linkB.Transmit("B to A".getBytes());

		assertTrue(latchB.await(2, TimeUnit.SECONDS), "B should receive message from A");
		assertTrue(latchA.await(2, TimeUnit.SECONDS), "A should receive message from B");
		assertArrayEquals("A to B".getBytes(), receivedByB.get());
		assertArrayEquals("B to A".getBytes(), receivedByA.get());
	}

	@Test
	void testMessageContentPreserved() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 16007);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 16008);

		FairLossLink linkA = new FairLossLink(addrA, addrB);
		FairLossLink linkB = new FairLossLink(addrB, addrA);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		// Send binary data (not just text)
		byte[] binaryData = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x7F };

		linkB.rxHandler = (data, link) -> {
			received.set(data);
			latch.countDown();
		};

		linkA.Transmit(binaryData);

		assertTrue(latch.await(2, TimeUnit.SECONDS), "Binary message should be received");
		assertArrayEquals(binaryData, received.get());
	}
}
