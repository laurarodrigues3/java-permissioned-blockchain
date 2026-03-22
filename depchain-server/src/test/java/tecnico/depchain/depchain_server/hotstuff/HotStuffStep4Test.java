package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Step 4 tests: crash faults and timeout-based failure detection.
 */
public class HotStuffStep4Test {

	private static final String HOST = "127.0.0.1";
	private static final int BASE_PORT = 30000;

	private HotStuff[] createReplicas(int n, int basePort) throws Exception {
		List<KeyPair> keyPairs = TestKeyHelper.readKeysFromTestConfig(n);
		List<PublicKey> publicKeys = TestKeyHelper.extractPublicKeys(keyPairs);

		HotStuff[] replicas = new HotStuff[n];
		for (int i = 0; i < n; i++) {
			CryptoService crypto = new CryptoService(i, keyPairs.get(i), publicKeys);
			replicas[i] = new HotStuff(i, HOST, basePort, n, keyPairs.get(i).getPrivate(), publicKeys, crypto);
		}
		return replicas;
	}

	private void stopAll(HotStuff[] replicas) {
		for (HotStuff r : replicas) {
			if (r != null) {
				try { r.stop(); } catch (Exception e) { /* best-effort */ }
			}
		}
		try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
	}

	@Test
	void testLeaderCrashSkipsView() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			int numLive = n - 1;
			CountDownLatch latch = new CountDownLatch(numLive);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(replicaDecisions);
				final int idx = i;
				replicas[i].setOnDecide(cmd -> {
					replicaDecisions.add(cmd);
					if (idx != 1) latch.countDown();
				});
			}

			for (int i = 0; i < n; i++) {
				if (i != 1) replicas[i].start();
			}
			Thread.sleep(200);

			replicas[2].propose("SurviveLeaderCrash");

			assertTrue(latch.await(20, TimeUnit.SECONDS),
					"Live replicas should eventually decide after leader crash and view change");

			for (int i = 0; i < n; i++) {
				if (i == 1) continue;
				assertEquals(1, decisions.get(i).size(),
						"Replica " + i + " should have 1 decision");
				assertEquals("SurviveLeaderCrash", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testNonLeaderCrashStillReachesConsensus() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 100);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(3000);

			int crashedReplica = 0;
			int numLive = n - 1;

			CountDownLatch latch = new CountDownLatch(numLive);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(replicaDecisions);
				final int idx = i;
				replicas[i].setOnDecide(cmd -> {
					replicaDecisions.add(cmd);
					if (idx != crashedReplica) latch.countDown();
				});
			}

			for (int i = 0; i < n; i++) {
				if (i != crashedReplica) replicas[i].start();
			}
			Thread.sleep(200);

			replicas[1].propose("WithoutReplica0");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"3 out of 4 replicas should still reach consensus (quorum=3)");

			for (int i = 0; i < n; i++) {
				if (i == crashedReplica) continue;
				assertEquals(1, decisions.get(i).size(),
						"Replica " + i + " should have 1 decision");
				assertEquals("WithoutReplica0", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testTimeoutAdvancesViewThenDecides() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 200);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(1000);

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

			Thread.sleep(4000);

			for (HotStuff r : replicas)
				r.propose("DelayedCommand");

			assertTrue(latch.await(30, TimeUnit.SECONDS),
					"All replicas should eventually decide after views timeout and a command is proposed");

			for (int i = 0; i < n; i++) {
				assertTrue(decisions.get(i).size() >= 1,
						"Replica " + i + " should have at least 1 decision");
				assertEquals("DelayedCommand", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testMultipleDecisionsWithOneCrashedReplica() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 300);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			int crashedReplica = 3;
			int numLive = n - 1;
			int numDecisions = 3;

			CountDownLatch latch = new CountDownLatch(numLive * numDecisions);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(replicaDecisions);
				final int idx = i;
				replicas[i].setOnDecide(cmd -> {
					replicaDecisions.add(cmd);
					if (idx != crashedReplica) latch.countDown();
				});
			}

			for (int i = 0; i < n; i++) {
				if (i != crashedReplica) replicas[i].start();
			}
			Thread.sleep(200);

			for (int cmd = 1; cmd <= numDecisions; cmd++) {
				for (int i = 0; i < n; i++) {
					if (i != crashedReplica)
						replicas[i].propose("MultiCmd-" + cmd);
				}
				Thread.sleep(3000);
			}

			assertTrue(latch.await(60, TimeUnit.SECONDS),
					"Live replicas should decide all commands despite one crashed replica");

			for (int i = 0; i < n; i++) {
				if (i == crashedReplica) continue;
				assertTrue(decisions.get(i).size() >= numDecisions,
						"Replica " + i + " should have at least " + numDecisions + " decisions, got "
								+ decisions.get(i).size());
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testViewAdvancesOnTimeout() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 400);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(500);

			for (HotStuff r : replicas)
				r.start();

			Thread.sleep(4000);

			for (int i = 0; i < n; i++) {
				assertTrue(replicas[i].getCurrentView() > 1,
						"Replica " + i + " should have advanced past view 1 via timeouts, but is at view "
								+ replicas[i].getCurrentView());
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testTwoCrashesCannotReachConsensus() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicas(n, BASE_PORT + 500);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(1000);

			int crashedReplica1 = 2;
			int crashedReplica2 = 3;

			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> replicaDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(replicaDecisions);
				replicas[i].setOnDecide(cmd -> replicaDecisions.add(cmd));
			}

			// Only start the honest, non-crashed replicas
			for (int i = 0; i < n; i++) {
				if (i != crashedReplica1 && i != crashedReplica2) {
					replicas[i].start();
				}
			}
			Thread.sleep(200);

			for (int i = 0; i < n; i++) {
				if (i != crashedReplica1 && i != crashedReplica2) {
					replicas[i].propose("TwoCrashes");
				}
			}

			// Wait a reasonable amount of time to ensure they don't decide
			Thread.sleep(8000);

			// n=4, f=1. Quorum is n-f=3. With 2 crashes, only 2 remain, so they shouldn't reach consensus
			for (int i = 0; i < n; i++) {
				if (i == crashedReplica1 || i == crashedReplica2) continue;
				assertEquals(0, decisions.get(i).size(),
						"Honest replica " + i + " should NOT have decided with 2 crashed replicas (insufficient for quorum)");
			}
		} finally {
			stopAll(replicas);
		}
	}
}
