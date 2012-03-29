package pacman.entries.ghosts;

import static pacman.game.Constants.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Random;

import pacman.controllers.Controller;
import pacman.game.Game;
import pacman.game.GameView;
import pacman.game.Constants.GHOST;
import pacman.game.Constants.MOVE;

public class MyGhosts extends Controller<EnumMap<GHOST,MOVE>> {	
	private class travel {
		GHOST ghost;
		int distance;
		
		public travel(GHOST g, int d) {
			ghost = g;
			distance = d;
		}
	}
	
	private class nodeInfo {
		int pacman;	//the distance the pacman travelled to get here
		EnumMap<MOVE, travel>	ghosts;	//For each direction of arrival, the ghost that gets here first and the distance it travelled.
		
		public nodeInfo() {
			pacman = Integer.MAX_VALUE;
			ghosts = new EnumMap<MOVE, travel>(MOVE.class);
		}
	}
	
	private Game 			game;
	private nodeInfo[]		nodes;	//Information about the distance to this node for the pacman and ghosts
	private boolean []		safe;	//The status of each node - safe (for the pacman) = true
	private int				safeScore; //How many points are available in the safe zone
	private static final Random			rnd = new Random();
	
	public EnumMap<GHOST,MOVE> getMove(Game game,long timeDue)
	{
		EnumMap<GHOST,MOVE> myMoves=new EnumMap<GHOST,MOVE>(GHOST.class);
		double best = 10000000;
		MOVE[][] options = new MOVE[NUM_GHOSTS][];
		MOVE[] nothing = { MOVE.NEUTRAL };
		
		boolean skip = true;
		for (GHOST g: GHOST.values()) {
			int gi = g.ordinal();
			options[gi] = game.getPossibleMoves(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g));
			if (options[gi].length == 0) //This only happens if we are in the lair
				options[gi] = nothing;
			if (game.doesGhostRequireAction(g))
				skip = false;
		}

		/*
		 * Loop over all possible ghost moves and pick the one that has the smallest safe zone for the pacman
		 * This is effectively a single step min/max algorithm
		 */
		if (!skip) {
			for (MOVE blinky: options[0]) {
				for (MOVE pinky: options[1]) {
					for (MOVE inky: options[2]) {
						for (MOVE sue: options[3]) {
							EnumMap<GHOST,MOVE> testMoves=new EnumMap<GHOST,MOVE>(GHOST.class);						
							testMoves.put(GHOST.BLINKY, blinky);
							testMoves.put(GHOST.PINKY, pinky);
							testMoves.put(GHOST.INKY, inky);
							testMoves.put(GHOST.SUE, sue);
							/*
							 * Pick the largest score the pacman can get
							 */
							double highest = 0;
							for (MOVE pacman: game.getPossibleMoves(game.getPacmanCurrentNodeIndex())) {
								this.game = game.copy();
								this.game.advanceGame(pacman, testMoves);
								
								double score = -10000000;
								if (!wasEaten(game, this.game)) {
									walkMaze();
									score = 10000*safeScore + scoreEdible() + scorePowerPills() + rnd.nextDouble();
								}							
								if (score > highest)
									highest = score;
							}
							/*
							 * Pick the lowest score the ghosts can get
							 */
							if (highest < best) {
								best = highest;
								myMoves = testMoves;
							}
						}
					}
				}
			}
		}
		
		/*
		if (safe != null) {
			this.game = game;
			walkMaze();
			for (int i=0; i<safe.length; i++) {
				if (safe[i])
					GameView.addPoints(game, new Color(0, Math.max(0, 255-nodes[i].pacman), 0, 128), i);
			}
		}
		*/
		
