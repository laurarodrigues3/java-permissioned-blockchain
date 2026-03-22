package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Step 6 tests: DepChainService integration (client request handling,
 * blockchain append-only array, upcall DECIDE, and idempotency).
 */
public class HotStuffStep6Test {

	private static final String HOST = "127.0.0.1";
	private static final int BASE_PORT = 50000;

	private DepChainService[] createServices(int n, int basePort) throws Exception {
		List<KeyPair> keyPairs = TestKeyHelper.readKeysFromTestConfig(n);
		List<PublicKey> publicKeys = TestKeyHelper.extractPublicKeys(keyPairs);

		int f = (n - 1) / 3;
		int threshold = 2 * f + 1;
		ThresholdCrypto.DealerParams dealerParams = ThresholdCrypto.generateParams(threshold, n);
		byte[] thresholdPublicKey = dealerParams.globalPublicKey;

		DepChainService[] services = new DepChainService[n];
		for (int i = 0; i < n; i++) {
			CryptoService crypto = new CryptoService(i, keyPairs.get(i), publicKeys, thresholdPublicKey);
			ThresholdCrypto tc = new ThresholdCrypto(i, threshold, n, dealerParams.pairingParamsStr,
					dealerParams.generator, dealerParams.globalPublicKey, dealerParams.privateShares.get(i),
					dealerParams.publicShares);
			services[i] = new DepChainService(i, HOST, basePort, n, keyPairs.get(i).getPrivate(), publicKeys, crypto, tc);
		}
		return services;
	}

	private void stopAll(DepChainService[] services) {
		for (DepChainService s : services) {
			if (s != null) {
				try { s.stop(); } catch (Exception e) { /* best-effort */ }
			}
		}
		try { Thread.sleep(500); } catch (InterruptedException ignored) {}
	}

	/**
	 * Submits a transaction to the current leader, retrying across view changes.
	 * Returns once all services have the transaction in their blockchain.
	 */
	private void submitAndWait(DepChainService[] services, String tx, int targetSize, int n) throws InterruptedException {
		long start = System.currentTimeMillis();
		int lastViewProposed = -1;

		while (System.currentTimeMillis() - start < 20000) {
			int currentView = services[0].getHotStuff().getCurrentView();
			if (currentView != lastViewProposed) {
				int leaderId = currentView % n;
				services[leaderId].handleClientRequest(tx);
				lastViewProposed = currentView;
			}
			boolean allReached = true;
			for (DepChainService s : services) {
				if (s.getBlockchain().size() < targetSize) {
					allReached = false;
					break;
				}
			}
			if (allReached) return;
			Thread.sleep(200);
		}
		fail("Failed to reach consensus for '" + tx + "' after 20s");
	}

	/**
	 * Basic blockchain append: 3 transactions are submitted sequentially
	 * and all replicas end up with the same append-only blockchain.
	 */
	@Test
	void testBlockchainAppendDecisions() throws Exception {
		int n = 4;
		DepChainService[] services = createServices(n, BASE_PORT);

		try {
			for (DepChainService s : services) s.start();
			Thread.sleep(2000);

			for (int c = 1; c <= 3; c++) {
				submitAndWait(services, "Tx" + c, c, n);
			}

			for (int i = 0; i < n; i++) {
				List<String> bc = services[i].getBlockchain();
				assertEquals(3, bc.size(), "Replica " + i + " should have 3 blocks");
				assertTrue(bc.contains("Tx1"), "Replica " + i + " missing Tx1");
				assertTrue(bc.contains("Tx2"), "Replica " + i + " missing Tx2");
				assertTrue(bc.contains("Tx3"), "Replica " + i + " missing Tx3");
			}
		} finally {
			stopAll(services);
		}
	}

	/**
	 * Idempotency: once a transaction is in the blockchain, duplicate
	 * submissions of the same string must be silently ignored and not
	 * appended again.
	 */
	@Test
	void testIdempotencyRejectsDuplicateRequests() throws Exception {
		int n = 4;
		DepChainService[] services = createServices(n, BASE_PORT + 100);

		try {
			for (DepChainService s : services) s.start();
			Thread.sleep(2000);

			// Submit and wait for first transaction
			submitAndWait(services, "UniqueTx", 1, n);

			// Re-submit the same transaction to every replica (simulating UDP retries)
			for (DepChainService s : services) {
				s.handleClientRequest("UniqueTx");
			}

			// Give the system time to (incorrectly) process duplicates
			Thread.sleep(3000);

			// Blockchain must still have exactly 1 entry
			for (int i = 0; i < n; i++) {
				List<String> bc = services[i].getBlockchain();
				assertEquals(1, bc.size(),
						"Replica " + i + " should have exactly 1 block (duplicate rejected), got " + bc.size());
				assertEquals("UniqueTx", bc.get(0));
			}
		} finally {
			stopAll(services);
		}
	}

	/**
	 * Non-leader handling: a client request sent to a non-leader replica
	 * should not be proposed (only the leader proposes). The request should
	 * still eventually reach the blockchain if re-submitted to the leader.
	 */
	@Test
	void testNonLeaderIgnoresClientRequest() throws Exception {
		int n = 4;
		DepChainService[] services = createServices(n, BASE_PORT + 200);

		try {
			for (DepChainService s : services) s.start();
			Thread.sleep(2000);

			int currentView = services[0].getHotStuff().getCurrentView();
			int leaderId = currentView % n;
			int nonLeaderId = (leaderId + 1) % n;

			// Submit to a non-leader (it should be ignored)
			services[nonLeaderId].handleClientRequest("NonLeaderTx");
			Thread.sleep(2000);

			// No replica should have decided this (non-leader doesn't propose)
			for (int i = 0; i < n; i++) {
				assertTrue(services[i].getBlockchain().isEmpty(),
						"Replica " + i + " should have empty blockchain (non-leader cannot propose)");
			}

			// Now submit to the actual leader (it should succeed)
			submitAndWait(services, "NonLeaderTx", 1, n);

			for (int i = 0; i < n; i++) {
				assertEquals(1, services[i].getBlockchain().size(),
						"Replica " + i + " should have 1 block after leader proposal");
			}
		} finally {
			stopAll(services);
		}
	}
}
