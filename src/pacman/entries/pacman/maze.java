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
	private ArrayList<ArrayList<Integer>>	eventHorizon; //For each ghost, the nodes where it meets the pacman as a hunter
	private eatDistance		cache;
	
	public maze(Game g, eatDistance c) {
		game = g;
		cache = c;
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
	 * Using a breadth first search we spread out from the edge of our reachable area for each ghost and the pacman
	 * At each node we record the distance travelled and the arrival direction
	 * A power pill or a hunting ghost blocks the pacman
	 * A pacman blocks an edible ghost
	 */
	private void walkMaze() {
		ArrayList<nodeMove>	pacmanEdge = new ArrayList<nodeMove>();
		ArrayList<nodeMove>	ghostsEdge = new ArrayList<nodeMove>();
		pacman = new pacmanInfo[game.getNumberOfNodes()];
		ghosts = new ghostInfo[NUM_GHOSTS][game.getNumberOfNodes()];
		accessible = new boolean[game.getNumberOfNodes()];
		safe = new boolean[game.getNumberOfNodes()];
		block = new float[game.getNumberOfNodes()];
		eventHorizon = new ArrayList<ArrayList<Integer>>();
		
		for (int i=0; i<game.getNumberOfNodes(); i++) {
			pacman[i] = new pacmanInfo();
			for (int g=0; g<NUM_GHOSTS; g++)
				ghosts[g][i] = new ghostInfo();
		}
		
		/*
		 * Populate starting nodes
		 */
		int ticks = 0; //How far the pacman has moved, the ghosts have move EAT_DISTANCE less
		for (GHOST g: GHOST.values()) {
			eventHorizon.add(new ArrayList<Integer>());
			if (game.getGhostLairTime(g) == 0)
				ghostsEdge.add(new nodeMove(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g), g, 0));
		}
		eventHorizon.add(new ArrayList<Integer>()); //This entry is for power pill block points
		
		int[]	hunterEat = new int[game.getNumberOfNodes()]; //The nodes at the eat range from each hunting ghost		
		pacmanEdge.add(new nodeMove(game.getPacmanCurrentNodeIndex(), MOVE.NEUTRAL));
		
		//BFS routine - expands ghost positions and pacman positions at the same time
		while (ghostsEdge.size() > 0 || pacmanEdge.size() > 0) { //Keep walking while we have somewhere to go
			//Record distance travelled to all edge nodes	
			for (nodeMove n: ghostsEdge) {
				int gi = n.ghost.ordinal();
				ghosts[gi][n.node].distance = ticks;
				ghosts[gi][n.node].dir = n.move;
				ghosts[gi][n.node].hunter = (ghosts[gi][n.node].distance >= game.getGhostEdibleTime(n.ghost));
				ghosts[gi][n.node].turns = n.turns;
			}
			ArrayList<Integer> pacmanEat = new ArrayList<Integer>();
			for (nodeMove n: pacmanEdge) {
				pacman[n.node].distance = ticks;
				pacman[n.node].dir = n.move;
				accessible[n.node]= true; 				
				for (int e: cache.eatNodes(game, n.node))
					pacmanEat.add(e);
			}
				
			/*
			 * New edge is 1 move from each point on the current edge
			 * Ghosts stop when they reach a node they have already been to
			 */
			ArrayList<nodeMove>	nextGhosts = new ArrayList<nodeMove>();			
			
			for (int i=0; i<hunterEat.length; i++)
				hunterEat[i] = -1;
			
			for (nodeMove gi: ghostsEdge) {
				int edibleTime = game.getGhostEdibleTime(gi.ghost) - ticks;
				if (edibleTime <= 0 || edibleTime%GHOST_SPEED_REDUCTION != 0) {
					for (MOVE gm: game.getPossibleMoves(gi.node, gi.move)) {
						int node = game.getNeighbour(gi.node, gm);
						boolean eaten =  (edibleTime > 1 && pacmanEat.contains(node));
						if (!eaten && ghosts[gi.ghost.ordinal()][node].distance == MAX_DISTANCE) {
							nodeMove next = new nodeMove(node, gm, gi.ghost, gi.turns + countChoices(gi.node));
							if (!nextGhosts.contains(next)) {
								nextGhosts.add(next);
								if (edibleTime <= 1) {
									for (int n: cache.eatNodes(game, node))
										hunterEat[n] = gi.ghost.ordinal();
								}
							}
						}
						}	
				} else { //edible ghost doesn't move - add in the current location
					boolean eaten =  (edibleTime > 1 && pacmanEat.contains(gi.node));
					if (!eaten && !nextGhosts.contains(gi))
						nextGhosts.add(gi);
				}
			}
			
			/*
			 * Work out new edge - 1 move from each point on the current edge
			 * Pacman will not move off power pills, or onto hunting ghosts or onto a node we have already been to
			 */
			ArrayList<nodeMove>	nextPacman = new ArrayList<nodeMove>();
			for (nodeMove n: pacmanEdge) {
				if (isPowerPill(n.node))
					eventHorizon.get(NUM_GHOSTS).add(n.node);
				else {
					for (MOVE d: game.getPossibleMoves(n.node)) {
						int node = game.getNeighbour(n.node, d);
						if (pacman[node].distance == MAX_DISTANCE) { //Not been here
							if (!isPowerPill(node) && hunterEat[node] != -1)
								eventHorizon.get(hunterEat[node]).add(n.node);
							else if (!nextPacman.contains(node))
								nextPacman.add(new nodeMove(node, d));
						}
					}
				}
			}
			
			pacmanEdge = nextPacman;
			ghostsEdge = nextGhosts;
			ticks++;
			/*
			 * Add in a new entry if a ghost becomes active (due to leave the lair)
			 */
			for (GHOST g: GHOST.values()) {
				if (game.getGhostLairTime(g) > 0 && game.getGhostLairTime(g) == ticks)
					ghostsEdge.add(new nodeMove(game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, g, 0));
			}
		}	
		
		/*
		 * Store the chance that each node is blocked by combining the odds of each hunting ghost
		 */
		for (int node=0; node<block.length; node++) {
			float chance = 1f;
			for (GHOST g: GHOST.values())
				if (ghosts[g.ordinal()][node].hunter)
					chance *= (1f-1f/(1+ghosts[g.ordinal()][node].turns));
			block[node] = 1f-chance;
		}
		
		/*
		 * Mark all the accessible and all the safe nodes
		 * A node is safe if it is accessible and we would still have access to a junction if we moved there
		 */
		for (int node=0; node<game.getNumberOfNodes(); node++) {
			if (accessible[node]) {
				if (isPowerPill(node))
					safe[node] = true;
				else {
					nodeMove[] jns = new nodeMove[4];
					GHOST[][] blockers = new GHOST[4][];
					int i = 0;
					for (MOVE m: game.getPossibleMoves(node)) {
						jns[i] = nearestJunction(node, m.opposite());
						blockers[i] = findBlockers(jns[i].node, jns[i].move, jns[i].turns + pacman[node].distance);
						i++;
					}
					/*
					 * If any junction has no blockers it is safe
					 * If 2 or more junctions are only blocked by the same ghost it is safe
					 */
					GHOST[] single = new GHOST[4]; //filled in if a junction is only blocked by this ghost
					
					for (int j=0; j<i; j++) {
						if (blockers[j].length == 0 && jns[j].ghost == null) { //No blockers = safe
							safe[node] = true;
						} else if (blockers[j].length == 1 || (blockers[j].length == 0 && jns[j].ghost != null)) {
							if (blockers[j].length == 1)
								single[j] = blockers[j][0];
							else
								single[j] = jns[j].ghost;
						}
					}
						
					/*
					 * Loop over singleton blockers and see if any are the same
					 */
					for (GHOST g: GHOST.values()) {
						int count = 0;
						for (int j=0; j<i; j++) {
							if (single[j] == g)
								count++;
						}
						if (count > 1)
						safe[node] = true;
				}
			}
		}
	}
	}
	
	
	
	/*
	 * Find all the ghosts that could reach this node as hunters after X moves and block the pacman moving in the given direction
	 * Also takes into account the 3000 tick level limit
	 */
	GHOST[] findBlockers(int node, MOVE m, int dist) {
		boolean blocks[] = new boolean[NUM_GHOSTS];
		int count = 0;
		
		for (GHOST g: GHOST.values())
			if (ghosts[g.ordinal()][node].hunter &&
					ghosts[g.ordinal()][node].dir != m && ghosts[g.ordinal()][node].distance - EAT_DISTANCE <= dist &&
					ghosts[g.ordinal()][node].distance - EAT_DISTANCE + game.getCurrentLevelTime() <= LEVEL_LIMIT) {
				blocks[g.ordinal()] = true;
				count++;
			}
							
		GHOST[] result = new GHOST[count];
		for (GHOST g: GHOST.values())
			if (blocks[g.ordinal()])
				result[--count] = g;
		return result;
	}
	
	/*
	 * Find the nearest junction to this node in the accessible zone
	 * If we come across a hunting ghost blocking the way, store it
	 */
	private nodeMove nearestJunction(int node, MOVE m) {
		nodeMove result = new nodeMove(node, m);
		result.turns = 0;
		while (result.turns == 0 || !game.isJunction(result.node)) {
			result.turns++;
			if (result.ghost == null) {
				for (GHOST g: GHOST.values())
					if (game.getGhostCurrentNodeIndex(g) == result.node &&
							game.getGhostEdibleTime(g) <= 0 &&
							game.getGhostLastMoveMade(g) != m)
						result.ghost = g;
			}
			result.move = game.getPossibleMoves(result.node, result.move)[0];
			result.node = game.getNeighbour(result.node, result.move);
		}
		return result;
	}
	
	/*
	 * Find the node where the ghost will be in N moves time
	 * Stop if we reach a junction
	 */
	private nodeMove getGhostNode(int node, MOVE dir, int ticks) {
		nodeMove result = new nodeMove(node, dir);
		result.turns = 0;
		while (!game.isJunction(result.node) && result.turns < ticks) {
			result.turns++;
			MOVE[] moves = game.getPossibleMoves(result.node, result.move);
			if (moves.length == 0)
				return result;
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
				for (MOVE m: game.getPossibleMoves(game.getGhostCurrentNodeIndex(g)))
				if (m != game.getGhostLastMoveMade(g).opposite()) {
						ghostDir = m.opposite();
						break;
					}
		}
		
		useCache = (!testing && from == game.getPacmanCurrentNodeIndex());			
		nodeMove jn = getGhostNode(game.getGhostCurrentNodeIndex(g), ghostDir, Integer.MAX_VALUE);
		
		if (useCache) {	
			pacdist = pacman[game.getGhostCurrentNodeIndex(g)].distance;
			pacjndist = pacman[jn.node].distance;
		} else {
			pacdist = (int)game.getDistance(from, game.getGhostCurrentNodeIndex(g), DM.PATH);
			pacjndist = (int)game.getDistance(from, jn.node, DM.PATH);
		}
		
		//Work out if we can catch the ghost before it reaches the jn
		if ((!safe[jn.node] && pacdist - EAT_DISTANCE < jn.turns) || pacjndist - EAT_DISTANCE <= jn.turns*2) {
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
		return ghosts[g.ordinal()][node].hunter;
	}
	
	public int turns(GHOST g, int node) {
		return ghosts[g.ordinal()][node].turns;
	}
	
	public int ghostDistance(GHOST g, int node) {
		return ghosts[g.ordinal()][node].distance;
	}
	
	public MOVE ghostDir(GHOST g, int node) {
		return ghosts[g.ordinal()][node].dir;
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
