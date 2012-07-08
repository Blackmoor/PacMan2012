package pacman.entries.ghosts;

import static pacman.game.Constants.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;

import pacman.game.Game;
import pacman.game.Constants.GHOST;
import pacman.game.GameView;

/*
 * A set of utility functions that provides distance and direction information based on the current game state
 */
public class maze {
	private static final int	MAX_DISTANCE = 350;
	private static final boolean	GHOST_DEBUG = false;
	private Game 			game;
	private pacmanInfo[]	pacman;	//Distance of pacman from each node - updated each game tick
	private ghostInfo[][]	ghosts; //Distance of each ghost from each node - updated each game tick
	private float[]			block; //The odds of each node being blocked by ghosts
	private boolean[]		willBlock; //True if the ghost will definitely block this node with best play
	private boolean[]		safe; //Set to true if the node can be safely reached by the pacman
	private boolean[]		accessible; //Set to true if the pacman can reach this node before a ghost can
	private int				accessibleCount; //The number of nodes we have access to
	private ArrayList<HashSet<Integer>>	eventHorizon; //For each ghost, the nodes where it meets the pacman as a hunter
	private eatDistance		cache;
	private int[]			hunterEat; //The ghost id of the ghost that will eat a pacman at this node
	private boolean[]		pacmanEat; //Set to true if the pacman would eat an edible ghost in this node
	private boolean 		isPacman;
	
	public maze(Game g, eatDistance c, boolean is) {
		game = g;
		cache = c;
		isPacman = is;
		walkMaze(true);
	}
	
