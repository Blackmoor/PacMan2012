package pacman.entries.pacman;

import static pacman.game.Constants.*;

import java.util.ArrayList;

import pacman.game.Game;
import pacman.game.Constants.GHOST;

/*
 * A set of utility functions that provides distance and direction information based on the current game state
 */
public class maze {
	private static final int	MAX_DISTANCE = 350;
	private Game 			game;
	private pacmanInfo[]	pacman;	//Distance of pacman from each node - updated each game tick
	private ghostInfo[][]	ghosts; //Distance of each ghost from each node - updated each game tick
	private float[]			block; //The odds of each node being blocked by ghosts
	private boolean[]		safe; //Set to true if the node can be safely reached by the pacman
	private boolean[]		accessible; //Set to true if the pacman can reach this node before a ghost can
	private int				safeNodes; //The number of safe nodes
	private ArrayList<ArrayList<Integer>>	eventHorizon; //For each ghost, the nodes where it meets the pacman as a hunter
	
	public maze(Game g) {
		game = g;
		walkMaze();
	}
	
	private class ghostInfo {
		public int		distance;	// Distance to the node
		public MOVE		dir;		//Direction of arrival
		public boolean	hunter;		//true if the ghost is hunting, false if edible
		public int		turns;		//How many turn choices ghost makes to get here
		
		public ghostInfo() {
			this.distance = MAX_DISTANCE;
			this.dir = MOVE.NEUTRAL;
			this.hunter = false;
			this.turns = 0;
		}
	}
	
	private class pacmanInfo {
		public int		distance;	//Distance to node
		public MOVE		dir;		//Direction of arrival
		
		public pacmanInfo() {
			this.distance = MAX_DISTANCE;
			this.dir = MOVE.NEUTRAL;
		}
	}
	
	public class nodeMove {
		int node;
		MOVE move;
		GHOST ghost;
		int turns;
		
		public nodeMove(int n, MOVE m, GHOST g, int t) {
			node = n;
			move = m;
			ghost = g;
			turns = t;
		}
		
		public nodeMove(int n, MOVE m, GHOST g) {
			node = n;
			move = m;
			ghost = g;
			turns = -1;
		}
		
		public nodeMove(int n, MOVE m) {
			node = n;
			move = m;
			turns = -1;
		}
		
		@Override
	    public boolean equals (Object x) {
	      if (x instanceof nodeMove)
	         return ((nodeMove)x).node == node && ((nodeMove)x).move == move && ((nodeMove)x).ghost == ghost;
	      return false;
	    }

	    @Override
	    public int hashCode ()
	    {
	        return node;
	    }
	}
	
	private int ghostIndex(GHOST g)
	{
		return g.ordinal();
	}
	
	/*
	 * How many valid move choices does a ghost have at this node
	 */
	private int countChoices(int node)
	{
		int choices = game.getNeighbouringNodes(node).length;
		if (choices > 2)
			return choices - 1;	//Can't go backwards so one less choice
		return 0; //if we are not at a junction we have no choices (only 1 valid move)
	}
	
	private boolean isPowerPill(int node) {
		for (int pp: game.getActivePowerPillsIndices())
			if (pp == node)
				return true;
		return false;
	}
	
	/*
	 * Hunter ghosts block the maze walk
	 */
	private GHOST isBlocked(int node) {
		for (GHOST g: GHOST.values()) {
			/*
			 * Only hunter ghosts can block and then only if they are
			 * 1. Get to the node before us from a different direction
			 * 2. Are travelling in the same direction and we are within 2 nodes of the eat distance from us as a game.reverse would kill us
			 */
			if (ghosts[ghostIndex(g)][node].hunter &&
					((ghosts[ghostIndex(g)][node].dir != pacman[node].dir && ghosts[ghostIndex(g)][node].distance - pacman[node].distance <= EAT_DISTANCE) ||
					 (ghosts[ghostIndex(g)][node].dir == pacman[node].dir && ghosts[ghostIndex(g)][node].distance < pacman[node].distance && pacman[node].distance - ghosts[ghostIndex(g)][node].distance <= EAT_DISTANCE + 2)))
				return g;
		}
		return null;
	}
	
