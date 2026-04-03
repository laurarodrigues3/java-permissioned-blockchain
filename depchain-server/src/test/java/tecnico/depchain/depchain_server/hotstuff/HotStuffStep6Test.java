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
 * Step 6 — Byzantine Fault Tolerance Tests
 *
 * Tests that HotStuff tolerates crash faults and silent Byzantine replicas.
 * Verifies that real consensus (PREPARE → PRE-COMMIT → COMMIT → DECIDE)
 * completes despite f=1 faulty replicas, by checking that onDecide
 * callbacks fire (which only happens on successful view completion).
 */
@Timeout(60)
public class HotStuffStep6Test {

    private static final int N = 4;
    private static final int BASE_TIMEOUT_MS = 5000;
    private static final int BLOCK_BUILD_MARGIN_MS = 3000;
    private static final AtomicInteger PORT_BASE = new AtomicInteger(47000);

    private List<HotStuff> replicas;
    private List<CopyOnWriteArrayList<Block>> decidedPerReplica;

    @BeforeEach
    void setUp() {
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
            Address addr = Address.fromHexString(String.format("0x%040d", i + 1));
            CryptoService crypto = new CryptoService(i, keyPairs.get(i), pubKeys);

            HotStuff hs = new HotStuff(
                    i, addr, "127.0.0.1", basePort, n,
                    keyPairs.get(i).getPrivate(), pubKeys, crypto, null);
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

    private void startAllExcept(int crashedId) throws InterruptedException {
        // Allow DH handshakes to complete before starting the protocol loop
        Thread.sleep(2000);
        for (int i = 0; i < replicas.size(); i++) {
            if (i == crashedId) continue;
            final int idx = i;
            replicas.get(i).setOnDecide(blk -> decidedPerReplica.get(idx).add(blk));
            replicas.get(i).start();
        }
    }

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
    public void testConsensusSurvivesOneCrashedReplica() throws Exception {
        buildCluster(N);
        int crashedReplica = 3;

        // Start only replicas 0, 1, 2 — replica 3 is "crashed" (never started).
        // With n=4, f=1, quorum=3: the 3 alive replicas form an exact quorum.
        startAllExcept(crashedReplica);

        // onDecide only fires on successful consensus (not on timeouts),
        // so reaching minDecisions=1 proves real PREPARE→PRE-COMMIT→COMMIT→DECIDE.
        boolean reached = waitForDecisions(1, 40_000, true, crashedReplica);
        assertTrue(reached,
                "3 out of 4 replicas should reach real consensus (f=1 tolerated)");

        // Verify decidedBlockCount increased beyond genesis
        for (int i = 0; i < N; i++) {
            if (i == crashedReplica) continue;
            assertTrue(replicas.get(i).getDecidedBlockCount() > 1,
                    "Alive replica " + i + " should have decided at least one block beyond genesis");
        }
    }

    @Test
    public void testCrashedReplicaDoesNotDecide() throws Exception {
        buildCluster(N);
        int crashedReplica = 3;

        // Never start replica 3
        startAllExcept(crashedReplica);

        // Wait for alive replicas to complete at least one consensus round
        boolean reached = waitForDecisions(1, 40_000, true, crashedReplica);
        assertTrue(reached, "Alive replicas should reach consensus");

        // Crashed replica's onDecide callback should never have fired
        assertEquals(0, decidedPerReplica.get(crashedReplica).size(),
                "Crashed replica should not have decided any blocks");
    }

    @Test
    public void testSilentByzantineDoesNotPreventConsensus() throws Exception {
        buildCluster(N);

        // Replica 2 drops ALL outgoing messages (simulates silent Byzantine).
        // It can still receive messages but never sends votes or NEW_VIEWs.
        replicas.get(2).setOutgoingFilter((dest, msg) -> null);

        startAll();

        // With 1 silent replica, 3 honest replicas can still form quorum (n-f=3).
        // onDecide fires only on real consensus, so this proves fault tolerance.
        boolean reached = waitForDecisions(1, 40_000, true, 2);
        assertTrue(reached,
                "3 honest replicas should reach consensus despite 1 silent Byzantine");

        // Verify block count increased for honest replicas
        for (int i = 0; i < N; i++) {
            if (i == 2) continue;
            assertTrue(replicas.get(i).getDecidedBlockCount() > 1,
                    "Honest replica " + i + " should decide blocks despite silent Byzantine");
        }
    }

    @Test
    public void testViewAdvancesWithOneCrash() throws Exception {
        buildCluster(N);
        int crashedReplica = 0;
        startAllExcept(crashedReplica);

        // Wait for at least one real decision to prove views advance via consensus
        boolean reached = waitForDecisions(1, 40_000, true, crashedReplica);
        assertTrue(reached, "Alive replicas should decide despite one crash");

        // Views should have advanced past the initial timeout
        for (int i = 1; i < N; i++) {
            assertTrue(replicas.get(i).getCurrentView() >= 2,
                    "Alive replica " + i + " should have advanced views");
        }
    }
}
