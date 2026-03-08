package tecnico.depchain.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

public class HotStuffTest {

	private static final String HOST = "127.0.0.1";
	private static final int BASE_PORT = 20000;

	/** Generate a fresh HMAC-SHA256 key */
	private static SecretKey generateKey() throws Exception {
		KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256");
		return kg.generateKey();
	}

	/**
	 * Create n replicas. Each replica i has a unique outgoing key keys[i].
	 * All replicas share the same key list so they can verify each other.
	 */
	private HotStuff[] createReplicas(int n, int basePort) throws Exception {
		// Generate one key per replica (shared by all)
		List<SecretKey> keys = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			keys.add(generateKey());
		}

		HotStuff[] replicas = new HotStuff[n];
		for (int i = 0; i < n; i++) {
			replicas[i] = new HotStuff(i, HOST, basePort, n, new ArrayList<>(keys));
		}
		return replicas;
	}

	@Test
	void testSingleDecision() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT);

		// Track decisions
		CountDownLatch latch = new CountDownLatch(n);
		List<List<String>> decisions = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
			decisions.add(replicaDecisions);
			replicas[i].setOnDecide(cmd -> {
				replicaDecisions.add(cmd);
				latch.countDown();
			});
		}

		// Start all replicas
		for (HotStuff r : replicas) r.start();

		// Give replicas time to initialize
		Thread.sleep(200);

		// View 1: leader is replica (1 % 4) = 1
		int leader = 1 % n;
		replicas[leader].propose("Hello Blockchain");

		// Wait for all replicas to decide
		assertTrue(latch.await(10, TimeUnit.SECONDS),
			"All replicas should decide within timeout");

		// Verify all decided the same thing
		for (int i = 0; i < n; i++) {
			assertEquals(1, decisions.get(i).size(),
				"Replica " + i + " should have exactly 1 decision");
			assertEquals("Hello Blockchain", decisions.get(i).get(0),
				"Replica " + i + " should have decided 'Hello Blockchain'");
		}

		// Cleanup
		for (HotStuff r : replicas) r.stop();
	}

	@Test
	void testMultipleDecisions() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 100);

		int numDecisions = 3;
		CountDownLatch latch = new CountDownLatch(n * numDecisions);
		List<List<String>> decisions = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
			decisions.add(replicaDecisions);
			replicas[i].setOnDecide(cmd -> {
				replicaDecisions.add(cmd);
				latch.countDown();
			});
		}

		// Start all replicas
		for (HotStuff r : replicas) r.start();
		Thread.sleep(200);

		// Propose commands across different views/leaders
		for (int v = 1; v <= numDecisions; v++) {
			int leader = v % n;
			replicas[leader].propose("Command-" + v);
			// Small delay between proposals to let the previous view finish
			Thread.sleep(500);
		}

		// Wait for all decisions
		assertTrue(latch.await(30, TimeUnit.SECONDS),
			"All replicas should decide all commands within timeout");

		// Verify all replicas have the same decisions in the same order
		for (int i = 0; i < n; i++) {
			assertEquals(numDecisions, decisions.get(i).size(),
				"Replica " + i + " should have " + numDecisions + " decisions");
		}

		// All replicas should agree on the sequence
		for (int d = 0; d < numDecisions; d++) {
			String expected = decisions.get(0).get(d);
			for (int i = 1; i < n; i++) {
				assertEquals(expected, decisions.get(i).get(d),
					"Replica " + i + " should agree with replica 0 on decision " + d);
			}
		}

		for (HotStuff r : replicas) r.stop();
	}
	@Test
	void testFastConcurrentProposals() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 200);

		int numDecisions = 5;
		CountDownLatch latch = new CountDownLatch(n * numDecisions);
		List<List<String>> decisions = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
			decisions.add(replicaDecisions);
			replicas[i].setOnDecide(cmd -> {
				replicaDecisions.add(cmd);
				latch.countDown();
			});
		}

		// Start all replicas
		for (HotStuff r : replicas) r.start();
		Thread.sleep(200);

		// Propose commands as fast as possible (no thread sleep between proposes)
		// Simulates high load / rapid client submissions
		for (int v = 1; v <= numDecisions; v++) {
			int leader = v % n;
			replicas[leader].propose("FastCmd-" + v);
		}

		// Wait for all decisions
		assertTrue(latch.await(30, TimeUnit.SECONDS),
			"All replicas should decide all fast commands within timeout. Concurrency bottlenecks detected.");

		// Verify all replicas have the same decisions in the same order
		for (int i = 0; i < n; i++) {
			assertEquals(numDecisions, decisions.get(i).size(),
				"Replica " + i + " should have " + numDecisions + " decisions");
		}

		// Ensure order consistency across replicas
		for (int d = 0; d < numDecisions; d++) {
			String expected = decisions.get(0).get(d);
			for (int i = 1; i < n; i++) {
				assertEquals(expected, decisions.get(i).get(d),
					"Consistency breach: Replica " + i + " disagrees with replica 0 on decision " + d);
			}
		}

		for (HotStuff r : replicas) r.stop();
	}
}
