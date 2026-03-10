package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

/**
 * Stage 1 integration tests evaluating the end-to-end DepChainService logic.
 * Ensures the upcall propagation and the linear history (blockchain) logic is sound.
 */
public class DepChainServiceTest {

    private static final String HOST = "127.0.0.1";
    // Using a different base port to avoid clashes with other tests
    private static final int BASE_PORT = 50000;

    private static SecretKey generateKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256");
        return kg.generateKey();
    }

    /**
     * Helper to spawn n DepChainService instances with proper cryptographic setup.
     */
    private DepChainService[] createServices(int n, int basePort) throws Exception {
        List<SecretKey> hmacKeys = new ArrayList<>();
        for (int i = 0; i < n; i++)
            hmacKeys.add(generateKey());

        List<KeyPair> honestKeyPairs = CryptoService.generateKeyPairs(n);
        List<PublicKey> publicKeys = CryptoService.extractPublicKeys(honestKeyPairs);

        int f = (n - 1) / 3;
        int threshold = 2 * f + 1;
        ThresholdCrypto.DealerParams dealerParams = ThresholdCrypto.generateParams(threshold, n);
        byte[] thresholdPublicKey = dealerParams.globalPublicKey;

        DepChainService[] services = new DepChainService[n];
        for (int i = 0; i < n; i++) {
            CryptoService crypto = new CryptoService(i, honestKeyPairs.get(i), publicKeys, thresholdPublicKey);
            ThresholdCrypto tc = new ThresholdCrypto(i, threshold, n, dealerParams.pairingParamsStr,
                    dealerParams.generator, dealerParams.globalPublicKey, dealerParams.privateShares.get(i),
                    dealerParams.publicShares);
            services[i] = new DepChainService(i, HOST, basePort, n, new ArrayList<>(hmacKeys), crypto, tc);
        }
        return services;
    }

    private void stopAll(DepChainService[] services) {
        for (DepChainService s : services) {
            if (s != null) {
                try { s.stop(); } catch (Exception e) { /* best-effort shutdown */ }
            }
        }
    }

    private void waitUntilBlockchainReachesSize(DepChainService[] services, int targetSize, int maxWaitMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < maxWaitMs) {
            boolean allReached = true;
            for (DepChainService s : services) {
                if (s.getBlockchain().size() < targetSize) {
                    allReached = false;
                    break;
                }
            }
            if (allReached) return;
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("Timeout waiting for blockchain to reach size " + targetSize);
    }

    @Test
    void testBlockchainAppendDecisions() throws Exception {
        int n = 4; // Tolerant to f=1 Byzantine failure
        DepChainService[] services = createServices(n, BASE_PORT);

        try {
            for (DepChainService s : services) {
                s.start();
            }
            // Increase initial sleep to ensure all UDP sockets are bound securely
            Thread.sleep(2000); 

            for (int c = 1; c <= 3; c++) {
                String tx = "Tx" + c;

                // Emulate a robust client: if view fails, we query the new current view and submit to the new leader!
                long start = System.currentTimeMillis();
                boolean decided = false;
                int lastViewProposed = -1;

                while (System.currentTimeMillis() - start < 20000) {
                    int currentView = services[0].getHotStuff().getCurrentView();
                    
                    // Only propose once per view!
                    if (currentView != lastViewProposed) {
                        int leaderId = currentView % n;
                        System.out.println("Submitting " + tx + " to leader " + leaderId + " on view " + currentView);
                        services[leaderId].handleClientRequest(tx);
                        lastViewProposed = currentView;
                    }

                    // Check if it reached the target size yet
                    boolean allReached = true;
                    for (DepChainService s : services) {
                        if (s.getBlockchain().size() < c) {
                            allReached = false;
                            break;
                        }
                    }

                    if (allReached) {
                        decided = true;
                        break;
                    }
                    
                    // Small delay before checking again or triggering next view propose if timeout happens
                    Thread.sleep(200); 
                }

                if (!decided) {
                    org.junit.jupiter.api.Assertions.fail("Failed to reach consensus for " + tx + " after 20s");
                }
            }

            // Assertions
            for (int i = 0; i < n; i++) {
                List<String> bc = services[i].getBlockchain();
                assertEquals(3, bc.size(), "Replica " + i + " should have 3 blocks in blockchain");
                assertTrue(bc.contains("Tx1"), "Replica " + i + " missing Tx1");
                assertTrue(bc.contains("Tx2"), "Replica " + i + " missing Tx2");
                assertTrue(bc.contains("Tx3"), "Replica " + i + " missing Tx3");
            }
        } finally {
            stopAll(services);
        }
    }
}
