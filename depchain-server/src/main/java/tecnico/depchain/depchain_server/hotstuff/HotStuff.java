package tecnico.depchain.depchain_server.hotstuff;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import tecnico.depchain.depchain_common.broadcasts.BestEffortBroadcast;
import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

public class HotStuff {
	private final int replicaID;
	private final int numReplicas;
	private final int quorumSize; // n - f
	private final BestEffortBroadcast broadcast;
	private final CryptoService crypto;
	private final ThresholdCrypto thresholdCrypto;

	// Protocol state (Algorithm 2)
	private volatile int currentView = 1;
	private QuorumCertificate prepareQC = null;
	private QuorumCertificate lockedQC = null;
	private TreeNode votedNodeThisView = null;

	// Tree of proposals
	private final Map<String, TreeNode> nodeStore = new HashMap<>();

	// Decided commands (the "blockchain")
	private final List<String> decidedCommands = new ArrayList<>();
    private final List<String> newlyDecidedThisView = new ArrayList<>();

	// Pending commands to propose (set by upper layer)
	private final BlockingQueue<String> pendingCommands = new LinkedBlockingQueue<>();

	// Message queue for sequential processing
	private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();
	private final List<Message> outOfOrderBuffer = new ArrayList<>();

	// Callback for decided commands
	private Consumer<String> onDecide = null;
	private final ConsensusUpcall upcall;

	// Outgoing message filter for Byzantine testing (Step 5).
	// Applied before every send/broadcast. Return null to drop the message.
	// Takes (destinationId, originalMessage) and returns modifiedMessage.
	private volatile BiFunction<Integer, Message, Message> outgoingFilter = null;

	// Protocol thread
	private Thread protocolThread;
	private volatile boolean running = false;

	// --- View timeout / Pacemaker (Step 4) ---
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
	 * @param keys            List of n shared HMAC keys (index i = shared key with replica i)
	 * @param crypto          CryptoService for Ed25519 signing/verification (Step 5)
	 * @param thresholdCrypto ThresholdCrypto for threshold QC signatures (nullable)
	 * @param upcall          Upcall for notifying the application layer on DECIDE completion
	 */
	public HotStuff(
			int replicaID, String host, int basePort, int numReplicas,
			List<SecretKey> keys, CryptoService crypto, ThresholdCrypto thresholdCrypto, ConsensusUpcall upcall)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.replicaID = replicaID;
		this.upcall = upcall;
		this.numReplicas = numReplicas;
		this.crypto = crypto;
		this.thresholdCrypto = thresholdCrypto;
		int f = (numReplicas - 1) / 3;
		this.quorumSize = numReplicas - f;

		List<InetSocketAddress> locals = new ArrayList<>();
		List<InetSocketAddress> remotes = new ArrayList<>();
		List<SecretKey> peerKeys = new ArrayList<>();

		for (int j = 0; j < numReplicas; j++) {
			if (j == replicaID)
				continue;

			int localPort = basePort + replicaID * numReplicas + (j < replicaID ? j : j - 1);
			locals.add(new InetSocketAddress(host, localPort));

			int remotePort = basePort + j * numReplicas + (replicaID < j ? replicaID : replicaID - 1);
			remotes.add(new InetSocketAddress(host, remotePort));

			peerKeys.add(keys.get(j));
		}

