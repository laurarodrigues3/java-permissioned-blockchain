package tecnico.depchain.depchain_server.hotstuff;

import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import tecnico.depchain.depchain_common.blockchain.Transaction;
import tecnico.depchain.depchain_server.blockchain.Block;

public class DepChainService implements ConsensusUpcall {
    private final int replicaID;
    private final int numReplicas;
    private final HotStuff hotStuff;
	private Consumer<Block> onDecide = null;

    // In-memory array of append-only transactions representing the blockchain
    private final List<Block> blockchain = Collections.synchronizedList(new ArrayList<>());

    private final List<Transaction> pendingTransactions = Collections.synchronizedList(new ArrayList<>());

    public DepChainService(
            int replicaID, String host, int basePort, int numReplicas,
            PrivateKey ownKey, List<PublicKey> publicKeys, CryptoService crypto, ThresholdCrypto thresholdCrypto)
            throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
        this.replicaID = replicaID;
        this.numReplicas = numReplicas;
        // Pass "this" as the ConsensusUpcall
        this.hotStuff = new HotStuff(replicaID, host, basePort, numReplicas, ownKey, publicKeys, crypto, thresholdCrypto, this);

        //TODO: Set DepChain timeout, smaller than hotstuff timeout, to gather existing transactions, sort and propose
    }

    public void start() {
        this.hotStuff.start();
    }

    public void stop() {
        this.hotStuff.stop();
    }

    public void setBaseTimeout(long timeoutMs) {
        this.hotStuff.setBaseTimeout(timeoutMs);
    }

    public void setOnDecide(Consumer<Block> callback) {
		this.onDecide = callback;
	}

    @Override
    public void onDecide(Block blk) {
        blockchain.add(blk);
        System.out.println("Block decided and added to the blockchain: " + blk);
        if (onDecide != null)
            onDecide(blk);
    }

    /**
     * Handles a request originating from the client.
     */
    public void handleClientRequest(Transaction requestedTx) {
        if (blockchainHasTransaction(requestedTx)) {
            System.err.println("[DepChainService-" + replicaID + "] Request ignored. The transaction is already on the blockchain: '" + requestedTx + "'");
            return;
        }

        //Save transaction
        pendingTransactions.add(requestedTx);
    }

    public List<Block> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public HotStuff getHotStuff() {
        return hotStuff;
    }

    private boolean blockchainHasTransaction(Transaction tx) {
        for (Block blk : blockchain){
            if (blk.transactions().contains(tx))
                return true;
        }
        return false;
    }
}
