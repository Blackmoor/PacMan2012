package pacman.entries.ghosts;

import static pacman.game.Constants.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;

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
	private int				accessibleCount; //The number of nodes we have access to
	private ArrayList<HashSet<Integer>>	eventHorizon; //For each ghost, the nodes where it meets the pacman as a hunter
	private eatDistance		cache;
	
	public maze(Game g, eatDistance c, boolean isPacman) {
		game = g;
		cache = c;
		walkMaze(isPacman);
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
	private void walkMaze(boolean isPacman) {
		HashSet<nodeMove>	pacmanEdge = new HashSet<nodeMove>();
		HashSet<nodeMove>	ghostsEdge = new HashSet<nodeMove>();
		pacman = new pacmanInfo[game.getNumberOfNodes()];
		ghosts = new ghostInfo[NUM_GHOSTS][game.getNumberOfNodes()];
		accessible = new boolean[game.getNumberOfNodes()];
		accessibleCount = 0;
		safe = new boolean[game.getNumberOfNodes()];
		block = new float[game.getNumberOfNodes()];
		eventHorizon = new ArrayList<HashSet<Integer>>();
		int[]	hunterEat = new int[game.getNumberOfNodes()]; //The nodes at the eat range from each hunting ghost	
		boolean[]	pacmanEat = new boolean[game.getNumberOfNodes()]; //The nodes at the eat range of the pacman
		
		for (int i=0; i<game.getNumberOfNodes(); i++) {
			pacman[i] = new pacmanInfo();
			for (int g=0; g<NUM_GHOSTS; g++)
				ghosts[g][i] = new ghostInfo();
		}
		
		/*
		 * Populate starting nodes
		 */
		int ticks = 0; //How far ahead we are looking
		for (GHOST g: GHOST.values()) {
			eventHorizon.add(new HashSet<Integer>());
			if (game.getGhostLairTime(g) == 0)
				ghostsEdge.add(new nodeMove(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g), g, 0));
		}
		eventHorizon.add(new HashSet<Integer>()); //This entry is for power pill block points
		pacmanEdge.add(new nodeMove(game.getPacmanCurrentNodeIndex(), MOVE.NEUTRAL));
		
		//BFS routine - expands ghost positions and pacman positions at the same time
		while (ticks < MAX_DISTANCE && ghostsEdge.size() + pacmanEdge.size() > 0) { //Keep walking while we have somewhere to go
			/*
			 * New edge is 1 move from each point on the current edge
			 * Ghosts stop when they reach a node they have already been to
			 */
			HashSet<nodeMove>	nextGhosts = new HashSet<nodeMove>();			
			Arrays.fill(hunterEat, -1);
			
			for (nodeMove gi: ghostsEdge) {
				int edibleTime = game.getGhostEdibleTime(gi.ghost) - ticks;
				if (edibleTime <= 0 || edibleTime%GHOST_SPEED_REDUCTION != 0) {
					for (MOVE gm: game.getPossibleMoves(gi.node, gi.move)) {
						int node = game.getNeighbour(gi.node, gm);
						boolean eaten =  (edibleTime > 1 && pacmanEat[node]);
						if (!eaten && (game.isJunction(node) || ghosts[gi.ghost.ordinal()][node].distance == MAX_DISTANCE)) {
							nodeMove next = new nodeMove(node, gm, gi.ghost, gi.turns + countChoices(gi.node));
							nextGhosts.add(next);
							if (edibleTime <= 1)
								for (int n: cache.eatNodes(game, node))
									hunterEat[n] = gi.ghost.ordinal();
						}
					}	
				} else { //edible ghost doesn't move - add in the current location
					boolean eaten =  (edibleTime > 1 && pacmanEat[gi.node]);
					if (!eaten)
						nextGhosts.add(gi);
				}
			}
			
			/*
			 * Work out new edge - 1 move from each point on the current edge
			 * Pacman will not move off power pills, or onto hunting ghosts or onto a node we have already been to
			 */
			HashSet<nodeMove>	nextPacman = new HashSet<nodeMove>();
			for (nodeMove n: pacmanEdge) {
				if (isPowerPill(n.node))
					eventHorizon.get(NUM_GHOSTS).add(n.node);
				else {
					for (MOVE d: game.getPossibleMoves(n.node)) {
						int node = game.getNeighbour(n.node, d);
						if (pacman[node].distance == MAX_DISTANCE) { //Not been here
							if (!isPowerPill(node) && hunterEat[node] != -1)
								eventHorizon.get(hunterEat[node]).add(n.node);
							else
								nextPacman.add(new nodeMove(node, d));
						}
					}
				}
			}
			
			/*
			 * Special case for pacman on first tick (0)
			 * If it is at a junction and its last move was NEUTRAL it can stay here
			 */
			if (ticks == 0 && game.isJunction(game.getPacmanCurrentNodeIndex()) && game.getPacmanLastMoveMade() == MOVE.NEUTRAL && hunterEat[game.getPacmanCurrentNodeIndex()] == -1) {
				pacman[game.getPacmanCurrentNodeIndex()].distance = ticks;
				pacman[game.getPacmanCurrentNodeIndex()].dir = MOVE.NEUTRAL;
				accessible[game.getPacmanCurrentNodeIndex()]= true; 
				accessibleCount++;
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
			
			//Record distance travelled to all edge nodes	
			for (nodeMove n: ghostsEdge) {
				int gi = n.ghost.ordinal();
				if (ticks < ghosts[gi][n.node].distance) {					
					ghosts[gi][n.node].distance = ticks;
					ghosts[gi][n.node].dir = n.move;
					ghosts[gi][n.node].hunter = (ghosts[gi][n.node].distance >= game.getGhostEdibleTime(n.ghost));
					ghosts[gi][n.node].turns = n.turns;
				}
			}
			
			Arrays.fill(pacmanEat, false);			
			for (nodeMove n: pacmanEdge) {
				pacman[n.node].distance = ticks;
				pacman[n.node].dir = n.move;
				accessible[n.node]= true; 
				accessibleCount++;
				for (int e: cache.eatNodes(game, n.node))
					pacmanEat[e] = true;
			}
			
		}	
		
		/*
		 * Store the chance that each node is blocked by combining the odds of each hunting ghost
		 */
		for (int node=0; node<block.length; node++) {
			float chance = 1f;
			for (GHOST g: GHOST.values())
				if (ghosts[g.ordinal()][node].hunter) {
					chance *= ghosts[g.ordinal()][node].turns;
					chance /= (1+ghosts[g.ordinal()][node].turns);
				}
			block[node] = 1f-chance;
		}
		
		/*
		 * Mark all the safe nodes
		 * A node is safe if it is accessible and we would still have access to a junction if we moved there
		 * We skip this for ghosts since it takes too long, simply marking all accessible nodes as safe
		 */
		if (isPacman) {
			/*
			 * For each accessible junction we travel the paths from here and check to see if we could be blocked along the way
			 * or if we could double back and escape
			 */
			for (int jn: game.getJunctionIndices()) {				
				if (accessible[jn]) {
					for (MOVE m: game.getPossibleMoves(jn)) {
						scanPath(jn, m);
					}
						}
					}
						
					/*
			 * Ensure we update the safe nodes on the path we are on
					 */
			if (!game.isJunction(game.getPacmanCurrentNodeIndex()))
				scanMyPath(game.getPacmanCurrentNodeIndex());

			/*
			 * A junction is safe if at least one of the nodes next to it is safe
			 */
			for (int jn: game.getJunctionIndices()) {				
				if (accessible[jn]) {
					for (int n: game.getNeighbouringNodes(jn))
						if (safe[n])
							safe[jn] = true;
				}
			}
			
			/*
			 * All accessible power pills are safe
			 */
			for (int pp: game.getActivePowerPillsIndices())
				safe[pp] = accessible[pp];
		} else {
			for (int node=0; node<game.getNumberOfNodes(); node++)
				safe[node] = accessible[node];
		}
	}
	
	/*
	 * Scan from the junction or the pacman location in the given direction and see if we are blocked
	 * Sets the safe status for each node
	 * If we find the pacman return true, otherwise false
	 */
	private void scanPath(int jn, MOVE m) {
		LinkedList<Integer> maybe = new LinkedList<Integer>(); //A list of nodes that are only safe if the route to the far end of the path is not blocked
		LinkedList<Integer> definitely = new LinkedList<Integer>(); 
		int dist = 1;
		int node = game.getNeighbour(jn, m);
		int moved = pacman[jn].distance;
		MOVE move = m;
		
		boolean[] backBlockers = new boolean[NUM_GHOSTS]; //true if the ghost can block our retreat
		boolean[] frontBlockers = new boolean[NUM_GHOSTS]; //true if the ghost can block our advance
		int backCount = 0; //number of back blockers
		int frontCount = 0; //number of front blockers
		boolean atEnd = false;
		boolean isBlocked = false; //Set to true if we are blocked from in front and behind
		
		while (!atEnd && !isBlocked) {
			//Check for back blocker
			backCount = 0;							
			for (GHOST g: GHOST.values()) {
				backBlockers[g.ordinal()] = (ghosts[g.ordinal()][jn].dir != m.opposite() && ghosts[g.ordinal()][jn].hunter &&
						ghosts[g.ordinal()][jn].distance - (pacman[jn].distance + 2*dist) <= EAT_DISTANCE);
				if (backBlockers[g.ordinal()])
					backCount++;									
			}
							
			//Check for front blocker
			frontCount = 0;
			for (GHOST g: GHOST.values()) {
				frontBlockers[g.ordinal()] = (ghosts[g.ordinal()][node].dir != move && ghosts[g.ordinal()][node].hunter &&
						ghosts[g.ordinal()][node].distance - (moved + dist) <= EAT_DISTANCE);
				if (frontBlockers[g.ordinal()])
					frontCount++;
			}
			
			if (backCount > 0 && frontCount > 0 && backCount+frontCount > 2)
				isBlocked = true;
			else if (backCount == 1 && frontCount == 1 && !Arrays.equals(backBlockers, frontBlockers))
						isBlocked = true;

			if (!isBlocked) {
				if (backCount == 0)
					definitely.add(node);
				else
					maybe.add(node);
	}
	
			if (game.isJunction(node)) {
				atEnd = true;
			} else {
				if (node == game.getPacmanCurrentNodeIndex())
					return; //Abort				
				move = game.getPossibleMoves(node, move)[0];
				node = game.getNeighbour(node, move);
				dist ++;
			}
		}
		for (int n: definitely)
			safe[n] = accessible[n];
		
		if (!isBlocked)
			for (int n: maybe)
				safe[n] = accessible[n];
	}
	
	/*
	 * Move out from the given node (current pacman node) and find the junctions at each end of the path
	 * For each tick moved from the start node we can work out if we can still get back to the jn behind us
	 */
	private void scanMyPath(int node) {
		int[] jns = new int[2];
		int[] here = new int[2];
		MOVE[] dirs = game.getPossibleMoves(node).clone();
		boolean[] atEnd = new boolean[2];
		boolean[][] blocked = new boolean[2][NUM_GHOSTS];
		int[] blockCount = new int[2];
		
		jns[0] = node;
		jns[1] = node;
		
		//Find end junctions and record if blocked
		while (!atEnd[0] || !atEnd[1]) {
			for (int end=0; end<2; end++) {
				if (!atEnd[end]) {				
					jns[end] = game.getNeighbour(jns[end], dirs[end]);
					pathBlockers(jns[end], pacman[jns[end]].distance, blocked[end]);
					if (game.isJunction(jns[end]))
						atEnd[end] = true;
					else
						dirs[end] = game.getPossibleMoves(jns[end], dirs[end])[0];
				}
			}
		}
		
		//Work out how many blockers we have at each end
		for (int end=0; end<2; end++)
			for (int i=0; i<NUM_GHOSTS;i++)
				if (blocked[end][i])
					blockCount[end]++;
				
		//Keep the jns info but reset the other variables
		here[0] = node;
		here[1] = node;
		atEnd[0] = false;
		atEnd[1] = false;
		dirs = game.getPossibleMoves(node).clone();
		int dist = 0;
		
		while (!atEnd[0] || !atEnd[1]) {
			dist ++;
			for (int end=0; end<2; end++) {
				if (!atEnd[end]) {
					here[end] = game.getNeighbour(here[end], dirs[end]);
					boolean[] backBlockers = new boolean[NUM_GHOSTS];
					if (blockCount[end] == 0 ||
							(blockCount[1-end] == 0 && !pathBlockers(here[end], pacman[jns[1-end]].distance+2*dist, backBlockers)) ||
							(blockCount[end] == 1 && blockCount[1-end] == 0 && Arrays.equals(blocked[end], backBlockers)))
						safe[here[end]] = accessible[here[end]];
					if (game.isJunction(here[end]))
						atEnd[end] = true;
					else
						dirs[end] = game.getPossibleMoves(here[end], dirs[end])[0];
				}
			}
		}
	}
	
	private boolean pathBlockers(int node, int dist, boolean[] blocked) {
		boolean foundBlocker = false;
	
		for (GHOST g: GHOST.values()) {
			if (ghosts[g.ordinal()][node].hunter && ghosts[g.ordinal()][node].dir != pacman[node].dir &&
					ghosts[g.ordinal()][node].distance - dist <= EAT_DISTANCE) {
				if (blocked != null)
					blocked[g.ordinal()] = true;
				foundBlocker = true;
			}
		}
		
		return foundBlocker;
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
	 * result[3] = 1 if chasing and -1 if intercepting
	 * This function can be called when the ghost is not edible to test how quickly we could reach it.
	 * In this case we have to reverse its direction of travel as a power pill would do this.
	 */
	private int[] getChaseInfo(int from, GHOST g, boolean testing) {
		int before; //Distance travelled to catch ghost before jn
		int after; //Distance travelled to catch ghost after jn
		int target;
		int pacjndist;
		int pacdist;
		boolean intercepting = false;
		boolean useCache; // true if we can use the cached distances (pacman and ghosts)
		MOVE ghostDir = game.getGhostLastMoveMade(g);

		if (testing) { //Assume ghost will reverse direction
			ghostDir = game.getPossibleMoves(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g))[0].opposite();
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
				else {
					before = 2*(pacdist - EAT_DISTANCE)/3;
					intercepting = true;
				}
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
			int[] result = new int[4];
			result[0] = target;
			result[1] = before;
			result[2] = after;
			result[3] = intercepting?-1:1;
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
				/*
				 * Check to see if first ghost eaten can interfere
				 */
				if (interference > 0 && 3*total/2-interference >= game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostInitialNodeIndex(), game.getGhostLastMoveMade(g), DM.PATH))					
						total += 50;
				if (total <= cutoff) {					
					current.add(new nodeMove(chase[0], MOVE.NEUTRAL, g, total));
					/*
					 * If we intercepted the ghost we start from 2 nodes in front of it otherwise 2 behind it
					 */
					MOVE ghostDir = MOVE.NEUTRAL;
					if (chase[3] == -1)
						ghostDir = game.getGhostLastMoveMade(g);
					else
						ghostDir = game.getPossibleMoves(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g))[0].opposite();							
					
					current.addAll(chaseOrder(getGhostNode(game.getGhostCurrentNodeIndex(g), ghostDir, EAT_DISTANCE).node, remaining, total,
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
	
	public ArrayList<HashSet<Integer>> eventHorizon() {
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
	
	public float access() {
		return 100f * accessibleCount / game.getNumberOfNodes();
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
		int start = game.getNeighbour(game.getPacmanCurrentNodeIndex(), game.getPossibleMoves(game.getPacmanCurrentNodeIndex(), game.getPacmanLastMoveMade())[0]);
		return chaseOrder(start, edible, 0, -1, 1);
	}
}