		SecretKey ownKey = keys.get(replicaID);
		broadcast = new BestEffortBroadcast(this::handleMsg, this::handleMsg, locals, ownKey, remotes, peerKeys);
	}

	/** Backward-compatible constructor (no upcall instance). */
	public HotStuff(
			int replicaID, String host, int basePort, int numReplicas,
			List<SecretKey> keys, CryptoService crypto, ThresholdCrypto thresholdCrypto)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this(replicaID, host, basePort, numReplicas, keys, crypto, thresholdCrypto, null);
	}

	/** Backward-compatible constructor (no threshold crypto). */
	public HotStuff(
			int replicaID, String host, int basePort, int numReplicas,
			List<SecretKey> keys, CryptoService crypto)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this(replicaID, host, basePort, numReplicas, keys, crypto, null, null);
	}

	// --- Public interface ---

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

	public void propose(String command) {
		this.pendingCommands.offer(command);
	}

	public List<String> getDecidedCommands() {
		return new ArrayList<>(decidedCommands);
	}

	public void setOnDecide(Consumer<String> callback) {
		this.onDecide = callback;
	}

	public int getCurrentView() {
		return currentView;
	}

	public void setBaseTimeout(long timeoutMs) {
		this.baseTimeoutMs = timeoutMs;
		this.viewTimeoutMs = timeoutMs;
	}

	/**
	 * Install a filter applied to every outgoing message before it is sent.
	 * The filter may modify the message or return null to suppress it entirely.
	 * Intended for Byzantine fault injection during testing.
	 */
	public void setOutgoingFilter(BiFunction<Integer, Message, Message> filter) {
		this.outgoingFilter = filter;
	}

	// --- Network layer ---

	private void handleMsg(byte[] data, InetSocketAddress remote) {
		byte[] msgBytes = new byte[data.length - 1];
		System.arraycopy(data, 1, msgBytes, 0, msgBytes.length);

		Message msg = Message.deserialize(msgBytes);
		if (msg != null) {
			// Step 5: Verify the global message signature
			if (msg.getMessageSignature() != null) {
				if (!crypto.verify(msg.getSenderId(), msg.getSignableBytes(), msg.getMessageSignature())) {
					return; // Invalid signature, drop silently
				}
			} else {
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
		for (int i = 0; i < numReplicas; i++) {
			sendMessage(i, msg);
		}
	}

	// --- Timeout helpers ---

	private void startViewTimer() {
		viewDeadline = System.currentTimeMillis() + viewTimeoutMs;
	}

	private long remainingMs() {
		return Math.max(0, viewDeadline - System.currentTimeMillis());
	}

	// --- Protocol helpers ---

	/**
	 * Pull a message of the given type and view from the queue.
	 * Returns null if the view deadline expires before a matching message arrives.
	 */
	private Message pullMessage(MsgType type, int viewNumber) throws InterruptedException {
		for (int i = 0; i < outOfOrderBuffer.size(); i++) {
			Message msg = outOfOrderBuffer.get(i);
			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return outOfOrderBuffer.remove(i);
			}
		}
		while (true) {
			long remaining = remainingMs();
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
		if (senderId < 0 || senderId >= numReplicas) return false;
		if (senderId == replicaID) return false;
		if (thresholdCrypto != null) {
			byte[] voteData = CryptoService.buildVoteData(msg.getType(), msg.getViewNumber(), expectedNodeHash);
			return thresholdCrypto.verifyPartial(senderId, voteData, msg.getPartialSignature());
		}
		return crypto.verifyVote(
				senderId, msg.getType(), msg.getViewNumber(),
				expectedNodeHash, msg.getPartialSignature());
	}

	/**
	 * safeNode predicate (Algorithm 1, lines 25-27).
	 */
	private boolean safeNode(TreeNode node, QuorumCertificate qc) {
		if (lockedQC == null)
			return true;

		boolean extendsLocked = node.extendsFrom(lockedQC.getNode());
		boolean higherView = qc != null && qc.getViewNumber() > lockedQC.getViewNumber();

		return extendsLocked || higherView;
	}

	private TreeNode createLeaf(TreeNode parent, String command) {
		TreeNode leaf = new TreeNode(parent, command);
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

	// --- Protocol main loop (with timeout / nextView interrupt) ---

	private void protocolLoop() {
		while (running) {
			startViewTimer();
			boolean viewSucceeded = false;
            newlyDecidedThisView.clear();

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

                    // Trigger upcalls AFTER view transition!
                    List<String> toUpcall = new ArrayList<>(newlyDecidedThisView);
                    newlyDecidedThisView.clear();
                    for (String cmd : toUpcall) {
                        if (onDecide != null) onDecide.accept(cmd);
                        if (upcall != null) upcall.onDecide(cmd);
                    }
				}
			}
		}
	}

	/**
	 * Send NEW_VIEW carrying our highest prepareQC to the next view's leader.
	 * Called on both successful completion and timeout (Algorithm 2, line 36).
	 */
	private void sendNewViewToNextLeader() {
		int nextLeader = getLeader(currentView + 1);
		sendMessage(nextLeader, makeMsg(MsgType.NEW_VIEW, null, prepareQC));
	}

	/** @return true if the view completed successfully, false on timeout */
	private boolean runLeaderPhase() throws InterruptedException {
		// === PREPARE phase (leader) ===
		QuorumCertificate highQC = null;

		if (currentView == 1) {
			// Bootstrap: no NEW_VIEW messages needed
		} else {
			Map<Integer, Message> newViews = new HashMap<>();

			while (newViews.size() < quorumSize) {
				Message msg = pullMessage(MsgType.NEW_VIEW, currentView - 1);
				if (msg == null) return false;
				newViews.put(msg.getSenderId(), msg);
			}

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
		}

		// Create new proposal
		TreeNode parentNode = highQC != null ? highQC.getNode() : null;
		if (parentNode != null)
			linkParent(parentNode);

		String cmd = waitForCommand();
		if (cmd == null) return false;

		TreeNode proposal = createLeaf(parentNode, cmd);
		broadcastMessage(makeMsg(MsgType.PREPARE, proposal, highQC));

		boolean selfVote = safeNode(proposal, highQC);

		// === Collect PREPARE votes -> form prepareQC ===
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

		// === PRE-COMMIT phase ===
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

		// === COMMIT phase ===
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

		// === DECIDE phase ===
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
				return false; // Leader equivocated!
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

		// === PRE-COMMIT phase (replica) ===
		Message preCommitMsg = pullMessage(MsgType.PRE_COMMIT, currentView);
		if (preCommitMsg == null) return false;

		// Byzantine check: PRE-COMMIT must come from leader and carry a valid prepareQC
		if (preCommitMsg.getSenderId() != leader) return false;

		QuorumCertificate rcvPrepareQC = preCommitMsg.getJustify();
		if (rcvPrepareQC != null
				&& rcvPrepareQC.matchingQC(MsgType.PREPARE, currentView)
				&& rcvPrepareQC.verify(crypto, thresholdCrypto, quorumSize)) {
			prepareQC = rcvPrepareQC;
			sendMessage(leader, makeVoteMsg(MsgType.PRE_COMMIT, proposal, null));
		}

		// === COMMIT phase (replica) ===
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

		// === DECIDE phase (replica) ===
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

	/**
	 * Wait for a command to propose, respecting the view deadline.
	 * Returns null if no command arrives before the view expires.
	 */
	private String waitForCommand() throws InterruptedException {
		long remaining = remainingMs();
		if (remaining <= 0)
			return null;

		return pendingCommands.poll(remaining, TimeUnit.MILLISECONDS);
	}

	private void executeDecision(TreeNode node) {
		if (node == null)
			return;
		String cmd = node.getCommand();
		if (cmd != null && !decidedCommands.contains(cmd)) {
			decidedCommands.add(cmd);
            newlyDecidedThisView.add(cmd);
		}
	}
}