	/*
	 * Using a breadth first search we spread out from the edge of our reachable area for each ghost and the pacman
	 * At each node we record the distance travelled and the arrival direction
	 */
	private void walkMaze() {
		ArrayList<nodeMove>	pacmanEdge = new ArrayList<nodeMove>();
		ArrayList<nodeMove>	ghostsEdge = new ArrayList<nodeMove>();
		pacman = new pacmanInfo[game.getNumberOfNodes()];
		ghosts = new ghostInfo[NUM_GHOSTS][game.getNumberOfNodes()];
		accessible = new boolean[game.getNumberOfNodes()];
		safe = new boolean[game.getNumberOfNodes()];
		block = new float[game.getNumberOfNodes()];
		safeNodes = 0;
		eventHorizon = new ArrayList<ArrayList<Integer>>();
		
		for (int i=0; i<game.getNumberOfNodes(); i++) {
			pacman[i] = new pacmanInfo();
			for (int g=0; g<NUM_GHOSTS; g++)
				ghosts[g][i] = new ghostInfo();
		}
		
		/*
		 * Populate starting edges
		 */
		int distance = 0;
		pacmanEdge.add(new nodeMove(game.getPacmanCurrentNodeIndex(), MOVE.NEUTRAL));
		for (GHOST g: GHOST.values()) {
			eventHorizon.add(new ArrayList<Integer>());
			if (game.getGhostLairTime(g) == 0)
				ghostsEdge.add(new nodeMove(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g), g, 0));
		}
		eventHorizon.add(new ArrayList<Integer>()); //This entry is for power pill block points
		
		while (ghostsEdge.size() == 0) { //The the ghosts must be in the lair
			distance++;
			for (GHOST g: GHOST.values())
				if (game.getGhostLairTime(g) == distance)
					ghostsEdge.add(new nodeMove(game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, g, 0));
		}
		//First store the ghost distances
		while (ghostsEdge.size() > 0) { //Keep walking while we have somewhere to go
			//Record distance travelled to all edge nodes	
			for (nodeMove n: ghostsEdge) {
				int gi = ghostIndex(n.ghost);
				ghosts[gi][n.node].distance = distance;
				ghosts[gi][n.node].dir = n.move;
				ghosts[gi][n.node].hunter = (ghosts[gi][n.node].distance > game.getGhostEdibleTime(n.ghost));
				ghosts[gi][n.node].turns = n.turns;
			}
				
			/*
			 * New edge is 1 move from each point on the current edge
			 * Ghosts stop when they reach a node they have already been to
			 */
			ArrayList<nodeMove>	nextGhosts = new ArrayList<nodeMove>();			
			for (nodeMove gi: ghostsEdge) {
				int edibleTime = game.getGhostEdibleTime(gi.ghost) - distance;
				if (edibleTime <= 0 || edibleTime%GHOST_SPEED_REDUCTION != 0) {
					for (MOVE gm: game.getPossibleMoves(gi.node, gi.move)) {
						int node = game.getNeighbour(gi.node, gm);
						if (ghosts[ghostIndex(gi.ghost)][node].distance == MAX_DISTANCE) {
							nodeMove next = new nodeMove(node, gm, gi.ghost, gi.turns + countChoices(gi.node));
							if (!nextGhosts.contains(next))
								nextGhosts.add(next);
						}
					}
				} else //edible ghost doesn't move - add in the current location
					nextGhosts.add(gi);
			}
			
			
			ghostsEdge = nextGhosts;
			distance++;
			/*
			 * Add in a new entry if a ghost is due to leave the lair
			 */
			for (GHOST g: GHOST.values())
				if (game.getGhostLairTime(g) > 0 && game.getGhostLairTime(g) == distance)
					ghostsEdge.add(new nodeMove(game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, g, 0));
		}	
		
