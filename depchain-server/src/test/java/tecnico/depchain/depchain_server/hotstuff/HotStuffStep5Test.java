package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.hyperledger.besu.datatypes.Address;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tecnico.depchain.depchain_server.blockchain.Block;
import tecnico.depchain.depchain_server.blockchain.EVM;

/**
 * Step 5 — HotStuff Consensus Integration Tests
 *
 * Spins up N=4 HotStuff replicas on localhost with real UDP links.
 * Tests that the full 3-phase HotStuff protocol (PREPARE → PRE-COMMIT →
 * COMMIT → DECIDE) actually completes and produces real block decisions.
 *
 * NOTE: All replicas share a single EVM singleton in-process.
 * Empty blocks (no transactions) are used to avoid EVM state conflicts.
 * Block hash comparison across replicas is not possible due to shared EVM
 * (each replica's executeDecision mutates the same world state).
 */
@Timeout(60)
public class HotStuffStep5Test {

    private static final int N = 4;
    private static final int BASE_TIMEOUT_MS = 5000;
    private static final int BLOCK_BUILD_MARGIN_MS = 3000;
    private static final AtomicInteger PORT_BASE = new AtomicInteger(45000);

    private List<HotStuff> replicas;
    private List<CopyOnWriteArrayList<Block>> decidedPerReplica;

    @BeforeEach
    void setUp() throws Exception {
        EVM.resetInstance();
        replicas = new ArrayList<>();
        decidedPerReplica = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        for (HotStuff hs : replicas) {
            try { hs.stop(); } catch (Exception ignored) {}
        }
        replicas.clear();
        EVM.resetInstance();
    }

    private void buildCluster(int n) throws Exception {
        int basePort = PORT_BASE.getAndAdd(n * n + 10);
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(n);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);

        Block genesis = new Block(null, new ArrayList<>(), null);

        for (int i = 0; i < n; i++) {
            Address addr = Address.fromHexString(
                    String.format("0x%040d", i + 1));
            CryptoService crypto = new CryptoService(i, keyPairs.get(i), pubKeys);

            HotStuff hs = new HotStuff(
                    i, addr, "127.0.0.1", basePort, n,
                    keyPairs.get(i).getPrivate(), pubKeys,
                    crypto, null);
            hs.setBaseTimeout(BASE_TIMEOUT_MS);
            hs.setBlockBuildMargin(BLOCK_BUILD_MARGIN_MS);
            hs.setGenesisBlock(genesis);

            CopyOnWriteArrayList<Block> decided = new CopyOnWriteArrayList<>();
            decidedPerReplica.add(decided);

            replicas.add(hs);
        }
    }

    private void startAll() throws InterruptedException {
        // Allow DH handshakes to complete before starting the protocol loop
        Thread.sleep(2000);
        for (int i = 0; i < replicas.size(); i++) {
            final int idx = i;
            replicas.get(i).setOnDecide(blk -> decidedPerReplica.get(idx).add(blk));
            replicas.get(i).start();
        }
    }

    /**
     * Waits until all specified replicas have at least {@code minDecisions}
     * entries in their onDecide callback list, or until {@code timeoutMs} elapses.
     */
    private boolean waitForDecisions(int minDecisions, long timeoutMs,
                                     boolean skipCrashed, int crashedId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allReached = true;
            for (int i = 0; i < decidedPerReplica.size(); i++) {
                if (skipCrashed && i == crashedId) continue;
                if (decidedPerReplica.get(i).size() < minDecisions) {
                    allReached = false;
                    break;
                }
            }
            if (allReached) return true;
            Thread.sleep(200);
        }
        return false;
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    public void testGenesisBlockSetBeforeStart() throws Exception {
        buildCluster(N);
        for (HotStuff hs : replicas) {
            assertEquals(1, hs.getDecidedBlockCount(),
                    "Before start, only genesis block should be decided");
        }
    }

    @Test
    public void testGenesisBlockSetOnlyOnce() throws Exception {
        buildCluster(N);
        startAll();
        for (HotStuff hs : replicas) {
            assertThrows(IllegalStateException.class, () -> hs.setGenesisBlock(new Block()),
                    "Cannot set genesis after start()");
        }
    }

    @Test
    public void testConsensusDecidesBlock() throws Exception {
        buildCluster(N);
        startAll();

        // View 0 times out (leader 0 waits for NEW_VIEW(-1) which never arrives).
        // View 1 should succeed: leader 1 collects NEW_VIEW(0) from all replicas,
        // proposes empty block, 3-phase voting completes, executeDecision fires.
        // onDecide only fires on success (no longer in finally block).
        boolean reached = waitForDecisions(1, 30_000, false, -1);
        assertTrue(reached,
                "All replicas should decide at least one block through real consensus");

        // decidedBlockCount should be > 1 (genesis + at least one decided block)
        for (HotStuff hs : replicas) {
            assertTrue(hs.getDecidedBlockCount() > 1,
                    "Each replica should have decided at least one block beyond genesis");
        }
    }

    @Test
    public void testDecidedBlockHasCorrectStructure() throws Exception {
        buildCluster(N);
        startAll();

        boolean reached = waitForDecisions(1, 30_000, false, -1);
        assertTrue(reached, "Consensus should complete");

        // The decided block should have empty transactions (mempool is empty)
        // and a non-null previousBlockHash (pointing to genesis)
        for (int i = 0; i < N; i++) {
            Block decided = decidedPerReplica.get(i).get(0);
            assertNotNull(decided, "Decided block should not be null");
            assertNotNull(decided.getTransactions(), "Transactions list should exist");
            assertTrue(decided.getTransactions().isEmpty(),
                    "Block should have no transactions (empty mempool)");
        }
    }

    @Test
    public void testOnDecideDoesNotFireOnTimeout() throws Exception {
        buildCluster(N);
        startAll();

        // View 0 always times out. Wait just enough for the timeout but not
        // enough for view 1 to fully complete. With BASE_TIMEOUT_MS=2000,
        // view 0 takes ~2s. Check that onDecide has NOT fired yet during
        // the timeout phase (it should only fire on successful consensus).
        Thread.sleep(BASE_TIMEOUT_MS / 2);

        // During the first half of view 0, no consensus has completed
        for (int i = 0; i < N; i++) {
            assertEquals(0, decidedPerReplica.get(i).size(),
                    "onDecide should NOT fire during a timeout — replica " + i);
        }
    }

    @Test
    public void testMultipleConsecutiveDecisions() throws Exception {
        buildCluster(N);
        startAll();

        // Wait for at least 2 decisions (view 1 and view 2 should both succeed)
        boolean reached = waitForDecisions(2, 40_000, false, -1);
        assertTrue(reached,
                "All replicas should decide at least 2 blocks through consecutive views");

        for (HotStuff hs : replicas) {
            assertTrue(hs.getDecidedBlockCount() >= 3,
                    "Should have genesis + at least 2 decided blocks");
        }
    }

    @Test
    public void testLeaderRotation() throws Exception {
        buildCluster(N);
        startAll();

        // After 2+ successful views, leader has rotated
        boolean reached = waitForDecisions(2, 40_000, false, -1);
        assertTrue(reached, "Multiple views should succeed");

        // View number should have advanced (view 0 timeout + 2+ successes)
        int maxView = 0;
        for (HotStuff hs : replicas) {
            maxView = Math.max(maxView, hs.getCurrentView());
        }
        assertTrue(maxView >= 3,
                "Views should advance: 1 timeout + 2+ successes = view >= 3, got " + maxView);
    }
}
