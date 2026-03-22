package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;



import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

/**
 * Step 5 tests: Byzantine fault tolerance.
 *
 * Byzantine replicas are simulated by giving them a mismatched Ed25519 key pair
 * (different private key than what other replicas expect). This causes their
 * vote signatures to be cryptographically invalid from the perspective of all
 * honest replicas. The system must still reach consensus using the honest quorum.
 */
public class HotStuffStep5Test {

	private static final String HOST = "127.0.0.1";
	private static final int BASE_PORT = 60000;

	/**
	 * Create n replicas where replicas are given mismatched
	 * Ed25519 keys (their signatures will be invalid to everyone else).
	 * All replicas receive threshold crypto params for QC threshold signatures.
	 */
	private HotStuff[] createReplicasWithByzantine(int n, int basePort, int... byzantineIds)
			throws Exception {
		List<KeyPair> honestKeyPairs = TestKeyHelper.readKeysFromTestConfig(n);
		List<PublicKey> publicKeys = TestKeyHelper.extractPublicKeys(honestKeyPairs);

		int f = (n - 1) / 3;
		int threshold = 2 * f + 1;
		ThresholdCrypto.DealerParams dealerParams = ThresholdCrypto.generateParams(threshold, n);
		byte[] thresholdPublicKey = dealerParams.globalPublicKey;

		java.util.Set<Integer> byzantineSet = new java.util.HashSet<>();
		for (int id : byzantineIds) byzantineSet.add(id);

		List<KeyPair> byzantineKeyPairs = CryptoService.generateKeyPairs(byzantineIds.length);

		HotStuff[] replicas = new HotStuff[n];
		int bIdx = 0;
		for (int i = 0; i < n; i++) {
			CryptoService crypto;
			if (byzantineSet.contains(i)) {
				crypto = new CryptoService(i, byzantineKeyPairs.get(bIdx++), publicKeys, thresholdPublicKey);
			} else {
				crypto = new CryptoService(i, honestKeyPairs.get(i), publicKeys, thresholdPublicKey);
			}
			ThresholdCrypto tc = new ThresholdCrypto(i, threshold, n, dealerParams.pairingParamsStr,
					dealerParams.generator, dealerParams.globalPublicKey, dealerParams.privateShares.get(i),
					dealerParams.publicShares);
			replicas[i] = new HotStuff(i, HOST, basePort, n, honestKeyPairs.get(i).getPrivate(), publicKeys, crypto, tc);
		}
		return replicas;
	}

	private HotStuff[] createHonestReplicas(int n, int basePort) throws Exception {
		return createReplicasWithByzantine(n, basePort);
	}

	private void stopAll(HotStuff[] replicas) {
		for (HotStuff r : replicas) {
			if (r != null) {
				try { r.stop(); } catch (Exception e) { /* best-effort */ }
			}
		}
		try { Thread.sleep(500); } catch (InterruptedException ignored) {}
	}


	/**
	 * 1 Byzantine replica (wrong keys) out of 4.
	 * Honest replicas should still reach consensus because the leader rejects
	 * the Byzantine replica's votes but has enough honest votes for quorum (3).
	 */
	@Test
	void testConsensusWithOneByzantineReplica() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicasWithByzantine(n, BASE_PORT, 0);

