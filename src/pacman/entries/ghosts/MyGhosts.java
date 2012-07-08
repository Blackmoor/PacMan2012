package pacman.entries.ghosts;

import static pacman.game.Constants.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import pacman.controllers.Controller;
import pacman.game.Game;
import pacman.game.GameView;
import pacman.game.Constants.GHOST;
import pacman.game.Constants.MOVE;

public class MyGhosts extends Controller<EnumMap<GHOST,MOVE>> {	
	private static final boolean ZONE_DEBUG = false;
	
	/*
	 * An iterator over the valid moves each ghost has
	 * Returns the next set of valid moves.
	 */
	private class Permutation implements Iterator<MOVE[]>
	{
		private MOVE[][] options;
		private int[] curr;
		private long totalPerms;
		private long counter = 0;
		
		public Permutation(MOVE[][] o) {
			totalPerms = 1;
			options = o.clone();
			curr = new int[o.length];
			
			for (int i=0; i<options.length; i++) {
				options[i] = o[i].clone();
				totalPerms *= options[i].length;
				curr[i] = 0;
			}
		}
		
		public boolean hasNext() {
		    return counter < totalPerms;
		} 
		
		//abstract remove() specified by Iterator is not implemented
		public void remove() {}
		
		public MOVE[] next() {
			counter++;
		    if (counter > totalPerms) {
		        throw new NoSuchElementException();
		    } 
		    if (counter > 1) {
		    	int i = 0;
		    	boolean done = false;
		    	while (!done)
		    		if (++curr[i] < options[i].length)
		    			done = true;
		    		else
		    			curr[i++] = 0;
		    }
		    MOVE[] result = new MOVE[options.length];
		    for (int i=0; i<result.length; i++)
		    	result[i] = options[i][curr[i]];
		    return result;
		}
	}
	
	private Game 			game;
	private maze			maze;
	private eatDistance		cache;
	private static final Random			rnd = new Random();
	private static final int			MAX_DISTANCE = 350;	//The longest path in all mazes.
	
