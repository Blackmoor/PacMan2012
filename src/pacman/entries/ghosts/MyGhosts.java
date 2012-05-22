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
	private static final int			MAX_DISTANCE = 350;	//The longest path in all mazes.
	
	public EnumMap<GHOST,MOVE> getMove(Game game,long timeDue)
	{
		EnumMap<GHOST,MOVE> myMoves=new EnumMap<GHOST,MOVE>(GHOST.class);
		double best = Double.MAX_VALUE;
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
							double highest = -1;
							for (MOVE pacman: game.getPossibleMoves(game.getPacmanCurrentNodeIndex())) {
								
								this.game = safeAdvance(game, pacman, testMoves);		
								
								double score = rnd.nextDouble();
								if (!wasEaten(game, this.game))
									score += scorePositions(walkMaze());
							
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
		this.game = game;
		walkMaze();
		for (int i=0; i<safe.length; i++) {
			if (safe[i])
				GameView.addPoints(game, new Color(0, Math.max(0, 255-nodes[i].pacman), 0, 128), i);
		}
		*/
		
		return myMoves;
	}
	
	/*
	 * Advance the game state with the given set of moves
	 * Check for global reverses and try again if one occurs
	 */
	private Game safeAdvance(Game game, MOVE pacman, EnumMap<GHOST,MOVE> testMoves) {
		Game result = null;
		boolean reversed = true;
		/*
		 * Apply the moves. If we get a random game reverse, try again.
		 * If a ghost's last move is the opposite of what we expect and we haven't just eaten a power pill
		 * then a reverse has happened. Ghosts in the lair don't move so we can't check them.
		 */
		GHOST toCheck = null;
		for (GHOST g: GHOST.values())
			if (game.getGhostLairTime(g) == 0 && game.getGhostLastMoveMade(g) != MOVE.NEUTRAL) {
				toCheck = g;
				break;
			}
		
		while (reversed) {
			reversed = false;
			result = game.copy();
			result.advanceGame(pacman, testMoves);			
			if (!result.gameOver() && toCheck != null &&
					result.getGhostLastMoveMade(toCheck) == game.getGhostLastMoveMade(toCheck).opposite() &&
					result.getCurrentLevel() == game.getCurrentLevel() &&
					result.getNumberOfActivePowerPills() == game.getNumberOfActivePowerPills())
				reversed = true;
		}	
		
		return result;
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
	 * Return an array list (1 for each ghost) containing a list of nodes where the pacman will meet the ghost (the event horizon)
	 */
	private ArrayList<ArrayList<Integer>> walkMaze() {
		ArrayList<ArrayList<Integer>>	eventHorizon = new ArrayList<ArrayList<Integer>>();
		ArrayList<Integer>	pacmanEdge = new ArrayList<Integer>();
		ArrayList<ghostMove>	ghostsEdge = new ArrayList<ghostMove>();
		
		eventHorizon.add(new ArrayList<Integer>());	//Blinky
		eventHorizon.add(new ArrayList<Integer>());	//Pinky
		eventHorizon.add(new ArrayList<Integer>());	//Inky
		eventHorizon.add(new ArrayList<Integer>());	//Sue
		
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
		while (ghostsEdge.size() == 0) { //All the ghosts must be in the lair
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
									nodes[node].ghosts.get(gm).distance - distance <= EAT_DISTANCE) {
								hunters++;
								eventHorizon.get(nodes[node].ghosts.get(gm).ghost.ordinal()).add(node);
							}
						if (hunters == 0 && !nextPacman.contains(node))
							nextPacman.add(node);
					}
				}

			pacmanEdge = nextPacman;
			distance++;			
		}	
		
		return eventHorizon;
	}
	
	/*
	 * Returns a number (smaller is safer/better) to indicate how safe edible ghosts are.
	 * Required behaviour
	 * Move away from the nearest edible ghost (weighting 1)
	 * Move into unsafe zone (weighting 2)
	 */
	private int scoreEdible(GHOST g, ArrayList<ArrayList<Integer>> ev) {
		int score = 0;	
		MOVE last = game.getGhostLastMoveMade(g);
		
		//If the ghost is in the zone the pacman can reach head to the event horizon
		double nearest = Integer.MAX_VALUE;
		for (int j = 0; j<ev.size(); j++)
			for (int i=0; i<ev.get(j).size(); i++) {
				double dist = game.getDistance(game.getGhostCurrentNodeIndex(g), ev.get(j).get(i), last, DM.PATH);
				if (dist < nearest)
					nearest = dist;
			}
		if (nearest != Integer.MAX_VALUE) {
			if (safe[game.getGhostCurrentNodeIndex(g)])
				score += 3*(int)nearest;
			else
				score += 2*(MAX_DISTANCE - (int)nearest);
		} else //If there is no safe node, head away from PacMan
			score += 2*(MAX_DISTANCE - game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), last, DM.PATH));
		
		//Move away from nearest edible ghost (if there is one)
		double edible = Integer.MAX_VALUE;
		for (GHOST h: GHOST.values())
			if (h != g && game.getGhostEdibleTime(h) > 0) {
				double dist = game.getDistance(game.getGhostCurrentNodeIndex(g), game.getGhostCurrentNodeIndex(h), last, DM.PATH);
				if (dist < edible)
					edible = dist;
			}
		if (edible != Integer.MAX_VALUE)
			score += MAX_DISTANCE - (int)edible; //Move away from the edible ghost
		
		return score;
	}
	
	/*
	 * Look at the board positions and return a score - higher is better for the pacman
	 * The score determines how effective a ghost not involved in blocking the pacman is
	 */
	private double scorePositions(ArrayList<ArrayList<Integer>> eventHorizon) {
		double score = 100*safeScore;
		
		/*
		 * Add in score for power pills
		 */
		boolean containsPowerPill = false;
		for (int pp: game.getActivePowerPillsIndices())
			if (safe[pp]) {
				score += POWER_PILL;
				containsPowerPill = true;
			}
		
		for (int p: game.getActivePillsIndices())
			if (safe[p])
				score += PILL;
		
		/*
		 * Apply a score to ghosts not contributing to the event horizon
		 */		
		for (GHOST g: GHOST.values()) {
			if (eventHorizon.get(g.ordinal()).size() == 0 && game.getGhostLairTime(g) <= 0) {
				if (game.getGhostEdibleTime(g) > 0) { //Ghost is edible and in danger
					score += scoreEdible(g, eventHorizon) * 100000;
				} else if (containsPowerPill) { //Move away from the pacman
					score += MAX_DISTANCE - (int)game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), game.getGhostLastMoveMade(g), DM.PATH);
				} else { //Move towards the pacman
					score += (int)game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), game.getGhostLastMoveMade(g), DM.PATH);
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