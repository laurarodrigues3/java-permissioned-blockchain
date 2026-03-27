package tecnico.depchain.depchain_server.blockchain;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_common.DepchainClient;
import tecnico.depchain.depchain_common.Membership;
import tecnico.depchain.depchain_common.messages.TransactionMessage;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionSubmissionTest {

    private EVM evm;
    private Mempool mempool;
    private IncomingTransactionValidator validator;

    private KeyPair clientA;
    private KeyPair clientB;
    private KeyPair clientC;
    
    // Hardcoded addresses for tests
    private final String addrA = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final String addrB = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private final String addrC = "0xcccccccccccccccccccccccccccccccccccccccc";

    @BeforeEach
    public void setup() throws Exception {
        EVM.resetInstance();
        evm = EVM.getInstance();
        mempool = new Mempool(evm);
        validator = new IncomingTransactionValidator(mempool);

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Ed25519");
        clientA = keyGen.generateKeyPair();
        clientB = keyGen.generateKeyPair();

        clientC = keyGen.generateKeyPair();

        // Inject mock clients into Membership
        DepchainClient client0 = new DepchainClient(new InetSocketAddress("127.0.0.1", 10000), clientA.getPublic());
        client0.setEvmAddress(addrA);
        DepchainClient client1 = new DepchainClient(new InetSocketAddress("127.0.0.1", 10001), clientB.getPublic());
        client1.setEvmAddress(addrB);
        DepchainClient client2 = new DepchainClient(new InetSocketAddress("127.0.0.1", 10002), clientC.getPublic());
        client2.setEvmAddress(addrC);
        
        DepchainClient[] mockClients = new DepchainClient[]{ client0, client1, client2 };
        Membership.initForTesting(new tecnico.depchain.depchain_common.DepchainMember[0], mockClients, clientA.getPrivate());

        // Pre-fund addresses in EVM so validation passes the balance check
        evm.createEOA(Address.fromHexString(addrA), Wei.of(1000000000L));
        evm.createEOA(Address.fromHexString(addrB), Wei.of(1000000000L));
        evm.createEOA(Address.fromHexString(addrC), Wei.of(1000000000L));
    }

    @AfterEach
    public void teardown() {
        EVM.resetInstance();
    }

    private TransactionMessage createAndSignTx(int clientId, KeyPair keys, BigInteger nonce, String from, String gasPrice, long gasLimit) {
        TransactionMessage tx = new TransactionMessage(clientId, nonce, from, null, gasPrice, gasLimit, "0", "");
        tx.sign(keys.getPrivate());
        return tx;
    }

    @Test
    public void testTransactionMessageSigningAndVerification() throws Exception {
        TransactionMessage tx = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "10", 21000);
        
        // Pass
        assertTrue(tx.verify(clientA.getPublic()), "Signature should easily verify with correct key");
        
        // Fail
        assertFalse(tx.verify(clientB.getPublic()), "Signature should fail with wrong key");
        
        // Tamper test
        byte[] raw = tx.serialize();
        raw[raw.length - 1] ^= 1; // flip a bit
        TransactionMessage tampered = TransactionMessage.deserialize(raw);
        assertFalse(tampered.verify(clientA.getPublic()), "Tampered message should fail verification");
    }

    @Test
    public void testMempoolGasPriceOrdering() {
        // 3 txs from different senders with different gas prices
        TransactionMessage tx1 = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "10", 21000);
        TransactionMessage tx2 = createAndSignTx(1, clientB, BigInteger.ZERO, addrB, "50", 21000);
        TransactionMessage tx3 = createAndSignTx(2, clientC, BigInteger.ZERO, addrC, "30", 21000);

        mempool.submitTransaction(tx1, validator);
        mempool.submitTransaction(tx2, validator);
        mempool.submitTransaction(tx3, validator);

        List<Transaction> top = mempool.getTopTransactions(100000);
        assertEquals(3, top.size());
        
        // Expected order: tx2 (50) -> tx3 (30) -> tx1 (10)
        assertEquals("50", top.get(0).gasPrice().toBigInteger().toString());
        assertEquals("30", top.get(1).gasPrice().toBigInteger().toString());
        assertEquals("10", top.get(2).gasPrice().toBigInteger().toString());
    }

    @Test
    public void testNonceOrderingWithinSameSender() {
        // Sender A submits nonce 0 (gas=10) and nonce 1 (gas=50)
        TransactionMessage tx0 = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "10", 21000);
        TransactionMessage tx1 = createAndSignTx(0, clientA, BigInteger.ONE, addrA, "50", 21000);

        mempool.addTransaction(IncomingTransactionValidator.toTransaction(tx0), tx0.getSignature());
        mempool.addTransaction(IncomingTransactionValidator.toTransaction(tx1), tx1.getSignature());

        List<Transaction> top = mempool.getTopTransactions(100000);
        assertEquals(2, top.size());
        
        // Expected: nonce 0 first despite lower gas, then nonce 1
        assertEquals(BigInteger.ZERO, top.get(0).nonce());
        assertEquals(BigInteger.ONE, top.get(1).nonce());
    }

    @Test
    public void testCrossSenderInterleaving() {
        // Sender A: nonce 0 (gas=10), nonce 1 (gas=50)
        TransactionMessage txA0 = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "10", 21000);
        TransactionMessage txA1 = createAndSignTx(0, clientA, BigInteger.ONE, addrA, "50", 21000);
        
        // Sender B: nonce 0 (gas=30)
        TransactionMessage txB0 = createAndSignTx(1, clientB, BigInteger.ZERO, addrB, "30", 21000);

        mempool.submitTransaction(txA0, validator);
        mempool.submitTransaction(txA1, validator);
        mempool.submitTransaction(txB0, validator);

        List<Transaction> top = mempool.getTopTransactions(100000);
        assertEquals(3, top.size());

        // Expected order: B:0@30 -> A:0@10 -> A:1@50
        // Because initially, heads are A:0(10) and B:0(30). B wins. B gets removed.
        // Then heads are A:0(10). A:0 wins. A:0 removed.
        // Then heads are A:1(50). A:1 wins.
        assertEquals(addrB, top.get(0).from().toHexString());
        assertEquals(addrA, top.get(1).from().toHexString());
        assertEquals(BigInteger.ZERO, top.get(1).nonce());
        assertEquals(addrA, top.get(2).from().toHexString());
        assertEquals(BigInteger.ONE, top.get(2).nonce());
    }

    @Test
    public void testPendingStateMultipleTxs() {
        TransactionMessage tx0 = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "10", 21000);
        TransactionMessage tx1 = createAndSignTx(0, clientA, BigInteger.ONE, addrA, "10", 21000);
        TransactionMessage tx2 = createAndSignTx(0, clientA, BigInteger.valueOf(2), addrA, "10", 21000);

        assertTrue(mempool.submitTransaction(tx0, validator));
        assertTrue(mempool.submitTransaction(tx1, validator));
        assertTrue(mempool.submitTransaction(tx2, validator));

        assertEquals(BigInteger.valueOf(3), mempool.getPendingNonce(Address.fromHexString(addrA)));
        
        long spent = 3 * 10 * 21000;
        assertEquals(Wei.of(1000000000L - spent), mempool.getPendingBalance(Address.fromHexString(addrA)));
    }

    @Test
    public void testValidatorRejectionCases() {
        // Zero gasPrice
        TransactionMessage txZeroGas = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "0", 21000);
        assertFalse(validator.validate(txZeroGas), "Should reject zero gas limits/prices");

        // Invalid signature
        TransactionMessage txSig = new TransactionMessage(1, BigInteger.ZERO, addrA, null, "10", 21000, "0", "");
        txSig.sign(clientA.getPrivate()); // signed by A, but clientId is 1 (B)
        assertFalse(validator.validate(txSig), "Should reject invalid signature");

        // Wrong nonce (gap)
        TransactionMessage txGap = createAndSignTx(0, clientA, BigInteger.ONE, addrA, "10", 21000);
        assertFalse(validator.validate(txGap), "Should reject nonce gap");

        // Insufficient balance
        TransactionMessage txPoor = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "100000", 21000);
        assertFalse(validator.validate(txPoor), "Should reject insufficient balance");

        // Spoofing attack (Client A signing for Client B's address)
        TransactionMessage txSpoof = createAndSignTx(0, clientA, BigInteger.ZERO, addrB, "10", 21000);
        assertFalse(validator.validate(txSpoof), "Should reject spoofed from address");
    }

    @Test
    public void testPostBlockCleanup() {
        TransactionMessage tx0 = createAndSignTx(0, clientA, BigInteger.ZERO, addrA, "10", 21000);
        TransactionMessage tx1 = createAndSignTx(0, clientA, BigInteger.ONE, addrA, "10", 21000);
        TransactionMessage tx2 = createAndSignTx(0, clientA, BigInteger.valueOf(2), addrA, "10", 21000);

        mempool.submitTransaction(tx0, validator);
        mempool.submitTransaction(tx1, validator);
        mempool.submitTransaction(tx2, validator);

        assertEquals(3, mempool.totalSize());

        // Simulate building a block with first 2 txs
        List<Transaction> blockTxs = List.of(
            IncomingTransactionValidator.toTransaction(tx0),
            IncomingTransactionValidator.toTransaction(tx1)
        );

        // Manually update EVM state to simulate tx0 and tx1 execution
        // We do this manually to bypass Besu's precompiled native library (gnark) linkage errors on ARM64
        org.hyperledger.besu.evm.account.MutableAccount acc = evm.getUpdater().getAccount(Address.fromHexString(addrA));
        acc.setNonce(2);
        evm.getUpdater().commit();

        // Notify mempool
        mempool.onBlockCommitted(blockTxs);

        // Expect 1 remaining tx (tx2)
        assertEquals(1, mempool.totalSize());
        
        // Mempool should have updated pendingNonce -> 3
        assertEquals(BigInteger.valueOf(3), mempool.getPendingNonce(Address.fromHexString(addrA)));
    }
}