	public EnumMap<GHOST,MOVE> getMove(Game now,long timeDue)
	{
		EnumMap<GHOST,MOVE> myMoves=new EnumMap<GHOST,MOVE>(GHOST.class);
		double best = Double.MAX_VALUE;
		MOVE[][] options = new MOVE[NUM_GHOSTS][];
		MOVE[] nothing = { MOVE.NEUTRAL };
				
		boolean skip = true;
		for (GHOST g: GHOST.values()) {
			int gi = g.ordinal();
			options[gi] = now.getPossibleMoves(now.getGhostCurrentNodeIndex(g), now.getGhostLastMoveMade(g));
			if (options[gi].length == 0) //This only happens if we are in the lair
				options[gi] = nothing;
			if (now.doesGhostRequireAction(g))
				skip = false;
		}
		
		if (cache == null) {
			cache = new eatDistance();
			skip = false; //Even though we don't need to - we work out our moves, fill the cache and incur the java timeouts while it is safe
		}

		/*
		 * Loop over all possible ghost moves and pick the one that has the smallest safe zone for the pacman
		 * This is effectively a single step min/max algorithm
		 */
		if (!skip) {			
			Permutation moves = new Permutation(options);
			while (moves.hasNext()) {
				MOVE[] m = moves.next();
				EnumMap<GHOST,MOVE> testMoves=new EnumMap<GHOST,MOVE>(GHOST.class);
				for (GHOST g: GHOST.values())
					testMoves.put(g, m[g.ordinal()]);
							
				/*
				 * Pick the largest score the pacman can get
				 */
				double highest = -1;
				for (MOVE pacman: now.getPossibleMoves(now.getPacmanCurrentNodeIndex())) {	
					game = now.copy();
					game.setGlobalReversals(false);
					game.advanceGame(pacman, testMoves);							
					double score = rnd.nextDouble();
					
					if (!wasEaten(now, game)) {
						if (maze == null)
							maze = new maze(game, cache, false);
						else
							maze.update(game);
						score += scorePositions(maze.eventHorizon());
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
		
		if (ZONE_DEBUG) {
			game = now;
			maze = new maze(game, cache, false);
			for (int i=0; i<game.getNumberOfNodes(); i++) {
				if (maze.isSafe(i))
					GameView.addPoints(game, new Color(0, Math.max(0, 255-maze.pacmanDistance(i)), 0, 128), i);
				else if (maze.hasAccess(i))
					GameView.addPoints(game, new Color(128, 0, 0, 128), i);
			}
		}
		
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
	
	/*
	 * Count the number of escape routes - junctions near the event horizon that are not sure to be blocked
	 */
	private int countEscapeRoutes() {
		int g = 0;
		int count = 0; //How many valid escape routes we have
		for (HashSet<Integer> gev: maze.eventHorizon()) {
			if (g < NUM_GHOSTS) {
				int options = 0; //How many valid escape routes we have for this ghost
				for (int n: gev)				
					if (!maze.willBlock(n))
						options++;
				if (options > 1)
					count += (options - 1);
			}
			g++;		
		}
		//System.out.printf("Escape Routes: %d\n", count);
		return count;
	}
	
	/*
	 * Look at the board positions and return a score - higher is better for the pacman
	 * The score determines how effective a ghost not involved in blocking the pacman is
	 */
	private double scorePositions(ArrayList<HashSet<Integer>> eventHorizon) {
		double score = 0;
		
		if (countEscapeRoutes() > 0)
			score += 1000;
		
		for (int i=0; i<game.getNumberOfNodes(); i++)
			if (maze.hasAccess(i))
				score += 10;
		
		/*
		 * Add in score for power pills
		 */
		boolean containsPowerPill = false;
		for (int pp: game.getActivePowerPillsIndices())
			if (maze.hasAccess(pp))
				containsPowerPill = true;
		
		if (containsPowerPill)
			score += 1500;
		
		for (int p: game.getActivePillsIndices())
			if (maze.isSafe(p))
				score ++;
		
		/*
		 * Apply a score to ghosts not contributing to the event horizon
		 * Either edible ghosts or hunters not in range
		 */
		int SAFE_DISTANCE = 40;
		ArrayList<GHOST> edible = new ArrayList<GHOST>();
		for (GHOST g: GHOST.values())
			if (game.getGhostEdibleTime(g) > 0)
				edible.add(g);
		if (edible.size() > 0) {
			ArrayList<nodeMove> order = maze.chaseOrder(edible);
			int edibleScore = maze.chaseScore(order);
			
			//System.out.printf("Chase Score %d\n", edibleScore);
			score += edibleScore;
			//Remove the edible ghost that were part of this score
			for (nodeMove nm: order)
				edible.remove(nm.ghost);
		}
		
		for (GHOST g: GHOST.values()) {
			if (eventHorizon.get(g.ordinal()).size() == 0) {
				int dist;
				if (game.getGhostLairTime(g) == 0)
					dist = (int)game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), game.getGhostLastMoveMade(g), DM.PATH);
				else
					dist = game.getGhostLairTime(g) + (int)game.getDistance(game.getGhostInitialNodeIndex(), game.getPacmanCurrentNodeIndex(), DM.PATH);
				if ((edible.contains(g) && game.getGhostEdibleTime(g) > 2*dist/3 - EAT_DISTANCE) ||
						(containsPowerPill && game.getDistance(game.getGhostCurrentNodeIndex(g), game.getPacmanCurrentNodeIndex(), game.getGhostLastMoveMade(g).opposite(), DM.PATH) < EDIBLE_TIME/2*(Math.pow(EDIBLE_TIME_REDUCTION, game.getCurrentLevel()))) ||
						maze.ghostDistance(g, game.getPacmanCurrentNodeIndex()) < SAFE_DISTANCE) //Move away from the pacman
					score -= dist;
				else //Move towards the pacman
					score += dist;
			}			
		}
		
		return score;		
	}
}