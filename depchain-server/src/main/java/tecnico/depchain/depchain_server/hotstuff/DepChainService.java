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

public class DepChainService implements ConsensusUpcall {
    private final int replicaID;
    private final int numReplicas;
    private final HotStuff hotStuff;
	private Consumer<String> onDecide = null;

    // In-memory array of append-only strings representing the blockchain
    private final List<String> blockchain = Collections.synchronizedList(new ArrayList<>());

    public DepChainService(
            int replicaID, String host, int basePort, int numReplicas,
            PrivateKey ownKey, List<PublicKey> publicKeys, CryptoService crypto, ThresholdCrypto thresholdCrypto)
            throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
        this.replicaID = replicaID;
        this.numReplicas = numReplicas;
        // Pass "this" as the ConsensusUpcall
        this.hotStuff = new HotStuff(replicaID, host, basePort, numReplicas, ownKey, publicKeys, crypto, thresholdCrypto, this);
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

    public void setOnDecide(Consumer<String> callback) {
		this.onDecide = callback;
	}

    @Override 
    public void onDecide(String payload) {
        blockchain.add(payload);
        System.out.println("Block decided and added to the blockchain: " + payload);
        if (onDecide != null)
            onDecide(payload);
    }

    /**
     * Handles a request originating from the client.
     * Evaluates if this node is the current leader and proposes to consensus.
     */
   public void handleClientRequest(String requestPayload) {
    if (this.blockchain.contains(requestPayload)) {
        System.err.println("[DepChainService-" + replicaID + "] Request ignored. The transaction is already on the blockchain: '" + requestPayload + "'");
        return;
    }

    int currentLeader = hotStuff.getCurrentView() % numReplicas;
    if (replicaID == currentLeader) {
        System.err.println("[DepChainService-" + replicaID + "] Proposing client request '" + requestPayload + "' for view " + hotStuff.getCurrentView());
        this.hotStuff.propose(requestPayload);
    } else {
        // In a real application, the client request would be gossiped or redirected to the leader.
        // For this phase, we just log and ignore.
        System.err.println("[DepChainService-" + replicaID + "] Ignored client request '" + requestPayload + "' (not the leader for view " + hotStuff.getCurrentView() + ").");
    }
}

    public List<String> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public HotStuff getHotStuff() {
        return hotStuff;
    }
}