		/*
		 * Store the chance that each node is blocked by combining the odds of each hunting ghost
		 */
		for (int node=0; node<block.length; node++) {
			float chance = 1f;
			for (GHOST g: GHOST.values())
				if (ghosts[ghostIndex(g)][node].hunter)
					chance *= (1f-1f/(1+ghosts[ghostIndex(g)][node].turns));
			block[node] = 1f-chance;
		}
		
		//Now store the pacman distances - the pacman is blocked by hunting ghosts
		distance = 0;
		while (pacmanEdge.size() > 0) {
			for (nodeMove n: pacmanEdge) {
				pacman[n.node].distance = distance;
				pacman[n.node].dir = n.move;
				accessible[n.node]= true; 
				safeNodes++;
			}
			/*
			 * New edge is 1 move from each point on the current edge
			 * Pacman will not move onto power pills, hunting ghosts or on a node we have already been to
			 */
			ArrayList<nodeMove>	nextPacman = new ArrayList<nodeMove>();
			for (nodeMove n: pacmanEdge)
				for (MOVE d: game.getPossibleMoves(n.node)) {
					int node = game.getNeighbour(n.node, d);
					if (pacman[node].distance == MAX_DISTANCE) {
						GHOST g = isBlocked(n.node);
						if (g != null)
							eventHorizon.get(g.ordinal()).add(n.node);
						else if (isPowerPill(n.node)) {
							eventHorizon.get(NUM_GHOSTS).add(n.node);
							safe[n.node] = true;
							accessible[n.node]= true; 
							safeNodes++;
						} else if (!nextPacman.contains(node))
							nextPacman.add(new nodeMove(node, d));
					}
				}
			
			pacmanEdge = nextPacman;
			distance++;
		}
		
		/*
		 * Mark all the safe nodes
		 * A node is safe if it is accessible and not on a part of a blocked path that can become trapped
		 */
		for (int node=0; node<block.length; node++)
			if (accessible[node] && weighting(node) > 0)
				safe[node] = true;
		