	public void update(Game g) {
		boolean reset = (game.getCurrentLevel() != g.getCurrentLevel());
		game = g;
		walkMaze(reset);
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
		
		public void reset() {
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
		
		public void reset() {
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
	 * 
	 * We try to reuse that current data structures to avoid the garbage collector kicking in
	 */
	private void walkMaze(boolean reset) {
		HashSet<nodeMove>	pacmanEdge = new HashSet<nodeMove>();
		HashSet<nodeMove>	ghostsEdge = new HashSet<nodeMove>();
		
		if (reset) {
		pacman = new pacmanInfo[game.getNumberOfNodes()];
		ghosts = new ghostInfo[NUM_GHOSTS][game.getNumberOfNodes()];
		accessible = new boolean[game.getNumberOfNodes()];
		safe = new boolean[game.getNumberOfNodes()];
		block = new float[game.getNumberOfNodes()];
		eventHorizon = new ArrayList<HashSet<Integer>>();
			for (int i=0; i<=NUM_GHOSTS; i++)
				eventHorizon.add(new HashSet<Integer>());
			willBlock = new boolean[game.getNumberOfNodes()];
			hunterEat = new int[game.getNumberOfNodes()]; //The nodes at the eat range from each hunting ghost	
			pacmanEat = new boolean[game.getNumberOfNodes()]; //The nodes at the eat range of the pacman
		for (int i=0; i<game.getNumberOfNodes(); i++) {
			pacman[i] = new pacmanInfo();
			for (int g=0; g<NUM_GHOSTS; g++)
				ghosts[g][i] = new ghostInfo();
		}
		} else {
			Arrays.fill(accessible, false);
			Arrays.fill(safe, false);
			Arrays.fill(block, 0f);
			Arrays.fill(willBlock, false);
			Arrays.fill(hunterEat, -1);
			Arrays.fill(pacmanEat, false);
			for (HashSet<Integer> gev: eventHorizon)
				gev.clear();
			for (int i=0; i<game.getNumberOfNodes(); i++) {
				pacman[i].reset();
				for (int g=0; g<NUM_GHOSTS; g++)
					ghosts[g][i].reset();
			}
		}
		accessibleCount = 0;		

		
		/*
		 * Populate starting nodes
		 */
		int ticks = 0; //How far ahead we are looking
		for (GHOST g: GHOST.values()) {
			if (game.getGhostLairTime(g) == 0)
				ghostsEdge.add(new nodeMove(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g), g, 0));
		}
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
			 * If its last move was NEUTRAL it can stay here
			 */
			Arrays.fill(pacmanEat, false);	
			if (ticks == 0 && game.getPacmanLastMoveMade() == MOVE.NEUTRAL && hunterEat[game.getPacmanCurrentNodeIndex()] == -1) {
				pacman[game.getPacmanCurrentNodeIndex()].distance = 1;
				pacman[game.getPacmanCurrentNodeIndex()].dir = MOVE.NEUTRAL;
				accessible[game.getPacmanCurrentNodeIndex()]= true; 
				accessibleCount++;
				for (int e: cache.eatNodes(game, game.getPacmanCurrentNodeIndex()))
					pacmanEat[e] = true;
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
		 * Where a ghost has more than 1 block point on the event horizon we need to know if at can block them all
		 * This is so if the pacman must make a decision at a junction before the ghost has to
		 */
		
		for (GHOST g: GHOST.values()) {
			if (eventHorizon.get(g.ordinal()).size() > 0) {
				ArrayList<ArrayList<Integer>> segments = makeSegments(g, false);
				ArrayList<ArrayList<Integer>> pmsegments = makeSegments(g, true);
				if (GHOST_DEBUG) {
					Color[] ghostColour = new Color[] { Color.red, Color.pink, Color.cyan, Color.orange };
	
					for (ArrayList<Integer> segment: segments)
						for (int i=1; i<segment.size(); i++)
							if (game.getNodeXCood(segment.get(i)) != 0 && game.getNodeXCood(segment.get(i-1)) != 0)
								GameView.addLines(game, ghostColour[g.ordinal()], segment.get(i), segment.get(i-1));
					for (ArrayList<Integer> segment: pmsegments)
						for (int i=1; i<segment.size(); i++)
							if (game.getNodeXCood(segment.get(i)) != 0 && game.getNodeXCood(segment.get(i-1)) != 0)
								GameView.addLines(game, Color.YELLOW, segment.get(i), segment.get(i-1));
				}
	
				if (eventHorizon.get(g.ordinal()).size() == 1) { //With only 1 block node it will be blocked with best play
					for (int n: eventHorizon.get(g.ordinal()))
						willBlock[n] = true;
				} else {
				//When a ghost needs to make a decision, we check to see if the pacman needs to make one first, if so the pacman is blocked
				for (int node: eventHorizon.get(g.ordinal())) {	
						ArrayList<Integer> choices = findChoices(segments, node, game.getGhostCurrentNodeIndex(g));										
					int count = 0; //how many choices the ghost has to make before the pacman
					if (choices != null) {
						int first = -1; //the first node where the pacman must make a choice
							ArrayList<Integer> pmchoices = findChoices(pmsegments, node, game.getPacmanCurrentNodeIndex());
						if (pmchoices != null)
							for (int n: pmchoices) {
								if (first == -1 || pacman[n].distance < pacman[first].distance)
									first = n;
							}
						if (first == -1)
								first = game.getPacmanCurrentNodeIndex();
						GHOST nearest = null;
						//Find nearest blocking ghost to this node
						for (GHOST g1: GHOST.values()) {
								if (ghosts[g1.ordinal()][first].hunter && ghostDistance(g1, first) > pacman[first].distance &&
										(nearest == null || ghostDistance(g1, first) < ghostDistance(nearest, first))) {
								nearest = g1;
							}
						}
						int pacmanChoice = MAX_DISTANCE;
						if (nearest != null)
								pacmanChoice = ghostDistance(nearest, first) - EAT_DISTANCE;
						
						//Now see if this ghost must make a choice before the pacman
						for (int n: choices) {
								if (n == game.getGhostCurrentNodeIndex(g) || ghostDistance(g,n) <= pacmanChoice)
								count++;
						}
					}
						if (count == 0)
							willBlock[node] = true;
					}
				}
			}
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
				if (accessible[jn] || jn == game.getPacmanCurrentNodeIndex()) {
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
				if (accessible[jn] && !safe[jn]) {
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
	 * Scan from the junction in the given direction and see if we are blocked
	 * Sets the safe status for each node
	 * If we find the pacman return true, otherwise false
	 */
	private void scanPath(int jn, MOVE m) {
		LinkedList<Integer> maybe = new LinkedList<Integer>(); //A list of nodes that are only safe if the route to the far end of the path is not blocked
		LinkedList<Integer> definitely = new LinkedList<Integer>(); //List of nodes that are definitely safe (unless we abort due to finding the pacman on this path)
		int dist = 1;
		int node = game.getNeighbour(jn, m);
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
						ghostDistance(g, jn) - (pacman[jn].distance + 2*dist) <= EAT_DISTANCE);
				if (backBlockers[g.ordinal()])
					backCount++;									
			}
							
			//Check for front blocker
			frontCount = 0;
			for (GHOST g: GHOST.values()) {
				frontBlockers[g.ordinal()] = (ghosts[g.ordinal()][node].dir != move && ghosts[g.ordinal()][node].hunter &&
						ghostDistance(g, node) - (pacman[jn].distance + dist) <= EAT_DISTANCE);
				if (frontBlockers[g.ordinal()])
					frontCount++;
			}
			
			if (backCount > 0 && frontCount > 0 && backCount+frontCount > 2)
				isBlocked = true;
			else if (backCount == 1 && frontCount == 1 && !Arrays.equals(backBlockers, frontBlockers))
						isBlocked = true;

			if (!isBlocked && pacman[node].dir == move) {
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
		int[] dist = new int[2];
		
		Arrays.fill(jns, node);
		//Find end junctions and record if blocked and the distance along this path to the jn
		while (!atEnd[0] || !atEnd[1]) {
			for (int end=0; end<2; end++) {
				if (!atEnd[end]) {				
					dist[end]++;
					jns[end] = game.getNeighbour(jns[end], dirs[end]);
					nodeBlockers(jns[end], dist[end], blocked[end]);
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
		Arrays.fill(here, node);
		Arrays.fill(atEnd, false);
		dirs = game.getPossibleMoves(node).clone();
		int ticks = 0;
		
		while (!atEnd[0] || !atEnd[1]) {
			ticks++;
			for (int end=0; end<2; end++) {
				if (!atEnd[end]) {
					here[end] = game.getNeighbour(here[end], dirs[end]);
					boolean[] backBlockers = new boolean[NUM_GHOSTS];
					if (blockCount[end] == 0 ||
							(blockCount[1-end] == 0 && !nodeBlockers(jns[1-end], dist[1-end]+2*ticks, backBlockers)) ||
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
	
	private boolean nodeBlockers(int node, int dist, boolean[] blocked) {
		boolean foundBlocker = false;
	
		for (GHOST g: GHOST.values()) {
			if (ghosts[g.ordinal()][node].hunter && ghosts[g.ordinal()][node].dir != pacman[node].dir &&
					ghostDistance(g, node) - dist <= EAT_DISTANCE) {
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
	 * result[1] = ticks for ghost to first junction
	 * result[2] = total distance to catch
	 * result[3] = 1 if chasing and -1 if intercepting
	 * This function can be called when the ghost is not edible to test how quickly we could reach it.
	 * In this case we have to reverse its direction of travel as a power pill would do this.
	 */
	private int[] getChaseInfo(int from, GHOST g, boolean testing) {
		int chaseDist; //Distance travelled to catch ghost
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
		if (pacdist - EAT_DISTANCE < jn.turns || pacjndist - EAT_DISTANCE <= jn.turns*GHOST_SPEED_REDUCTION/(GHOST_SPEED_REDUCTION-1)) {
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
					chaseDist = GHOST_SPEED_REDUCTION*(pacdist - EAT_DISTANCE);
				else {
					chaseDist = GHOST_SPEED_REDUCTION*(pacdist - EAT_DISTANCE)/(2*GHOST_SPEED_REDUCTION-1);
					intercepting = true;
				}
			} else
				chaseDist = 0;
			target = getGhostNode(game.getGhostCurrentNodeIndex(g), ghostDir, chaseDist*(GHOST_SPEED_REDUCTION-1)/GHOST_SPEED_REDUCTION).node; //Ghost moves at half speed
		} else { //Ghost will get to jn, then we chase it
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
			
			if (pacDir == jn.move) //We must be chasing all the way
				chaseDist = GHOST_SPEED_REDUCTION*(pacdist - EAT_DISTANCE);
			else //Head to Jn then chase
				chaseDist = GHOST_SPEED_REDUCTION*(pacjndist - EAT_DISTANCE - jn.turns);
			target = jn.node;
		}
		
		if (testing || safe[target]) {
			int[] result = new int[4];
			result[0] = target;
			result[1] = jn.turns*GHOST_SPEED_REDUCTION/(GHOST_SPEED_REDUCTION-1);
			result[2] = chaseDist;
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
				int total = distance + chase[2];
				if (total > chase[1]) { //Ghost will have time to disperse
					if (isPacman)
						total += (total - chase[1])/5; //Assume small dispersal for weak ghost teams
					else
						total += (total - chase[1])/2; //Assume large dispersal for our ghosts
				}
				/*
				 * Check to see if first ghost eaten can interfere
				 */
				if (interference > 0 && (2*GHOST_SPEED_REDUCTION-1)*total/GHOST_SPEED_REDUCTION-interference >= game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostInitialNodeIndex(), game.getGhostLastMoveMade(g), DM.PATH))					
						total += 50;
				if (total <= cutoff) {					
					current.add(new nodeMove(chase[0], MOVE.NEUTRAL, g, total));
					/*
					 * If we intercepted the ghost we start from 2 nodes in front of it otherwise 2 behind it
					 */
					MOVE ghostDir = MOVE.NEUTRAL;
					if ((chase[3] == 1 && game.getGhostEdibleTime(g) == 0) || (chase[3] == -1 && game.getGhostEdibleTime(g) != 0))
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
	 * Given a set of path segments, return the nodes (junctions) at which a ghost or pacman has to make a choice
	 * If there is no route to the node, return null
	 */
	private ArrayList<Integer> findChoices(ArrayList<ArrayList<Integer>> segments,  int node, int stop) {
		ArrayList<ArrayList<Integer>> path = new ArrayList<ArrayList<Integer>>();
		
		ArrayList<Integer> found = getSegment(segments, node);
		while (found != null) {
			path.add(0, found);
			if (found.get(0) == stop)
				break;
			found = getSegment(segments, found.get(0));
		}
		
		if (path.size() == 0)
			return null;
		
		ArrayList<Integer> result = new ArrayList<Integer>(); //The set of junctions where there are forks in the path
		for (ArrayList<Integer> s: path)
			if (s.get(0) != stop)
				result.add(s.get(0));
		
		//If the segment list has more than 1 path leading from the stop node we need to add it too
		int count = 0;
		for (ArrayList<Integer> s: segments)
			if (s.get(0) == stop)
				count++;
		if (count > 1)
			result.add(stop);
		
		return result;
	}
	
	/*
	 * Find the segment that ends at the given node from the supplied list of segments
	 */
	private ArrayList<Integer> getSegment(ArrayList<ArrayList<Integer>> segments,  int node) {
		
		for (ArrayList<Integer> s: segments)
			if (s.get(s.size() - 1) == node)
				return s;
		return null;
	}
	
	/*
	 * Check to see if the given object is in the active list and is not scheduled for deactivation or removal
	 */
	private boolean isActive(ArrayList<Integer> me, ArrayList<ArrayList<Integer>> active, ArrayList<ArrayList<Integer>> toRemove, ArrayList<ArrayList<Integer>> toDeactivate) {
		return (active.contains(me) && !toRemove.contains(me) && !toDeactivate.contains(me));
	}
	
	/*
	 * Scan backwards from the event horizon and record the path segments that make up the best routes to the ghost (or pacman)
	 */
	private ArrayList<ArrayList<Integer>> makeSegments(GHOST g, boolean pm) {
		ArrayList<ArrayList<Integer>> result = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> active = new ArrayList<ArrayList<Integer>>();
		
		int dist = 0;
		for (int node: eventHorizon.get(g.ordinal())) {
			ArrayList<Integer> path = new ArrayList<Integer>();
			path.add(node);
			result.add(path);
			active.add(path);
			if (ghostDistance(g, node) > dist)
				dist = ghostDistance(g, node);
		}
		
		if (result.size() == 0)
			return result;
		
		//Changing the array list within the iterator breaks it, so we create a list of entries to remove or add and do it later
		ArrayList<ArrayList<Integer>> toAdd = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> toRemove = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> toDeactivate = new ArrayList<ArrayList<Integer>>();
		//Starting at the farthest point we add in the next node to each path
		while (dist-- > 0) {
			for (ArrayList<Integer> path: active) {
				int banned = -1; //If this is the first move from the event horizon for a ghost we cannot move to the node the pacman came from
				if (!pm && path.size() == 1)
					banned = game.getNeighbour(path.get(0), pacman[path.get(0)].dir.opposite());
				ArrayList<Integer> next = nearestNeighbour(pm?null:g, path.get(0), dist, banned);
				if (next.size() > 1) { //There is more than 1 equi-distant route.
					//Create new segments with the current node as the end point
					for (int n: next) {
						ArrayList<Integer> s = new ArrayList<Integer>();
						s.add(0, path.get(0));
						s.add(0, n);
						toAdd.add(s);
					}
					toDeactivate.add(path);
				} else if (next.size() == 1) {
					path.add(0, next.get(0));
				}
			}
			result.addAll(toAdd);
			active.addAll(toAdd);
			toAdd.clear();
			active.removeAll(toDeactivate);
			toDeactivate.clear();

			//Search through the active segments, any that have the same starting node must be merged
			for (int i=0; i<result.size(); i++) {
				ArrayList<Integer> path = result.get(i);
				if (isActive(path, active, toRemove, toDeactivate)) {
					boolean merge = false;	//set to true if a merge is found
					for (int j=i+1; j<result.size(); j++) {
						ArrayList<Integer> compare = result.get(j);
						if ((int)path.get(0) == (int)compare.get(0) && isActive(compare, active, toRemove, toDeactivate)) {
							if (compare.size() == 1)
								toRemove.add(compare);
							else
								toDeactivate.add(compare);
							//Also remove any split segments that end at this end point
							for (int k=0; k<result.size(); k++) {
								ArrayList<Integer> split = result.get(k);
								if (k != j && (int)split.get(split.size()-1) == (int)compare.get(compare.size()-1) && isActive(split, active, toRemove, toDeactivate))
									toRemove.add(split);
							}
							merge = true;
						}
					}
					if (merge) {
						if (path.size() == 1)
							toRemove.add(path);
						else
							toDeactivate.add(path);
						//Also remove any split segments that end at this end point
						for (int k=0; k<result.size(); k++) {
							ArrayList<Integer> split = result.get(k);
							if (k != i && (int)split.get(split.size()-1) == (int)path.get(path.size()-1) && isActive(split, active, toRemove, toDeactivate))
								toRemove.add(split);
						}
						ArrayList<Integer> s = new ArrayList<Integer>();
						s.add(path.get(0));
						toAdd.add(s);
					}
				}
			}
			active.removeAll(toRemove);
			result.removeAll(toRemove);
			toRemove.clear();
			active.addAll(toAdd);
			result.addAll(toAdd);
			toAdd.clear();
			active.removeAll(toDeactivate);
			toDeactivate.clear();
			
		}
		
		//Tidy up the list by removing any segments with only 1 node
		for (ArrayList<Integer> s: result) {
			if (s.size() < 2)
				toRemove.add(s);
		}
		result.removeAll(toRemove);
		return result;
	}

	/*
	 * Find all nodes 1 space nearer the given ghost than the current node
	 * If no ghost is given, find the nodes 1 space nearer to the pacman
	 */
	private ArrayList<Integer> nearestNeighbour(GHOST g, int node, int dist, int banned) {
		ArrayList<Integer> result = new ArrayList<Integer>();
		
		if (g == null && pacman[node].distance == dist+1) {
			for (MOVE m: game.getPossibleMoves(node)) {
				int n = game.getNeighbour(node, m);
				if (n != banned && (pacman[n].distance == dist || (dist == 0 && n == game.getPacmanCurrentNodeIndex())))
					result.add(n);
			}
		} else if (g != null && (ghostDistance(g, node) == dist+1 || (!ghosts[g.ordinal()][node].hunter && ghostDistance(g, node) == dist+2))) {
			for (MOVE m: game.getPossibleMoves(node)) {
				int n = game.getNeighbour(node, m);
				if (n != banned && ghostDistance(g, n) == dist)
					result.add(n);
			}
		}
		return result;
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
		if (game.getGhostCurrentNodeIndex(g) == node)
			return 0;
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
	
	public boolean willBlock(int node) {
		return willBlock[node];
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
	
	/*
	 * Find the nearest junction to this node in the accessible zone
	 */
	public int nearestJunction(int node) {	
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
}
