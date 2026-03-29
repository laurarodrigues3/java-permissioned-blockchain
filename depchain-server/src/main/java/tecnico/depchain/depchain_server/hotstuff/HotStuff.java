package tecnico.depchain.depchain_server.hotstuff;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.hyperledger.besu.datatypes.Address;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.broadcasts.BestEffortBroadcast;
import tecnico.depchain.depchain_server.blockchain.Block;
import tecnico.depchain.depchain_server.blockchain.EVM;
import tecnico.depchain.depchain_server.blockchain.Mempool;
import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

public class HotStuff {
	private final Address ownAddress;
	private final int replicaID;
	private final int numReplicas;
	private final int quorumSize; // n - f
	private final BestEffortBroadcast broadcast;
	private final CryptoService crypto;
	private final ThresholdCrypto thresholdCrypto;

	private volatile int currentView = 0;
	private QuorumCertificate prepareQC = null;
	private QuorumCertificate lockedQC = null;
	private TreeNode votedNodeThisView = null;

	private final Map<String, TreeNode> nodeStore = new HashMap<>();

    private Mempool mempool;
	private final List<Block> decidedBlocks = new ArrayList<>();

	private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();
	private final List<Message> outOfOrderBuffer = new ArrayList<>();

	private Consumer<Block> onDecide = null;

	// Outgoing message filter for Byzantine testing (Step 5).
	// Applied before every send/broadcast. Return null to drop the message.
	// Takes (destinationId, originalMessage) and returns modifiedMessage.
	private volatile BiFunction<Integer, Message, Message> outgoingFilter = null;

	// Protocol thread
	private Thread protocolThread;
	private volatile boolean running = false;

	public static final long maxBlockGas = 15_000_000L; //15M is close to ethereum's average target
	private static final long DEFAULT_TIMEOUT_MS = 5000;
	private static final long MAX_TIMEOUT_MS = 60000;
	private long baseTimeoutMs = DEFAULT_TIMEOUT_MS;
	private long viewTimeoutMs = DEFAULT_TIMEOUT_MS;
	private long viewDeadline;

	/**
	 * @param replicaID       This replica's ID (0-indexed)
	 * @param host            Host address for all replicas
	 * @param basePort        Base port for the system
	 * @param numReplicas     Total number of replicas
	 * @param ownKey          This replica's Ed25519 private key (for signing outgoing link messages)
	 * @param publicKeys      List of n Ed25519 public keys (index i = public key of replica i)
	 * @param crypto          CryptoService for Ed25519 signing/verification (Step 5)
	 * @param thresholdCrypto ThresholdCrypto for threshold QC signatures (nullable)
	 */
	public HotStuff(
			int replicaID, Address ownAddress, String host, int basePort, int numReplicas,
			PrivateKey ownKey, List<PublicKey> publicKeys, CryptoService crypto, ThresholdCrypto thresholdCrypto)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.replicaID = replicaID;
		this.ownAddress = ownAddress;
		this.numReplicas = numReplicas;
		this.crypto = crypto;
		this.thresholdCrypto = thresholdCrypto;
		int f = (numReplicas - 1) / 3;
		this.quorumSize = numReplicas - f;
		this.mempool = new Mempool();

		List<InetSocketAddress> locals = new ArrayList<>();
		List<InetSocketAddress> remotes = new ArrayList<>();
		List<PublicKey> peerKeys = new ArrayList<>();

		for (int j = 0; j < numReplicas; j++) {
			if (j == replicaID)
				continue;

			int localPort = basePort + replicaID * numReplicas + (j < replicaID ? j : j - 1);
			locals.add(new InetSocketAddress(host, localPort));

			int remotePort = basePort + j * numReplicas + (replicaID < j ? replicaID : replicaID - 1);
			remotes.add(new InetSocketAddress(host, remotePort));

			peerKeys.add(publicKeys.get(j));
		}

