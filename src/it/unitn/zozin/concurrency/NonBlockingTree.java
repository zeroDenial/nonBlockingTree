package it.unitn.zozin.concurrency;

import it.unitn.zozin.concurrency.Node.DummyNode;
import it.unitn.zozin.concurrency.Node.InternalNode;
import it.unitn.zozin.concurrency.Node.Leaf;
import it.unitn.zozin.concurrency.NonBlockingTree.TreeVisitor;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class NonBlockingTree<K extends Comparable<K>> extends AbstractSet<K> {

	private final InternalNode<K> root;
	private final DummyNode<K> DUMMY_NODE1;
	private final DummyNode<K> DUMMY_NODE2;
	private final AtomicInteger size = new AtomicInteger();

	private class SearchRes {
		InternalNode<K> gp;
		InternalNode<K> p;
		Leaf<K> l;
		Update<K> pupdate;
		Update<K> gpupdate;
	}

	public NonBlockingTree(K minKey, K maxKey) {
		if (minKey == null || maxKey == null)
			throw new NullPointerException();
		if (minKey.compareTo(maxKey) >= 0)
			throw new IllegalArgumentException();

		this.DUMMY_NODE1 = DummyNode.getInstance(minKey);
		this.DUMMY_NODE2 = DummyNode.getInstance(maxKey);

		this.root = InternalNode.getInstance(maxKey, DUMMY_NODE1, DUMMY_NODE2);
	}

	/**
	 * Search from root to leaf for the given key
	 */
	private SearchRes search(K key) {
		SearchRes r = new SearchRes();

		Node<K> cur = root;
		while (cur instanceof InternalNode) {
			// Remember parent of p
			r.gp = r.p;
			// Remember parent of l
			r.p = (InternalNode<K>) cur;
			// Remember update field of gp
			r.gpupdate = r.pupdate;
			// Remember update field of p
			r.pupdate = r.p.getUpdate();

			// Move down to appropriate child
			if (key.compareTo(cur.key) < 0)
				cur = r.p.getLeft();
			else
				cur = r.p.getRight();
		}

		r.l = (Leaf<K>) cur;

		return r;
	}

	public boolean find(K key) {
		SearchRes r = search(key);
		return r.l.key.equals(key);
	}

	public boolean insert(K key) {
		while (true) {
			SearchRes r = search(key);

			// Cannot insert duplicate key
			if (r.l.key.compareTo(key) == 0)
				return false;

			// Node already flagged, help the other and then retry the insert
			if (r.pupdate.state != Update.CLEAN) {
				help(r.pupdate);
				continue;
			}

			InternalNode<K> newInternal = createSubTree(r.l.key, key);

			InsertInfo<K> insertOp = new InsertInfo<K>(r.p, newInternal, r.l);

			// Try to set parent flag to insert flag
			if (r.p.setUpdate(r.pupdate, insertOp, Update.IFLAG)) {
				// IFLAG successful, finish insertion
				helpInsert(insertOp);
				return true;
			} else {
				// IFLAG failed, help who caused the failure
				help(r.p.getUpdate());
			}
		}
	}

	/**
	 * @return Subtree with 3 nodes: internal node, new key leaf, sibling leaf
	 *         (old leaf that will we replaced with this tree)
	 */
	private InternalNode<K> createSubTree(K k1, K k2) {
		Node<K> left, right;
		K max;

		if (k1.compareTo(k2) < 0) {
			left = getLeafInstance(k1);
			right = getLeafInstance(k2);
			max = k2;
		} else {
			// k1 == k2 not possible since duplicated key are not admitted
			left = getLeafInstance(k2);
			right = getLeafInstance(k1);
			max = k1;
		}

		return InternalNode.getInstance(max, left, right);
	}

	private Leaf<K> getLeafInstance(K key) {
		if (key.compareTo(DUMMY_NODE1.key) == 0)
			return DUMMY_NODE1;
		else if (key.compareTo(DUMMY_NODE2.key) == 0)
			return DUMMY_NODE2;
		else
			return Leaf.getInstance(key);
	}

	private void helpInsert(InsertInfo<K> insertOp) {
		// Substitute either left or right child of the parent tree with the new
		// subtree
		if (insertOp.p.setChild(insertOp.l, insertOp.newInternal)) {

			// Update size counter
			size.getAndIncrement();

			// Reset parent flag and unset reference to info record
			insertOp.p.setUpdate(new Update<K>(Update.IFLAG, insertOp), insertOp, Update.CLEAN);
		}
	}

	public boolean delete(K key) {
		while (true) {
			SearchRes r = search(key);

			// Key is not in the tree
			if (r.l.key.compareTo(key) != 0)
				return false;

			// If the grandparent state is not clean, help and then retry delete
			if (r.gpupdate.state != Update.CLEAN) {
				help(r.gpupdate);
				continue;
			}

			// If the parent state is not clean, help and then retry delete
			if (r.pupdate.state != Update.CLEAN) {
				help(r.pupdate);
				continue;
			}

			DeleteInfo<K> deleteOp = new DeleteInfo<K>(r.gp, r.p, r.l, r.pupdate);

			// Try to set grandparent flag to delete flag
			if (r.gp.setUpdate(r.gpupdate, deleteOp, Update.DFLAG)) {
				// DFLAG successful, if also the marking succeeds returns,
				// otherwise retry delete
				if (helpDelete(deleteOp))
					return true;
			} else {
				// DFLAG failed, help who caused the failure
				help(r.gp.getUpdate());
			}
		}
	}

	private boolean helpDelete(DeleteInfo<K> deleteOp) {
		// Try to mark the parent of the node to delete
		if (deleteOp.p.setUpdate(deleteOp.pupdate, deleteOp, Update.MARK)) {
			// MARK successful, finish deletion
			helpMarked(deleteOp);
			return true;
		} else {
			// MARK failed, help who caused the failure
			help(deleteOp.p.getUpdate());
			// Backtrack CAS because the delete has to be retried starting by
			// setting the grandparent DFLAG
			deleteOp.gp.setUpdate(deleteOp.gp.getUpdate(), deleteOp, Update.CLEAN);
			return false;
		}
	}

	private void helpMarked(DeleteInfo<K> deleteOp) {
		Node<K> other;

		// Set other to point to the sibling of the node to remove (deleteOp.l)
		if (deleteOp.p.getRight() == deleteOp.l)
			other = deleteOp.p.getLeft();
		else
			other = deleteOp.p.getRight();

		// Set the sibling of the node to remove, as a child of the grandparent
		if (deleteOp.gp.setChild(deleteOp.p, other)) {

			// Update size counter
			size.getAndDecrement();

			// Reset grandparent flag and unset reference to info record
			deleteOp.gp.setUpdate(new Update<K>(Update.DFLAG, deleteOp), deleteOp, Update.CLEAN);
		}
	}

	private void help(Update<K> update) {
		switch (update.state) {
			case Update.IFLAG :
				helpInsert((InsertInfo<K>) update.info);
				break;
			case Update.DFLAG :
				helpDelete((DeleteInfo<K>) update.info);
				break;
			case Update.MARK :
				helpMarked((DeleteInfo<K>) update.info);
				break;
		}
	}

	/**
	 * METHODS FOR COMPLIANCE WITH THE SET INTERFACE
	 */

	@Override
	public boolean add(K e) {
		return insert(e);
	}

	@Override
	public Iterator<K> iterator() {
		return new InorderIterator();
	}

	@Override
	public int size() {
		return size.get();
	}

	class InorderIterator implements Iterator<K> {

		final List<K> snapshot = new ArrayList<K>();
		final Iterator<K> listIter;

		private K last;

		public InorderIterator() {
			inVisit(new TreeVisitor<K>() {

				@Override
				public void visit(Node<K> node, int level) {
					if (node instanceof Leaf)
						snapshot.add(node.key);
				}
			});
			this.listIter = snapshot.iterator();
		}

		@Override
		public boolean hasNext() {
			return listIter.hasNext();
		}

		@Override
		public K next() {
			last = listIter.next();
			return last;
		}

		@Override
		public void remove() {
			NonBlockingTree.this.remove(last);
		}
	}

	/**
	 * Perform an in-order visit of the tree, including internal nodes
	 */
	void inVisit(TreeVisitor<K> v) {
		root.inVisit(v, 0);
	}

	/**
	 * Perform a pre-order visit of the tree, including internal nodes
	 */
	void preVisit(TreeVisitor<K> v) {
		root.preVisit(v, 0);
	}

	interface TreeVisitor<K extends Comparable<K>> {

		public void visit(Node<K> node, int level);
	}
}