		int c = 0;
		for (ArrayList<Integer> gev: eventHorizon) {
			if (c < NUM_GHOSTS)
				for (int node: gev) {
					int jn = nearestJunction(node);
					if (jn != -1) {
						//Find nearest hunting ghost to this jn
						int best = Integer.MAX_VALUE;
						int b = -1;
						for (GHOST g: GHOST.values()) {
							if (isHunter(g, jn) && ghostDistance(g, jn) < best) {
								best = ghostDistance(g, jn);
								b = g.ordinal();
							}
						}
						int safeDistance = (best - pacmanDistance(jn)-1)/2;
						int[] path = game.getShortestPath(jn, node);
						if (b == c || safeDistance > path.length - 1)
							safeDistance = path.length - 1;
						int i = 1;
						while (i <= safeDistance) {
							safe[path[i]] = accessible[path[i]];
							i++;
						}
					}
				}
			c++;
		}
	}
	
	/*
	 * Find the nearest junction to this node in the accessible zone
	 */
	private int nearestJunction(int node) {	
		int prev = -1;
		while (!game.isJunction(node)) {
			boolean trapped = true;
			for (int next: game.getNeighbouringNodes(node))
				if (next != prev && accessible[next]) {
					prev = node;
					node = next;
					trapped = false;
					break;
				}
			if (trapped)
				return -1;
		}
		return node;
	}
	
	/*
	 * Find the node where the ghost will be in N moves time
	 * Stop if we reach a junction
	 */
	private nodeMove getGhostNode(int node, MOVE dir, int ticks) {
		nodeMove result = new nodeMove(node, dir);
		result.turns = 0;
		while (!game.isJunction(result.node) && ticks-- > 0) {
			MOVE[] moves = game.getPossibleMoves(result.node, result.move);
			if (moves.length == 0)
				return result;
			result.turns++;
			result.move = moves[0];
			result.node = game.getNeighbour(result.node, result.move);
		}		
		return result;
	}
	
	/*
	 * Returns the node the pacman should head for to eat the given ghost
	 * result[0] = node
	 * result[1] = distance before reaching a junction
	 * result[2] = distance after reaching a junction
	 * This function can be called when the ghost is not edible to test how quickly we could reach it.
	 * In this case we have to reverse its direction of travel as a power pill would do this.
	 */
	private int[] getChaseInfo(int from, GHOST g, boolean testing) {
		int before; //Distance travelled to catch ghost before jn
		int after; //Distance travelled to catch ghost after jn
		int target;
		int pacjndist;
		int pacdist;
		boolean useCache; // true if we can use the cached distances (pacman and ghosts)
		MOVE ghostDir = game.getGhostLastMoveMade(g);

		if (testing) { //Assume ghost will reverse direction
			ghostDir = ghostDir.opposite();
			if (game.getNeighbour(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g)) == -1)
				for (MOVE m: game.getPossibleMoves(game.getGhostCurrentNodeIndex(g)))
					if (m != ghostDir) {
						ghostDir = m.opposite();
						break;
					}
		}
		
		useCache = (!testing && from == game.getPacmanCurrentNodeIndex());			
		nodeMove jn = getGhostNode(game.getGhostCurrentNodeIndex(g), ghostDir, Integer.MAX_VALUE);
		
		if (useCache) {	
			pacjndist = pacman[jn.node].distance;
			pacdist = pacman[game.getGhostCurrentNodeIndex(g)].distance;
		} else {
			pacjndist = (int)game.getDistance(from, jn.node, DM.PATH);
			pacdist = (int)game.getDistance(from, game.getGhostCurrentNodeIndex(g), DM.PATH);
		}
		
		if (pacjndist - EAT_DISTANCE <= jn.turns*2) {
			//Work out if pacman gets to the ghost in the same direction it is travelling			
			MOVE pacDir = MOVE.NEUTRAL;
			if (useCache) 
				pacDir = pacman[game.getGhostCurrentNodeIndex(g)].dir;
			else {
				int[] pacmanPath = game.getShortestPath(from, game.getGhostCurrentNodeIndex(g));
				if (pacmanPath.length >= 2)
					for (MOVE m: MOVE.values()) {
						if (game.getNeighbour(pacmanPath[pacmanPath.length-2], m) == pacmanPath[pacmanPath.length-1])
							pacDir = m;
					}
				else
					pacDir = MOVE.NEUTRAL;
			}
			
			if (pacdist > EAT_DISTANCE) {
				if (pacDir == ghostDir)
					before = 2*(pacdist - EAT_DISTANCE);
				else
					before = 2*(pacdist - EAT_DISTANCE)/3;
			} else
				before = 0;
			after = 0;
			target = getGhostNode(game.getGhostCurrentNodeIndex(g), ghostDir, before/2).node; //Ghost moves at half speed
		} else {
			//Work out if pacman gets to the junction in the same direction as the ghost gets to the jn		
			MOVE pacDir = MOVE.NEUTRAL;
			if (useCache) 
				pacDir = pacman[jn.node].dir;
			else {
				int[] pacmanPath = game.getShortestPath(from, jn.node);
				if (pacmanPath.length >= 2) {
					for (MOVE m: MOVE.values())
						if (game.getNeighbour(pacmanPath[pacmanPath.length-2], m) == pacmanPath[pacmanPath.length-1])
							pacDir = m;
				} else
					pacDir = MOVE.NEUTRAL;
			}
			
			before = jn.turns * 2;
			if (pacDir == jn.move)
				after = 2*(pacdist - EAT_DISTANCE) - before;
			else
				after = 2*(pacjndist - EAT_DISTANCE - before);
			target = jn.node;
		}
		
		if (testing || safe[target]) {
			int[] result = new int[3];
			result[0] = target;
			result[1] = before;
			result[2] = after;
			return result;
		}
		
		return null;
	}
	
	/*
	 * chaseOrder - returns a list of edible ghosts along with the target node and distance travelled
	 * The first entry is the first to be eaten
	 * 
	 * This is an estimate since working out all options is not possible in 40ms
	 * Once a ghost is eaten it will respawn and at some point will add interference to our calculations.
	 */
	private ArrayList<nodeMove> chaseOrder(int from, ArrayList<GHOST> edible, int distance, int interference, int depth) {
		ArrayList<nodeMove> best = new ArrayList<nodeMove>();		
		
		for (GHOST g: edible) {
			ArrayList<nodeMove> current = new ArrayList<nodeMove>();
			int cutoff;
			if (game.getGhostEdibleTime(g) == 0)
				cutoff = (int)(EDIBLE_TIME*(Math.pow(EDIBLE_TIME_REDUCTION, game.getCurrentLevel())));
			else
				cutoff = game.getGhostEdibleTime(g);
			int end = LEVEL_LIMIT - game.getCurrentLevelTime();
			if (cutoff > end)
				cutoff = end;
			
			ArrayList<GHOST> remaining = new ArrayList<GHOST>();
			for (GHOST e: edible)
				if (e != g)
					remaining.add(e);			
			int[] chase = getChaseInfo(from, g, game.getGhostEdibleTime(g) == 0);
			if (chase != null) {
				//Adjust before and after values based on distance travelled so far
				if (distance > chase[1]) {
					chase[2] += chase[1];
					chase[1] = 0;
				} else {
					chase[1] -= distance;
					chase[2] += distance;
				}
				int total = distance + chase[1] + chase[2]*depth;
				if (interference > 0 && 3*total/2-interference >= game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostInitialNodeIndex(), game.getGhostLastMoveMade(g), DM.PATH))					
					total += 50;
				if (total <= cutoff) {					
					current.add(new nodeMove(chase[0], MOVE.NEUTRAL, g, total));
					current.addAll(chaseOrder(game.getGhostCurrentNodeIndex(g), remaining, total,
							(interference==-1)?total + (int)(COMMON_LAIR_TIME*(Math.pow(LAIR_REDUCTION,game.getCurrentLevel()))):interference, depth+1));
				}
			}
			/*
			 * Check to see how many ghosts we can eat, if it is the best store the result
			 */
			if (best == null || chaseScore(best) < chaseScore(current))
				best = current;
		}
		
		return best;
	}
	
	/*
	 * public getter routines
	 */
	public boolean isHunter(GHOST g, int node) {
		return ghosts[ghostIndex(g)][node].hunter;
	}
	
	public int turns(GHOST g, int node) {
		return ghosts[ghostIndex(g)][node].turns;
	}
	
	public int ghostDistance(GHOST g, int node) {
		return ghosts[ghostIndex(g)][node].distance;
	}
	
	public MOVE ghostDir(GHOST g, int node) {
		return ghosts[ghostIndex(g)][node].dir;
	}
	
	public int pacmanDistance(int node) {
		return pacman[node].distance;
	}
	
	public MOVE pacmanDir(int node) {
		return pacman[node].dir;
	}
	
	public ArrayList<ArrayList<Integer>> eventHorizon() {
		return eventHorizon;
	}
	
	public boolean isSafe(int node) {
		return safe[node];
	}
	
	public boolean hasAccess(int node) {
		return accessible[node];
	}
	
	public int safeNodes() {
		return safeNodes;
	}
	
	public float weighting(int node) {
		return 1f - block[node];
	}
	
	/*
	 * public utility functions
	 */
	
	/*
	 * Take the list of ghosts to eat, score 1000 for each eaten ghost and then 1000-distance travelled
	 */
	public int chaseScore(ArrayList<nodeMove> order) {
		int score = 0;
		int dist = 1000;
		for (nodeMove nm: order) {
				score += 1000;
				dist = nm.turns;
			}
		score += 1000-dist;
		return score;
	}
	
	public ArrayList<nodeMove> chaseOrder(ArrayList<GHOST> edible) {
		return chaseOrder(game.getPacmanCurrentNodeIndex(), edible, 0, -1, 1);
	}
}