		try {
			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			int leader = 1 % n; // view 1 leader
			replicas[leader].propose("ByzantineTest");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"All replicas (including Byzantine) should decide within timeout");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size(),
						"Replica " + i + " should have 1 decision");
				assertEquals("ByzantineTest", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Multiple decisions with 1 Byzantine replica.
	 * Verifies the system sustains consensus across several view rotations
	 * even when one replica has invalid crypto keys. Commands are proposed
	 * to all replicas so whichever becomes leader can propose.
	 */
	@Test
	void testMultipleDecisionsWithByzantineFault() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicasWithByzantine(n, BASE_PORT + 100, 3);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			int numDecisions = 3;
			CountDownLatch latch = new CountDownLatch(n * numDecisions);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			for (int v = 1; v <= numDecisions; v++) {
				for (HotStuff r : replicas)
					r.propose("ByzCmd-" + v);
				Thread.sleep(1000);
			}

			assertTrue(latch.await(60, TimeUnit.SECONDS),
					"All replicas should decide all commands despite 1 Byzantine fault");

			for (int i = 0; i < n; i++) {
				assertTrue(decisions.get(i).size() >= numDecisions,
						"Replica " + i + " should have at least " + numDecisions + " decisions");
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica is the leader for a view.
	 * Its self-vote has an invalid signature, but the QC it builds still
	 * contains enough honest signatures (3 out of 4) to verify.
	 * The system should still progress.
	 */
	@Test
	void testByzantineLeaderStillAllowsProgress() throws Exception {
		int n = 4;
		HotStuff[] replicas = createReplicasWithByzantine(n, BASE_PORT + 200, 2);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			// Propose to all replicas so whichever leader wins can propose
			for (HotStuff r : replicas)
				r.propose("ByzLeaderCmd");

			assertTrue(latch.await(20, TimeUnit.SECONDS),
					"System should reach consensus even when Byzantine replica is/was leader");

			for (int i = 0; i < n; i++) {
				assertTrue(decisions.get(i).size() >= 1,
						"Replica " + i + " should have at least 1 decision");
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica combined with a crash fault.
	 * n=4, f=1: with 1 Byzantine + 1 crashed, only 2 honest replicas remain.
	 * quorum = n-f = 3. With only 2 honest replicas, consensus CANNOT be reached
	 * (2 < 3). This verifies the safety guarantee: honest replicas do NOT decide
	 * with insufficient honest votes (the Byzantine replica may decide on its own
	 * when acting as leader, but that is expected Byzantine behavior).
	 */
	@Test
	void testByzantinePlusCrashHonestReplicasCannotDecide() throws Exception {
		int n = 4;
		int byzantineId = 0;
		int crashedId = 3;
		// Replica 0 is Byzantine (wrong keys), Replica 3 is crashed (never started)
		HotStuff[] replicas = createReplicasWithByzantine(n, BASE_PORT + 300, byzantineId);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(1000);

			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> rd.add(cmd));
			}

			// Start all except crashed replica
			for (int i = 0; i < n; i++) {
				if (i != crashedId) replicas[i].start();
			}
			Thread.sleep(200);

			for (int i = 0; i < n; i++) {
				if (i != crashedId) replicas[i].propose("ShouldNotDecide");
			}

			// Wait a reasonable time - honest replicas should NOT decide
			Thread.sleep(10000);

			for (int i = 0; i < n; i++) {
				if (i == crashedId || i == byzantineId) continue;
				assertEquals(0, decisions.get(i).size(),
						"Honest replica " + i + " should NOT have decided (insufficient honest replicas for quorum)");
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Verify that the QC verification mechanism works end-to-end:
	 * with all honest replicas, decisions happen normally even with
	 * the stricter signature verification.
	 */
	@Test
	void testHonestReplicasDecideWithStrictVerification() throws Exception {
		int n = 4;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 400);

		try {
			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			replicas[1].propose("StrictVerifyCmd");

			assertTrue(latch.await(10, TimeUnit.SECONDS),
					"All honest replicas should decide with strict QC verification");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size());
				assertEquals("StrictVerifyCmd", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}


	/**
	 * Byzantine replica silently drops all its vote messages.
	 * The leader never receives its votes, but still has quorum from
	 * the 3 honest replicas (self + 2 honest remotes = 3 >= quorum).
	 * Uses the outgoing message filter to suppress vote messages.
	 */
	@Test
	void testFilterDropsVotesFromByzantineReplica() throws Exception {
		int n = 4;
		int byzantineId = 0;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 500);

		try {
			// Byzantine replica drops all vote-type messages (PREPARE/PRE_COMMIT/COMMIT votes)
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				MsgType t = msg.getType();
				if (msg.getPartialSignature() != null
						&& (t == MsgType.PREPARE || t == MsgType.PRE_COMMIT || t == MsgType.COMMIT)) {
					return null; // silently drop the vote
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			replicas[1].propose("DropVotesTest");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine replica dropping all votes");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size());
				assertEquals("DropVotesTest", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica corrupts its vote signatures before sending.
	 * The leader's signature verification rejects these tampered votes.
	 * The system still reaches consensus from the 3 honest replicas.
	 */
	@Test
	void testFilterCorruptsVoteSignatures() throws Exception {
		int n = 4;
		int byzantineId = 0;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 600);

		try {
			// Byzantine replica flips bits in vote signatures
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				byte[] sig = msg.getPartialSignature();
				if (sig != null && sig.length > 0) {
					byte[] corrupted = Arrays.copyOf(sig, sig.length);
					corrupted[0] ^= 0xFF;
					corrupted[corrupted.length - 1] ^= 0xFF;
					return new Message(msg.getType(), msg.getViewNumber(),
							msg.getSenderId(), msg.getTreeNode(),
							msg.getJustify(), corrupted);
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			replicas[1].propose("CorruptSigTest");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine replica corrupting signatures");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size());
				assertEquals("CorruptSigTest", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica spoofs its senderId in vote messages, pretending
	 * to be another replica. The leader verifies the signature against
	 * the spoofed sender's public key, which fails (signed by Byzantine
	 * replica's key, not the spoofed replica's key).
	 */
	@Test
	void testFilterSpoofsSenderId() throws Exception {
		int n = 4;
		int byzantineId = 0;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 700);

		try {
			// Byzantine replica pretends to be replica 2 in all vote messages
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				if (msg.getPartialSignature() != null) {
					return new Message(msg.getType(), msg.getViewNumber(),
							2, // spoofed senderId
							msg.getTreeNode(), msg.getJustify(),
							msg.getPartialSignature());
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			replicas[1].propose("SpoofIdTest");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine replica spoofing sender ID");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size());
				assertEquals("SpoofIdTest", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica replays votes from a previous view by changing
	 * the viewNumber in its outgoing messages to an old value.
	 * These messages will either not match pullMessage (wrong view) or
	 * fail signature verification (signed for a different view).
	 */
	@Test
	void testFilterReplaysOldViewNumber() throws Exception {
		int n = 4;
		int byzantineId = 0;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 800);

		try {
			// Byzantine replica sends votes with view = 0 (old/invalid)
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				if (msg.getPartialSignature() != null) {
					return new Message(msg.getType(), 0,
							msg.getSenderId(), msg.getTreeNode(),
							msg.getJustify(), msg.getPartialSignature());
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			replicas[1].propose("ReplayViewTest");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine replica replaying old view");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size());
				assertEquals("ReplayViewTest", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica injects a fake QC with a high view number into its
	 * NEW_VIEW message. The leader must verify QCs before selecting highQC,
	 * rejecting the forged QC (it has no valid signatures).
	 * The system should still reach consensus using valid QCs from honest replicas.
	 */
	@Test
	void testFilterForgesHighQCInNewView() throws Exception {
		int n = 4;
		int byzantineId = 0;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 900);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			// Byzantine replica forges a high-view QC in all its NEW_VIEW messages
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				if (msg.getType() == MsgType.NEW_VIEW) {
					TreeNode fakeNode = new TreeNode(new byte[32], "forged");
					QuorumCertificate fakeQC = new QuorumCertificate(
							MsgType.PREPARE, 999, fakeNode);
					fakeQC.addVote(0, null);
					fakeQC.addVote(1, null);
					fakeQC.addVote(2, null);
					return new Message(MsgType.NEW_VIEW, msg.getViewNumber(),
							msg.getSenderId(), msg.getTreeNode(), fakeQC);
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			for (HotStuff r : replicas)
				r.propose("ForgedQCTest");

			assertTrue(latch.await(20, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine replica forging NEW_VIEW QC");

			for (int i = 0; i < n; i++) {
				assertTrue(decisions.get(i).size() >= 1,
						"Replica " + i + " should have at least 1 decision");
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine leader sends a PREPARE with a forged justifyQC (invalid signatures).
	 * Honest replicas must verify the justifyQC and nullify it if invalid,
	 * falling back to their lockedQC for the safeNode check.
	 * The system should still reach consensus in a later view with an honest leader.
	 */
	@Test
	void testFilterForgesJustifyQCInPrepare() throws Exception {
		int n = 4;
		// Replica 2 is "Byzantine" (will forge justifyQC in PREPARE broadcasts).
		int byzantineId = 2;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 1000);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			// Byzantine leader forges the justifyQC in PREPARE broadcasts
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				if (msg.getType() == MsgType.PREPARE && msg.getJustify() != null) {
					TreeNode fakeNode = new TreeNode(new byte[32], "forged-justify");
					QuorumCertificate fakeJustify = new QuorumCertificate(
							MsgType.PREPARE, 500, fakeNode);
					fakeJustify.addVote(0, null);
					fakeJustify.addVote(1, null);
					fakeJustify.addVote(2, null);
					return new Message(MsgType.PREPARE, msg.getViewNumber(),
							msg.getSenderId(), msg.getTreeNode(), fakeJustify);
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			for (HotStuff r : replicas)
				r.propose("ForgedJustifyTest");

			assertTrue(latch.await(20, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine leader forging justifyQC");

			for (int i = 0; i < n; i++) {
				assertTrue(decisions.get(i).size() >= 1,
						"Replica " + i + " should have at least 1 decision");
			}
		} finally {
			stopAll(replicas);
		}
	}


	/**
	 * Combined Byzantine attack: one replica simultaneously has wrong crypto keys
	 * (invalid signatures everywhere) AND uses the message filter to forge
	 * high-view QCs in NEW_VIEW messages. This tests both vote rejection AND
	 * NEW_VIEW QC verification together under active attack.
	 * The system must still reach consensus with the 3 honest replicas.
	 */
	@Test
	void testCombinedByzantineAttack() throws Exception {
		int n = 4;
		int byzantineId = 0;
		// Byzantine replica has wrong keys (invalid sigs) + will forge NEW_VIEW QCs
		HotStuff[] replicas = createReplicasWithByzantine(n, BASE_PORT + 1100, byzantineId);

		try {
			for (HotStuff r : replicas)
				r.setBaseTimeout(2000);

			// Additionally, forge NEW_VIEW QCs with fake high view numbers
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				if (msg.getType() == MsgType.NEW_VIEW) {
					TreeNode fakeNode = new TreeNode(new byte[32], "combined-attack");
					QuorumCertificate fakeQC = new QuorumCertificate(
							MsgType.PREPARE, 9999, fakeNode);
					fakeQC.addVote(0, null);
					fakeQC.addVote(1, null);
					fakeQC.addVote(2, null);
					return new Message(MsgType.NEW_VIEW, msg.getViewNumber(),
							msg.getSenderId(), msg.getTreeNode(), fakeQC);
				}
				return msg;
			});

			int numDecisions = 2;
			CountDownLatch latch = new CountDownLatch(n * numDecisions);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			for (int v = 1; v <= numDecisions; v++) {
				for (HotStuff r : replicas)
					r.propose("CombinedAttack-" + v);
				Thread.sleep(1000);
			}

			assertTrue(latch.await(45, TimeUnit.SECONDS),
					"System should reach consensus despite combined Byzantine attack");

			for (int i = 0; i < n; i++) {
				assertTrue(decisions.get(i).size() >= numDecisions,
						"Replica " + i + " should have at least " + numDecisions + " decisions");
			}
		} finally {
			stopAll(replicas);
		}
	}

	@Test
	void testFilterSpoofsSenderIdOutOfRange() throws Exception {
		int n = 4;
		int byzantineId = 0;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 1200);

		try {
			// Byzantine replica sets senderId to -1 (out of range)
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				if (msg.getPartialSignature() != null) {
					return new Message(msg.getType(), msg.getViewNumber(),
							-1, msg.getTreeNode(), msg.getJustify(),
							msg.getPartialSignature());
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			replicas[1].propose("OutOfRangeIdTest");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"System should reach consensus despite out-of-range sender ID attack");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size());
				assertEquals("OutOfRangeIdTest", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

	/**
	 * Byzantine replica explicitly corrupts its BLS partial signature in its vote messages.
	 * The leader's signature verification will reject the BLS partial signature.
	 * The system must still reach consensus using the remaining 3 honest replicas.
	 */
	@Test
	void testInvalidBLSPartialSignature() throws Exception {
		int n = 4;
		int byzantineId = 1;
		HotStuff[] replicas = createHonestReplicas(n, BASE_PORT + 1300);

		try {
			// Byzantine replica corrupts the BLS partialSignature
			replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
				byte[] pSig = msg.getPartialSignature();
				if (pSig != null && pSig.length > 0) {
					byte[] corruptedSig = Arrays.copyOf(pSig, pSig.length);
					// explicitly corrupt the BLS signature by modifying the first and last byte
					corruptedSig[0] ^= 0x5A;
					corruptedSig[corruptedSig.length - 1] ^= 0x5A;

					return new Message(msg.getType(), msg.getViewNumber(),
							msg.getSenderId(), msg.getTreeNode(),
							msg.getJustify(), corruptedSig);
				}
				return msg;
			});

			CountDownLatch latch = new CountDownLatch(n);
			List<List<String>> decisions = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				List<String> rd = java.util.Collections.synchronizedList(new ArrayList<>());
				decisions.add(rd);
				replicas[i].setOnDecide(cmd -> {
					rd.add(cmd);
					latch.countDown();
				});
			}

			for (HotStuff r : replicas) r.start();
			Thread.sleep(200);

			// Propose from an honest replica (Replica 2 will be view 2 leader)
			replicas[2].propose("InvalidBLSPartialSig");

			assertTrue(latch.await(15, TimeUnit.SECONDS),
					"System should reach consensus despite Byzantine replica corrupting its BLS partial signature");

			for (int i = 0; i < n; i++) {
				assertEquals(1, decisions.get(i).size(), "Replica " + i + " should have 1 decision");
				assertEquals("InvalidBLSPartialSig", decisions.get(i).get(0));
			}
		} finally {
			stopAll(replicas);
		}
	}

}