		broadcast = new BestEffortBroadcast(this::handleMsg, this::handleMsg, locals, ownKey, remotes, peerKeys);
	}

	public void start() {
		running = true;
		protocolThread = new Thread(this::protocolLoop, "HotStuff-" + replicaID);
		protocolThread.setDaemon(true);
		protocolThread.start();
	}

	public void stop() {
		running = false;
		if (protocolThread != null) {
			protocolThread.interrupt();
			try { protocolThread.join(3000); } catch (InterruptedException ignored) {}
		}
		broadcast.close();
	}

	public void propose(SignedTransaction signedTx) {
		mempool.addTransaction(signedTx);
	}

	public List<Block> getDecidedBlocks() {
		//REVIEW: Why a copy?
		return new ArrayList<>(decidedBlocks);
	}

	public void setOnDecide(Consumer<Block> callback) {
		this.onDecide = callback;
	}

	public int getCurrentView() {
		return currentView;
	}

	public void setBaseTimeout(long timeoutMs) {
		this.baseTimeoutMs = timeoutMs;
		this.viewTimeoutMs = timeoutMs;
	}

	public void setOutgoingFilter(BiFunction<Integer, Message, Message> filter) {
		this.outgoingFilter = filter;
	}

	private void handleMsg(byte[] data, InetSocketAddress remote) {
		byte[] msgBytes = new byte[data.length - 1];
		System.arraycopy(data, 1, msgBytes, 0, msgBytes.length);

		Message msg = Message.deserialize(msgBytes);
		if (msg != null) {
			// Verify the global message signature
			if (msg.getMessageSignature() != null) {
				if (!crypto.verify(msg.getSenderId(), msg.getSignableBytes(), msg.getMessageSignature())) {
					System.err.println("[HotStuff-" + replicaID + "] ERROR: Global signature failed for msg from " + msg.getSenderId() + " type=" + msg.getType());
					return; // Invalid signature, drop silently
				}
			} else {
				System.err.println("[HotStuff-" + replicaID + "] ERROR: Message unsigned from " + msg.getSenderId() + " type=" + msg.getType());
				return; // Unsigned message, drop silently
			}
			messageQueue.offer(msg);
		}
	}

	private void sendMessage(int replica, Message msg) {
		BiFunction<Integer, Message, Message> filter = this.outgoingFilter;
		if (filter != null) {
			msg = filter.apply(replica, msg);
			if (msg == null) return;
		}
		if (msg.getMessageSignature() == null) {
			msg.setMessageSignature(crypto.sign(msg.getSignableBytes()));
		}
		if (replica == replicaID) {
			messageQueue.offer(msg);
			return;
		}
		int link = replica > replicaID ? replica - 1 : replica;
		broadcast.transmit(link, msg.serialize());
	}

	private void broadcastMessage(Message msg) {
		BiFunction<Integer, Message, Message> filter = this.outgoingFilter;
		if (filter != null) {
			// Apply filter globally for the broadcast (using a dummy dest = -1)
			msg = filter.apply(-1, msg);
			if (msg == null) return;
		}

		if (msg.getMessageSignature() == null) {
			msg.setMessageSignature(crypto.sign(msg.getSignableBytes()));
		}

		// Broadcast to others using the native layer
		broadcast.broadcast(msg.serialize());

		// Self-delivery
		messageQueue.offer(msg);
	}

	private void startViewTimer() {
		viewDeadline = System.currentTimeMillis() + viewTimeoutMs;
	}

	private long remainingViewMs() {
		return Math.max(0, viewDeadline - System.currentTimeMillis());
	}

	// Tighter timing to propose blocks with present transactions
	private long remainingBlockMs() {
		return remainingViewMs() - 500; //Arbitrary value
	}

	private Message pullMessage(MsgType type, int viewNumber) throws InterruptedException {
		for (int i = 0; i < outOfOrderBuffer.size(); i++) {
			Message msg = outOfOrderBuffer.get(i);
			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return outOfOrderBuffer.remove(i);
			}
		}
		while (true) {
			long remaining = remainingViewMs();
			if (remaining <= 0)
				return null;

			Message msg = messageQueue.poll(remaining, TimeUnit.MILLISECONDS);
			if (msg == null)
				return null;

			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return msg;
			}
			if (msg.getViewNumber() >= currentView - 1) {
				outOfOrderBuffer.add(msg);
			}
		}
	}

	private int getLeader(int view) {
		return view % numReplicas;
	}

	private boolean isLeader() {
		return getLeader(currentView) == replicaID;
	}

	private Message makeMsg(MsgType type, TreeNode node, QuorumCertificate qc) {
		return new Message(type, currentView, replicaID, node, qc);
	}

	private Message makeVoteMsg(MsgType type, TreeNode node, QuorumCertificate qc) {
		byte[] nodeHash = (node != null) ? node.getHash() : null;
		byte[] sig;
		if (thresholdCrypto != null) {
			byte[] voteData = CryptoService.buildVoteData(type, currentView, nodeHash);
			sig = thresholdCrypto.signPartial(voteData);
		} else {
			sig = crypto.signVote(type, currentView, nodeHash);
		}
		return new Message(type, currentView, replicaID, node, qc, sig);
	}

	/**
	 * Verify a vote message's Ed25519 signature against the expected node hash.
	 * Also performs sender ID bounds check and rejects votes that claim to
	 * be from the leader itself (self-votes are added explicitly, not from network).
	 */
	private boolean verifyVoteMsg(Message msg, byte[] expectedNodeHash) {
		int senderId = msg.getSenderId();
		if (senderId < 0 || senderId >= numReplicas) {
			System.err.println("[HotStuff-" + replicaID + "] verifyVoteMsg failed: invalid senderId " + senderId);
			return false;
		}
		if (senderId == replicaID) {
			System.err.println("[HotStuff-" + replicaID + "] verifyVoteMsg failed: senderId == replicaID (" + senderId + ")");
			return false;
		}
		if (thresholdCrypto != null) {
			byte[] voteData = CryptoService.buildVoteData(msg.getType(), msg.getViewNumber(), expectedNodeHash);
			boolean res = thresholdCrypto.verifyPartial(senderId, voteData, msg.getPartialSignature());
			if (!res) {
				System.err.println("[HotStuff-" + replicaID + "] verifyVoteMsg failed: threshold crypto verification failed for sender " + senderId + " type=" + msg.getType() + " expectedHash=" + java.util.Arrays.toString(expectedNodeHash));
			}
			return res;
		}
		boolean res = crypto.verifyVote(
				senderId, msg.getType(), msg.getViewNumber(),
				expectedNodeHash, msg.getPartialSignature());
		if (!res) {
			System.err.println("[HotStuff-" + replicaID + "] verifyVoteMsg failed: crypto verification failed for sender " + senderId + " type=" + msg.getType());
		}
		return res;
	}

	private boolean safeNode(TreeNode node, QuorumCertificate qc) {
		if (lockedQC == null)
			return true;

		boolean extendsLocked = node.extendsFrom(lockedQC.getNode());
		boolean higherView = qc != null && qc.getViewNumber() > lockedQC.getViewNumber();

		return extendsLocked || higherView;
	}

	private TreeNode createLeaf(TreeNode parent, Block blk) {
		TreeNode leaf = new TreeNode(parent, blk);
		storeNode(leaf);
		return leaf;
	}

	private void storeNode(TreeNode node) {
		nodeStore.put(hashHex(node.getHash()), node);
	}

	private void linkParent(TreeNode node) {
		if (node == null)
			return;
		TreeNode parent = nodeStore.get(hashHex(node.getParentHash()));
		if (parent != null) {
			node.setParent(parent);
		}
	}

	private static String hashHex(byte[] hash) {
		StringBuilder sb = new StringBuilder();
		for (byte b : hash)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}

	/**
	 * After the leader collects n-f individually verified Partial BLS votes and forms
	 * a QC, interpolate the partial shares to create a BLS threshold signature.
	 */
	private void addThresholdSignature(QuorumCertificate qc, byte[] nodeHash) {
		if (thresholdCrypto == null) return;
		try {
			byte[] sig = thresholdCrypto.aggregateShares(qc.getSignatures());
			qc.setThresholdSignature(sig);
		} catch (Exception ignored) {
		}
	}

	private void protocolLoop() {
		// Bootstrap: every replica sends NEW_VIEW(viewNumber=0, qc=null) to View 1's leader
		sendNewViewToNextLeader();

		while (running) {
			startViewTimer();
			boolean viewSucceeded = false;

			try {
				if (isLeader()) {
					viewSucceeded = runLeaderPhase();
				} else {
					viewSucceeded = runReplicaPhase();
				}
			} catch (InterruptedException e) {
				break;
			} finally {
				if (running) {
					sendNewViewToNextLeader();

					if (viewSucceeded) {
						viewTimeoutMs = baseTimeoutMs;
					} else {
						viewTimeoutMs = Math.min(viewTimeoutMs * 2, MAX_TIMEOUT_MS);
					}
					currentView++;
					votedNodeThisView = null;

                    Block decidedThisView = decidedBlocks.getLast();
					if (onDecide != null) onDecide.accept(decidedThisView);
				}
			}
		}
	}

	/**
	 * Send NEW_VIEW carrying our highest prepareQC to the next view's leader.
	 * Called on both successful completion and timeout.
	 */
	private void sendNewViewToNextLeader() {
		int nextLeader = getLeader(currentView + 1);
		sendMessage(nextLeader, makeMsg(MsgType.NEW_VIEW, null, prepareQC));
	}

	/** @return true if the view completed successfully, false on timeout */
	private boolean runLeaderPhase() throws InterruptedException {

		QuorumCertificate highQC = null;

		// Always collect n-f NEW_VIEW messages (including View 1)
		Map<Integer, Message> newViews = new HashMap<>();

		while (newViews.size() < quorumSize) {
			Message msg = pullMessage(MsgType.NEW_VIEW, currentView - 1);
			if (msg == null) return false;
			newViews.put(msg.getSenderId(), msg);
		}

		// Null-safe highQC computation: if all QCs are null (View 1), highQC stays null
		for (Message m : newViews.values()) {
			if (m.getSenderId() < 0 || m.getSenderId() >= numReplicas) continue;
			QuorumCertificate qc = m.getJustify();
			if (qc != null
					&& qc.getType() == MsgType.PREPARE
					&& qc.getViewNumber() > 0
					&& qc.getViewNumber() < currentView
					&& qc.verify(crypto, thresholdCrypto, quorumSize)) {
				if (highQC == null || qc.getViewNumber() > highQC.getViewNumber())
					highQC = qc;
			}
		}

		// Create new proposal
		TreeNode parentNode = highQC != null ? highQC.getNode() : null;
		if (parentNode != null)
			linkParent(parentNode);

		Block blk = waitForBlock();
		if (blk == null) return false;

		TreeNode proposal = createLeaf(parentNode, blk);
		broadcastMessage(makeMsg(MsgType.PREPARE, proposal, highQC));

		boolean selfVote = safeNode(proposal, highQC);

		QuorumCertificate newPrepareQC = new QuorumCertificate(MsgType.PREPARE, currentView, proposal);
		if (selfVote) {
			byte[] selfSig;
			if (thresholdCrypto != null) {
				byte[] voteData = CryptoService.buildVoteData(MsgType.PREPARE, currentView, proposal.getHash());
				selfSig = thresholdCrypto.signPartial(voteData);
			} else {
				selfSig = crypto.signVote(MsgType.PREPARE, currentView, proposal.getHash());
			}
			newPrepareQC.addVote(replicaID, selfSig);
		}

		while (!newPrepareQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.PREPARE, currentView);
			if (msg == null) {
                System.err.println("[HotStuff-" + replicaID + "] Leader TIMED OUT waiting for PREPARE votes! Gathered: " + newPrepareQC.getSignatures().size());
				return false;
			}
			if (verifyVoteMsg(msg, proposal.getHash())) {
				newPrepareQC.addVote(msg.getSenderId(), msg.getPartialSignature());
			}
		}

		addThresholdSignature(newPrepareQC, proposal.getHash());
		prepareQC = newPrepareQC;
		broadcastMessage(makeMsg(MsgType.PRE_COMMIT, null, prepareQC));

		QuorumCertificate newPrecommitQC = new QuorumCertificate(MsgType.PRE_COMMIT, currentView, proposal);
		byte[] selfPrecommitSig;
		if (thresholdCrypto != null) {
			byte[] voteData = CryptoService.buildVoteData(MsgType.PRE_COMMIT, currentView, proposal.getHash());
			selfPrecommitSig = thresholdCrypto.signPartial(voteData);
		} else {
			selfPrecommitSig = crypto.signVote(MsgType.PRE_COMMIT, currentView, proposal.getHash());
		}
		newPrecommitQC.addVote(replicaID, selfPrecommitSig);

		while (!newPrecommitQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.PRE_COMMIT, currentView);
			if (msg == null) return false;
			if (verifyVoteMsg(msg, proposal.getHash())) {
				newPrecommitQC.addVote(msg.getSenderId(), msg.getPartialSignature());
			}
		}

		addThresholdSignature(newPrecommitQC, proposal.getHash());
		lockedQC = newPrecommitQC;
		broadcastMessage(makeMsg(MsgType.COMMIT, null, newPrecommitQC));

		QuorumCertificate newCommitQC = new QuorumCertificate(MsgType.COMMIT, currentView, proposal);
		byte[] selfCommitSig;
		if (thresholdCrypto != null) {
			byte[] voteData = CryptoService.buildVoteData(MsgType.COMMIT, currentView, proposal.getHash());
			selfCommitSig = thresholdCrypto.signPartial(voteData);
		} else {
			selfCommitSig = crypto.signVote(MsgType.COMMIT, currentView, proposal.getHash());
		}
		newCommitQC.addVote(replicaID, selfCommitSig);

		while (!newCommitQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.COMMIT, currentView);
			if (msg == null) return false;
			if (verifyVoteMsg(msg, proposal.getHash())) {
				newCommitQC.addVote(msg.getSenderId(), msg.getPartialSignature());
			}
		}

		addThresholdSignature(newCommitQC, proposal.getHash());
		broadcastMessage(makeMsg(MsgType.DECIDE, null, newCommitQC));
		executeDecision(proposal);
		return true;
	}

	/** @return true if the view completed successfully, false on timeout */
	private boolean runReplicaPhase() throws InterruptedException {
		int leader = getLeader(currentView);
		// === PREPARE phase (replica) ===
		Message prepareMsg = pullMessage(MsgType.PREPARE, currentView);
		if (prepareMsg == null) return false;

		// Byzantine check: PREPARE must come from the current leader
		if (prepareMsg.getSenderId() != leader) return false;

		TreeNode proposal = prepareMsg.getTreeNode();

		// Equivocation protection: explicitly fail if already voted for a different proposal in this view
		if (proposal != null) {
			if (votedNodeThisView != null && !Arrays.equals(votedNodeThisView.getHash(), proposal.getHash())) {
				return false;
			}
			votedNodeThisView = proposal;
		}

		if (proposal != null) {
			storeNode(proposal);
			linkParent(proposal);
		}

		QuorumCertificate justifyQC = prepareMsg.getJustify();

		// Byzantine check: verify justifyQC before trusting it.
		// A Byzantine leader could forge a QC to manipulate the safeNode predicate.
		if (justifyQC != null
				&& (justifyQC.getViewNumber() >= currentView
					|| !justifyQC.verify(crypto, thresholdCrypto, quorumSize))) {
			justifyQC = null;
		}

		if (justifyQC != null && justifyQC.getNode() != null) {
			storeNode(justifyQC.getNode());
			linkParent(justifyQC.getNode());
			if (proposal != null) {
				if (Arrays.equals(proposal.getParentHash(), justifyQC.getNode().getHash())) {
					proposal.setParent(justifyQC.getNode());
				}
			}
		}

		boolean extendsFromJustify = (justifyQC == null || justifyQC.getNode() == null)
				|| proposal.extendsFrom(justifyQC.getNode());

		boolean safe = safeNode(proposal, justifyQC);
		if (proposal != null && extendsFromJustify && safe) {
			sendMessage(leader, makeVoteMsg(MsgType.PREPARE, proposal, null));
		} else {
            System.err.println("[HotStuff-" + replicaID + "] Dropped PREPARE! extends=" + extendsFromJustify + ", safe=" + safe);
        }

		Message preCommitMsg = pullMessage(MsgType.PRE_COMMIT, currentView);
		if (preCommitMsg == null) return false;

		// Byzantine check: PRE-COMMIT must come from leader and carry a valid prepareQC
		if (preCommitMsg.getSenderId() != leader) return false;

		//Ensure block is good before accepting it
		Block blk = proposal.getBlock();
		if (blk == null ||
			!decidedBlocks.contains(blk) ||
			!EVM.getInstance().executeBlock(blk, ownAddress, false))
			return false;

		QuorumCertificate rcvPrepareQC = preCommitMsg.getJustify();
		if (rcvPrepareQC != null
				&& rcvPrepareQC.matchingQC(MsgType.PREPARE, currentView)
				&& rcvPrepareQC.verify(crypto, thresholdCrypto, quorumSize)) {
			prepareQC = rcvPrepareQC;
			sendMessage(leader, makeVoteMsg(MsgType.PRE_COMMIT, proposal, null));
		}

		Message commitMsg = pullMessage(MsgType.COMMIT, currentView);
		if (commitMsg == null) return false;

		if (commitMsg.getSenderId() != leader) return false;

		QuorumCertificate rcvPrecommitQC = commitMsg.getJustify();
		if (rcvPrecommitQC != null
				&& rcvPrecommitQC.matchingQC(MsgType.PRE_COMMIT, currentView)
				&& rcvPrecommitQC.verify(crypto, thresholdCrypto, quorumSize)) {
			lockedQC = rcvPrecommitQC;
			sendMessage(leader, makeVoteMsg(MsgType.COMMIT, proposal, null));
		}

		Message decideMsg = pullMessage(MsgType.DECIDE, currentView);
		if (decideMsg == null) return false;

		if (decideMsg.getSenderId() != leader) return false;

		QuorumCertificate commitQC = decideMsg.getJustify();
		if (commitQC != null
				&& commitQC.matchingQC(MsgType.COMMIT, currentView)
				&& commitQC.verify(crypto, thresholdCrypto, quorumSize)
				&& commitQC.getNode() != null) {
			executeDecision(commitQC.getNode());
		}
		return true;
	}

	private void executeDecision(TreeNode node) {
		if (node == null)
			return;
		Block blk = node.getBlock();
		if (blk != null && !decidedBlocks.contains(blk)) {
			decidedBlocks.add(blk);
			EVM.getInstance().executeBlock(blk, ownAddress, true);
		}
	}

	private Block waitForBlock() {
		long remaining = remainingBlockMs();
		if (remaining <= 0)
			return null;

		// Wait as long as possible
		LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(remaining));

		// Then build block
		var txs = mempool.getTopTransactions(maxBlockGas);
		Block lastBlock = decidedBlocks.getLast();
		Block blk = new Block(lastBlock == null ? null : lastBlock.getBlockHash(), txs, null);

		if (!EVM.getInstance().executeBlock(blk, ownAddress, false))
			return null; //TODO: Instead spam test blocks until it works (needed?)

		return blk;
	}
}
