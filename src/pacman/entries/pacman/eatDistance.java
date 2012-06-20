package pacman.entries.pacman;

import static pacman.game.Constants.EAT_DISTANCE;

import java.util.ArrayList;

import pacman.game.Game;

/*
 * This class caches all the nodes within EAT_DISTANCE of it for the current maze
 */
public class eatDistance {	
	private class neighbourCache {
		private int[][] cache; //indexed by node id
		
		@SuppressWarnings("unchecked")
		public neighbourCache(int size) {
			cache = new int[size][];
		}
		
		public int[] getNeighbours(int node) {
			if (cache== null)
				return null;
			return cache[node];
		}
		
		public void storeNeighbours(int node, int[] nodes) {
			cache[node] = nodes;
		}
	}
	
	ArrayList<neighbourCache> cache; //indexed by the maze id
	
	public eatDistance() {
		cache = new ArrayList<neighbourCache>();
	}
	
	/*
	 * Return the nodes within Eat Distance of this node
	 */
	public int[] eatNodes(Game g, int node) {	
		if (cache.size() < g.getMazeIndex() + 1 || cache.get(g.getMazeIndex()) == null)
			cache.add(g.getMazeIndex(), new neighbourCache(g.getNumberOfNodes()));
		
		int[] result = cache.get(g.getMazeIndex()).getNeighbours(node);
		if (result == null) {
			result = _eatNodes(g, node);
			cache.get(g.getMazeIndex()).storeNeighbours(node, result);
		}
		
		return result;
	}
	
	/*
	 * Returns a list of the nodes within the EAT_DISTANCE of this node
	 */
	private int[] _eatNodes(Game game, ArrayList<Integer> edge) {
		ArrayList<Integer> processed = new ArrayList<Integer>();	
		int dist = EAT_DISTANCE;
		while (dist-- >= 0) {
			ArrayList<Integer> nextEdge = new ArrayList<Integer>();
			for (int n: edge) {
				processed.add(n);
				if (dist >= 0) {
					for (int a: game.getNeighbouringNodes(n))
						if (!processed.contains(a))
							nextEdge.add(a);
				}
			}
			edge = nextEdge;
		}
		int[] result = new int[processed.size()];
		int i=0;
		for (int n: processed)
			result[i++] = n;
		return result;
	}
	
	private int[] _eatNodes(Game game, int node) {
		ArrayList<Integer> edge = new ArrayList<Integer>();
		edge.add(node);
		return _eatNodes(game, edge);
	}
}
