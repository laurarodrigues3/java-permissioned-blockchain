package tecnico.depchain.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

/**
 * Step 3 tests: basic consensus without faults.
 */
public class HotStuffStep3Test {

	private static final String HOST = "127.0.0.1";
	private static final int BASE_PORT = 20000;

	private static SecretKey generateKey() throws Exception {
		KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256");
		return kg.generateKey();
	}

	private HotStuff[] createReplicas(int n, int basePort) throws Exception {
		List<SecretKey> keys = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			keys.add(generateKey());
		}

		List<KeyPair> keyPairs = CryptoService.generateKeyPairs(n);
		List<PublicKey> publicKeys = CryptoService.extractPublicKeys(keyPairs);

		HotStuff[] replicas = new HotStuff[n];
		for (int i = 0; i < n; i++) {
			CryptoService crypto = new CryptoService(i, keyPairs.get(i), publicKeys);
			replicas[i] = new HotStuff(i, HOST, basePort, n, new ArrayList<>(keys), crypto);
		}
		return replicas;
	}

	private void stopAll(HotStuff[] replicas) {
		for (HotStuff r : replicas) {
			if (r != null) {
				try { r.stop(); } catch (Exception e) { /* best-effort */ }
			}
		}
		try { Thread.sleep(500); } catch (InterruptedException ignored) {}
	}

	@Test
	void testSingleDecision() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT);

		try {
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

			for (HotStuff r : replicas)
				r.start();
			Thread.sleep(200);

			int leader = 1 % n;
			replicas[leader].propose("Hello Blockchain");

			assertTrue(latch.await(10, TimeUnit.SECONDS),
					"All replicas should decide within timeout");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size(),
						"Replica " + i + " should have exactly 1 decision");
				assertEquals("Hello Blockchain", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testMultipleDecisions() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 100);

		try {
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

			for (HotStuff r : replicas)
				r.start();
			Thread.sleep(200);

			for (int v = 1; v <= numDecisions; v++) {
				int leader = v % n;
				replicas[leader].propose("Command-" + v);
				Thread.sleep(500);
			}

			assertTrue(latch.await(30, TimeUnit.SECONDS),
					"All replicas should decide all commands within timeout");

			for (int i = 0; i < n; i++) {
				assertEquals(numDecisions, decisions.get(i).size(),
						"Replica " + i + " should have " + numDecisions + " decisions");
			}

			for (int d = 0; d < numDecisions; d++) {
				String expected = decisions.get(0).get(d);
				for (int i = 1; i < n; i++) {
					assertEquals(expected, decisions.get(i).get(d),
							"Replica " + i + " should agree with replica 0 on decision " + d);
				}
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testFastConcurrentProposals() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 200);

		try {
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

			for (HotStuff r : replicas)
				r.start();
			Thread.sleep(200);

			for (int v = 1; v <= numDecisions; v++) {
				int leader = v % n;
				replicas[leader].propose("FastCmd-" + v);
			}

			assertTrue(latch.await(30, TimeUnit.SECONDS),
					"All replicas should decide all fast commands within timeout");

			for (int i = 0; i < n; i++) {
				assertEquals(numDecisions, decisions.get(i).size(),
						"Replica " + i + " should have " + numDecisions + " decisions");
			}

			for (int d = 0; d < numDecisions; d++) {
				String expected = decisions.get(0).get(d);
				for (int i = 1; i < n; i++) {
					assertEquals(expected, decisions.get(i).get(d),
							"Consistency breach: Replica " + i + " disagrees with replica 0 on decision " + d);
				}
			}
		} finally {
			stopAll(replicas);
		}
	}
}