		return myMoves;
	}
	
	/*
	 * Try to work out if the pacman was eaten
	 */
	private boolean wasEaten(Game before, Game after) {
		if (after.gameOver())
			return true;
		if (after.getPacmanNumberOfLivesRemaining() < before.getPacmanNumberOfLivesRemaining())
			return true;
		if (after.getPacmanNumberOfLivesRemaining() == before.getPacmanNumberOfLivesRemaining() &&
				after.getScore() >= EXTRA_LIFE_SCORE && before.getScore() < EXTRA_LIFE_SCORE)
			return true;
		return false;
	}
	
	class ghostMove {
		int node;
		MOVE move;
		GHOST ghost;
		
		public ghostMove(int n, MOVE m, GHOST g) {
			node = n;
			move = m;
			ghost = g;
		}
		
		@Override
	    public boolean equals (Object x) {
	      if (x instanceof ghostMove)
	         return ((ghostMove)x).node == node && ((ghostMove)x).move == move && ((ghostMove)x).ghost == ghost;
	      return false;
	    }

	    @Override
	    public int hashCode ()
	    {
	        return node;
	    }
	}
	
	/*
	 * Using a breadth first search we spread out from the edge of our reachable area for each ghost and the pacman
	 * At each node we record the distance travelled and the arrival direction
	 */
	private void walkMaze() {
		
		ArrayList<Integer>	pacmanEdge = new ArrayList<Integer>();
		ArrayList<ghostMove>	ghostsEdge = new ArrayList<ghostMove>();
		nodes = new nodeInfo[game.getNumberOfNodes()];
		for (int i=0; i< nodes.length; i++)
			nodes[i] = new nodeInfo();
		safe = new boolean[game.getNumberOfNodes()];
		safeScore = 0;
		/*
		 * Populate starting edges
		 * To take account of the EAT_DISTANCE we don't move the pacman until the ghosts have had EAT_DISTANCE (2) extra moves
		 */
		pacmanEdge.add(game.getPacmanCurrentNodeIndex());
		for (GHOST g: GHOST.values())
			if (game.getGhostLairTime(g) == 0)
				ghostsEdge.add(new ghostMove(game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g), g));
		
		int distance = 0;
		while (ghostsEdge.size() == 0) { //The the ghosts must be in the lair
			distance++;
			for (GHOST g: GHOST.values())
				if (game.getGhostLairTime(g) == distance)
					ghostsEdge.add(new ghostMove(game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, g));
		}
		
		//First store the ghost distances
		while (ghostsEdge.size() > 0) { //Keep walking while we have somewhere to go
			for (ghostMove gi: ghostsEdge)
				nodes[gi.node].ghosts.put(gi.move, new travel(gi.ghost, distance));					
				
			/*
			 * New edge is 1 move from each point on the current edge
			 * Ghosts will not move onto a node another ghost has already been to (in the same direction as us)
			 */
			ArrayList<ghostMove>	nextGhosts = new ArrayList<ghostMove>();			
			for (ghostMove gi: ghostsEdge) {
				int edibleTime = game.getGhostEdibleTime(gi.ghost) - distance;
				if (edibleTime <= 0 || edibleTime%GHOST_SPEED_REDUCTION != 0) {
					for (MOVE gm: game.getPossibleMoves(gi.node, gi.move)) {
						int node = game.getNeighbour(gi.node, gm);
						if (!nodes[node].ghosts.containsKey(gm)) {
							ghostMove next = new ghostMove(node, gm, gi.ghost);
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
					ghostsEdge.add(new ghostMove(game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, g));
		}
		
		//Now do the pacman - making use of the ghost distance to see if we are blocked
		distance = 0;
		while (pacmanEdge.size() > 0) { //Keep walking while we have somewhere to go
			//Record distance travelled to all edge nodes
			for (int n: pacmanEdge) {
				safe[n] = true;
				nodes[n].pacman = distance;
				safeScore++;
			}				
				
			/*
			 * New edge is 1 move from each point on the current edge
			 * Pacman will not move onto power pills, hunting ghosts or on a node we have already been to
			 */
			ArrayList<Integer>	nextPacman = new ArrayList<Integer>();			
			for (int n: pacmanEdge)
				for (MOVE d: game.getPossibleMoves(n)) {
					int node = game.getNeighbour(n, d);
					if (!isPowerPill(n) && nodes[node].pacman == Integer.MAX_VALUE) {
						//Ensure there isn't a hunting ghost coming the other way
						int hunters = 0;
						for (MOVE gm: nodes[node].ghosts.keySet())							
							if (gm != d && game.getGhostEdibleTime(nodes[node].ghosts.get(gm).ghost) < distance &&
									nodes[node].ghosts.get(gm).distance - distance <= EAT_DISTANCE)
								hunters++;
						if (hunters == 0 && !nextPacman.contains(node))
							nextPacman.add(node);
					}
				}

			pacmanEdge = nextPacman;
			distance++;			
		}	
	}
	
	private int ghostDistance(GHOST me, int node) {		
		int dist = Integer.MAX_VALUE;
		for (MOVE m: nodes[node].ghosts.keySet())
			if (me == nodes[node].ghosts.get(m).ghost && nodes[node].ghosts.get(m).distance < dist)
				dist = nodes[node].ghosts.get(m).distance;
		return dist;
	}
	
	private GHOST nearestGhost(int node, MOVE banned) {		
		int dist = Integer.MAX_VALUE;
		GHOST result = null;
		for (MOVE m: nodes[node].ghosts.keySet())
			if (m != banned && nodes[node].ghosts.get(m).distance < dist) {
				dist = nodes[node].ghosts.get(m).distance;
				result = nodes[node].ghosts.get(m).ghost;
			}
		return result;
	}
	
	/*
	 * Returns a number (smaller is better) to indicate how safe edible ghosts are.
	 * Required behaviour
	 * 1. Move away from pacman (weighting 3)
	 * 2. Move towards the nearest hunting ghost (weighting 1)
	 * 3. Move Away from the nearest edible ghost (weighting 2)
	 */
	private int scoreEdible() {
		int score = 0;
		for (GHOST g: GHOST.values()) {
			if (game.getGhostEdibleTime(g) > 0) {				
				//We want to move away from the pacman - subtract the distance since we want a low score
				score -= 3*game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), DM.PATH);
				
				//Towards nearest hunter (if there is one) and away from nearest edible ghost (if there is one)
				int hunter = Integer.MAX_VALUE;
				int edible = Integer.MAX_VALUE;
				for (GHOST h: GHOST.values()) {
					if (game.getGhostEdibleTime(h) == 0) { ///hunter
						if (game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(h), DM.PATH) < hunter)
							hunter = (int)game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(h), DM.PATH);
					} else { //edible
						if (game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(h), DM.PATH) < edible)
							edible = (int)game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(h), DM.PATH);
					}
				}
				if (hunter != Integer.MAX_VALUE)
					score += hunter; //Move towards the hunter ghost
				if (edible != Integer.MAX_VALUE)
					score -= 2*edible; //Move away from the edible ghost
				
 			}
		}	
		
		return score;
	}
	
	private int scorePowerPills() {
		int score = 0;
		/*
		 * Add in score for power pills
		 */
		boolean containsPowerPill = false;
		for (int pp: game.getActivePowerPillsIndices())
			if (safe[pp]) {
				score += POWER_PILL;
				containsPowerPill = true;
			}
		
		/*
		 * Apply a score to ghosts not contributing to the event horizon
		 * The event horizon is defined as any node in the safe zone with a neighbour not in the safe zone
		 */
		boolean involved[] = new boolean[NUM_GHOSTS];	
		for (int n=0; n<game.getNumberOfNodes(); n++) {
			if (safe[n]) {
				for (MOVE m: game.getPossibleMoves(n)) {
					int next = game.getNeighbour(n, m);
					if (!safe[next] && !isPowerPill(next)) {
						//Find out which ghost(s) reach this point first (in a direction different to the pacman)
						GHOST g = nearestGhost(next, m);
						if (g != null)
							involved[g.ordinal()] = true;
					}
				}
			}
		}
		
		for (GHOST g: GHOST.values()) {
			if (!involved[g.ordinal()]) {
				if (containsPowerPill) { //Move away from nearest ghost
					int nearest = Integer.MAX_VALUE;
					for (GHOST n: GHOST.values()) {
						if (g != n && game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(n), DM.PATH) < nearest)
							nearest = (int)game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(n), DM.PATH);
					}
					if (nearest != Integer.MAX_VALUE)
						score -= nearest;
				} else { //Move towards the pacman
					score += game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), DM.PATH);
				}
			}
		}

		return score;		
	}
	
	
	private boolean isPowerPill(int node) {
		for (int pp: game.getActivePowerPillsIndices())
			if (pp == node)
				return true;
		return false;
	}
}