/**
 * Operation information used by threads to help each other in completing the
 * update operation on the tree
 */
interface OperationInfo<K extends Comparable<K>> {
}

class InsertInfo<K extends Comparable<K>> implements OperationInfo<K> {

	InternalNode<K> p;
	InternalNode<K> newInternal;
	Leaf<K> l;

	public InsertInfo(InternalNode<K> p, InternalNode<K> newInternal, Leaf<K> l) {
		this.p = p;
		this.newInternal = newInternal;
		this.l = l;
	}

	@Override
	public String toString() {
		return "HELPINSERT: p=" + p + ", newSub=" + newInternal + ", l=" + l;
	}
}

class DeleteInfo<K extends Comparable<K>> implements OperationInfo<K> {

	InternalNode<K> gp;
	InternalNode<K> p;
	Leaf<K> l;
	Update<K> pupdate;

	public DeleteInfo(InternalNode<K> gp, InternalNode<K> p, Leaf<K> l, Update<K> pupdate) {
		this.gp = gp;
		this.p = p;
		this.l = l;
		this.pupdate = pupdate;
	}

	@Override
	public String toString() {
		return "HELPDELETE: gp=" + gp + ", p=" + p + ", l=" + l;
	}

}

class Update<K extends Comparable<K>> {

	public static final int CLEAN = 0;
	public static final int IFLAG = 1;
	public static final int DFLAG = 2;
	public static final int MARK = 3;

