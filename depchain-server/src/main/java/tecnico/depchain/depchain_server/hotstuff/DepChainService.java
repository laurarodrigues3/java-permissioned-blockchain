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

import tecnico.depchain.depchain_server.blockchain.Mempool;

public class DepChainService implements ConsensusUpcall {
    private final int replicaID;
    private final int numReplicas;
    private final HotStuff hotStuff;
	private Consumer<String> onDecide = null;
    private Mempool mempool;

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

    public void setMempool(Mempool mempool) {
        this.mempool = mempool;
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

    public List<String> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public HotStuff getHotStuff() {
        return hotStuff;
    }
}