	int state;
	OperationInfo<K> info;

	public Update(int state, OperationInfo<K> info) {
		this.state = state;
		this.info = info;
	}

	@Override
	public String toString() {
		return "STATE: " + state + " INFO: " + info.toString();
	}
}

abstract class Node<K extends Comparable<K>> {
	
	public K key;
	
	Node(K key) {
		this.key = key;
	}
	
	public abstract void inVisit(TreeVisitor<K> v, int level);
	
	public abstract void preVisit(TreeVisitor<K> v, int level);
	
	@Override
	public String toString() {
		return "Leaf " + key.toString();
	}
	
	public static class Leaf<K extends Comparable<K>> extends Node<K> {

		Leaf(K key) {
			super(key);
		}
		
		public static <K extends Comparable<K>> Leaf<K> getInstance(K key) {
			return new Leaf<K>(key);
		}

		@Override
		public void inVisit(TreeVisitor<K> v, int level) {
			v.visit(this, level);
		}
		
		@Override
		public void preVisit(TreeVisitor<K> v, int level) {
			v.visit(this, level);
		}
	}


	public static class DummyNode<K extends Comparable<K>> extends Leaf<K> {

		private DummyNode(K key) {
			super(key);
		}

		public static <K extends Comparable<K>> DummyNode<K> getInstance(K dummyKey) {
			return new DummyNode<K>(dummyKey);
		}
	}
	
	public static class InternalNode<K extends Comparable<K>> extends Node<K> {
    
		private AtomicReference<Node<K>> left = new AtomicReference<Node<K>>();
		private AtomicReference<Node<K>> right = new AtomicReference<Node<K>>();
		private AtomicStampedReference<OperationInfo<K>> update = new AtomicStampedReference<OperationInfo<K>>(null, Update.CLEAN);

		public static <K extends Comparable<K>> InternalNode<K> getInstance(K key, Node<K> left, Node<K> right) {
			return new InternalNode<K>(key, left, right);
		}

		private InternalNode(K key, Node<K> left, Node<K> right) {
			super(key);
			this.left.set(left);
			this.right.set(right);
		}
		
		public boolean setChild(Node<K> expectedNode, Node<K> newNode) {
			if(newNode.key.compareTo(key) < 0)
				return this.left.compareAndSet(expectedNode, newNode);
			else
				return this.right.compareAndSet(expectedNode, newNode);
		}

		public boolean setUpdate(Update<K> expectedUpd, OperationInfo<K> newInfo, int newState) {
			return this.update.compareAndSet(expectedUpd.info, newInfo, expectedUpd.state, newState);
		}

		public Node<K> getLeft() {
			return left.get();
		}

		public Node<K> getRight() {
			return right.get();
		}

		public Update<K> getUpdate() {
			int[] state = new int[1];
			OperationInfo<K> i = update.get(state);
			return new Update<K>(state[0], i);
		}
		
		@Override
		public void inVisit(TreeVisitor<K> v, int level) {
			getLeft().inVisit(v, level+1);
			v.visit(this, level);
			getRight().inVisit(v, level+1);
		}
		
		@Override
		public void preVisit(TreeVisitor<K> v, int level) {
			v.visit(this, level);
			getLeft().preVisit(v, level+1);
			getRight().preVisit(v, level+1);
		}
		
		@Override
		public String toString() {
			return "Internal " + key.toString();
		}
	}
}