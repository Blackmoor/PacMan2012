package pacman.entries.pacman;


import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import pacman.controllers.Controller;
import pacman.game.Constants.GHOST;
import pacman.game.Constants.MOVE;
import pacman.game.Game;
import pacman.game.GameView;

import static pacman.game.Constants.*;

/*
 * This is the class you need to modify for your entry. In particular, you need to
 * fill in the getAction() method. Any additional classes you write should either
 * be placed in this package or sub-packages (e.g., game.entries.pacman.mypackage).
 */
public class MyPacMan extends Controller<MOVE>
{

	private static final int	ESCAPE = 70; //Score given to an escape route (as opposed to a power pill which is 50)
	private static final boolean PATH_DEBUG = false;
	private static final boolean ZONE_DEBUG = false;
	private Game 			game;	
	private maze			maze;
	private int				best = -1; //The best node (last turn)
	private float[]			scores; //The score for each node
	private int[]			bestPath; //The nodes that make up the best path for the pacman to take
	private float			bestScore; //The total score of all nodes on the best path
	private int				edibleScore = 0; //The score for edible ghost on the last tick
	private boolean			waiting = false; //set to true if the edible score is increasing so we should wait to eat a power pill
	private float			risk = 30; //How much risk we can take (20 = lots, 60 = conservative)
	private int				lastDeath = -1;
	private eatDistance		cache; //A cache of the nodes within EAT_DISTANCE
	
	private static final Random			rnd = new Random(); //Used to break tie breaks in paths with the same score
	
	//Place your game logic here to play the game as Ms Pac-Man
	public MOVE getMove(Game g,long timeDue)
	{
		if (this.game != null && wasEaten(game, g)) {
			risk += 20;
			lastDeath = game.getCurrentLevel();
			//System.out.printf("Game level %d (%d): DIED\n", game.getCurrentLevel(), game.getCurrentLevelTime());
		}
		risk -= 0.001f;
		game = g;
		if (cache == null)
			cache = new eatDistance();
		maze = new maze(game, cache, true);
        int best = bestNode();
        MOVE dir = bestDir(best);
        
        return dir;
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
	
	private float initialiseScores() {
		/*
		 * Set the score for each reachable node to 1 and unreachable to -1
		 */
		float result = 0; //Percentage of node that are safe
		scores = new float[game.getNumberOfNodes()];
		for (int node=0; node<scores.length; node++) {
			scores[node] = -1;
			if (maze.isSafe(node)) {
				scores[node] = 1;
				result++;
				if (ZONE_DEBUG)
					GameView.addPoints(game, new Color(0f, (0.3f+maze.weighting(node)*0.7f), 0f), node);
			} else if (ZONE_DEBUG && maze.hasAccess(node))
				GameView.addPoints(game, new Color(0.5f, 0, 0), node);
		}
		return (100*result) / game.getNumberOfNodes();
	}
	
	/*
	 * Given a set of nodes, find the node in the safe zone that is furthest from all the nodes
	 * This is called with the event horizon and used to find the node to head to if there are no escape routes
	 * as it will give us the longest survival time and the best chance for a random game reverse to occur
	 */
	private int furthestNode(HashSet<Integer> edge) {
		//We use the event horizon as the starting locations for a breadth first search over the accessible zone
		int last = -1;
		boolean[] visited = new boolean[game.getNumberOfNodes()];
		
		while (edge.size() > 0) {
			for (int n: edge) {
				visited[n] = true;
			}
			/*
			 * New edge is 1 move from each point on the current edge
			 * Only allow moves in the accessible zone
			 */
			HashSet<Integer>	next = new HashSet<Integer>();
			for (int n: edge) {
				for (MOVE d: game.getPossibleMoves(n)) {
					int node = game.getNeighbour(n, d);
					if (!visited[node] && maze.hasAccess(node))
						next.add(node);
				}
				last = n;
			}
			
			edge = next;
		}
		
		return last;
	}
	
	/*
	 * Count the number of escape routes - junctions near the event horizon that are not sure to be blocked
	 */
	private int countEscapeRoutes() {
		final Color[] ghostColour = new Color[] { Color.red, Color.pink, Color.cyan, Color.orange };
		HashSet<Integer> jns = new HashSet<Integer>();
		int g = 0;
		int unused = 0;
		for (HashSet<Integer> gev: maze.eventHorizon()) {
			if (g < NUM_GHOSTS) {
				if (gev.size() != 1)
					unused++;
				for (int n: gev) {				
					int jn = nearestJunction(n);
					//The 4 junctions at the bottom of maze 2 are a nightmare and ignored as possible escape routes
					if (jn != -1 && !(game.getMazeIndex() == 2 && (jn == 1166 || jn == 1186 || jn == 1213 || jn == 1233)))
						jns.add(jn);
					if (ZONE_DEBUG)
						GameView.addPoints(game, ghostColour[g], n);
				}
			}
			g++;		
		}
		
		int count = 0;
		for (int jn: jns)
			if (maze.weighting(jn) > 0)
				count++;
		
		if (count > unused)
			return count - unused;
		return 0;
	}
	
	private int countPowerPills() {
		int count = 0;
		for (int i: game.getActivePowerPillsIndices())
			if (maze.isSafe(i))
				count++;
		return count;
	}
	
	/*
	 * Score the escape routes - the junctions next to the nodes on the event horizon
	 * We can pick the ones most likely to give an escape route or the ones most likely to lure the ghosts together
	 */
	private void scoreEscapeRoutes(boolean lure) {	
		HashSet<Integer> edge = new HashSet<Integer>();
		HashSet<Integer> jns = new HashSet<Integer>();
		int g = 0;
		for (HashSet<Integer> gev: maze.eventHorizon()) {
			for (int n: gev) {
				edge.add(n);
				if (g < NUM_GHOSTS) {
					int jn = nearestJunction(n);
					//The 4 junctions at the bottom of maze 2 are a nightmare and ignored as possible escape routes
					if (jn != -1 && !(game.getMazeIndex() == 2 && (jn == 1166 || jn == 1186 || jn == 1213 || jn == 1233)))
						jns.add(jn);
				}
			}
			g++;		
		}
		
		int best = -1;
		int converge = Integer.MAX_VALUE;
		if (lure) {			
			for (int jn: jns) {
				//Pick the junction with the smallest difference between the pacman and ghost distances
				int diff = 0;
				for (GHOST gh: GHOST.values()) {
					int dist = maze.ghostDistance(gh, jn) - EAT_DISTANCE - maze.pacmanDistance(jn);
					if (dist < 0)
						dist = 100;
					diff += dist;
				}
				if (diff < converge) {
					converge = diff;
					best = jn;
				}
			}
		}
		if (best == -1)
			for (int jn: jns)
				scores[jn] += ESCAPE*maze.weighting(jn);
		else
			scores[best] += ESCAPE;
		
		//Find the node in the accessible zone furthest from the event horizon - when we are completely blocked, this is the best place to head		
		int node = furthestNode(edge);	
		if (node != -1) {
			scores[node]+=3;
			if (ZONE_DEBUG)
				GameView.addPoints(game, Color.MAGENTA, node);
		}
	}
	
	private GHOST nearestHunter(int node)
	{
		int nearest = Integer.MAX_VALUE;
		GHOST id = null;
		
		//Find nearest Hunting Ghost
		for (GHOST g: GHOST.values()) {
			int dist = maze.ghostDistance(g, node);
			if (maze.isHunter(g, node) && dist < nearest) {
				nearest = dist;
				id = g;
			}
		}
		
		return id;
	}

	/*
	 * Add in the score for each pill in the safe zone
	 * Return the node id of the nearest safe pill or -1 if there is none.
	 */
	private int scorePills() {
		int best = -1;
		for (int p: game.getActivePillsIndices())
			if (maze.isSafe(p)) {
				scores[p] += PILL;
				if (best == -1 || maze.pacmanDistance(p) < maze.pacmanDistance(best))
					best = p;
			}
		return best;
	}
	
	/*
	 * Find the best power pill to eat
	 * 
	 * Only score it if
	 * 1. We need to eat a power pill to survive
	 * 2. We want to eat it because there are lots of ghosts in range that would become edible
	 */
	private void scorePowerPills(boolean danger) {
		/*
		 * Work out if we should wait because the estimated score for eating ghosts is getting bigger
		 */
		ArrayList<GHOST> ghosts = new ArrayList<GHOST>();
		for (GHOST g: GHOST.values())
			if (game.getGhostLairTime(g) == 0)
				ghosts.add(g);
		int score = maze.chaseScore(maze.chaseOrder(ghosts));
		waiting = (score > 0 && score >= edibleScore);
		edibleScore = score;		
		//System.out.printf("Tick %d - edible score %d, waiting = %s\n", game.getTotalTime(), score, waiting);
		
		/*
		 * Find the nearest safe power pill that wants to be eaten.
		 * If none want to be eaten, pick the nearest safe power pill
		 */
		int dist = Integer.MAX_VALUE;
		int pp = -1;
		boolean wantsToBeEaten = false; //set to true if we are only comparing power pills we want to eat
		for (int p: game.getActivePowerPillsIndices()) {
			boolean eat = eatMe(p); //Do we want to eat this power pill
			if (maze.hasAccess(p) && ((eat && !wantsToBeEaten) || ((eat || !wantsToBeEaten) && maze.pacmanDistance(p) < dist))) {
				if (eat)
					wantsToBeEaten = true;
				pp = p;
				dist = maze.pacmanDistance(p);
			}
		}
		
		if (pp != -1) {
			//If we don't want to eat the power pill yet we hover near by
			//This is achieved by not scoring the pp when we are 1 node away
			boolean scoreIt = (wantsToBeEaten && !waiting);
			if (!scoreIt) {
				if (maze.pacmanDistance(pp) > 1)
					scoreIt = true;
				else { //We are 1 node away from eating power pill
					GHOST g = nearestHunter(pp);
					if (g != null && maze.ghostDistance(g, pp) <= EAT_DISTANCE + 2)
						scoreIt = true;
					else {
						g = nearestHunter(game.getPacmanCurrentNodeIndex());
						if (g != null && maze.ghostDistance(g, game.getPacmanCurrentNodeIndex()) <= EAT_DISTANCE + 2)
							scoreIt = true;
					}
				}
			}
			if (scoreIt) {	
				if (danger) { //Treat this as an escape route
					scores[pp] += ESCAPE*maze.weighting(pp);
					if (lastDeath == game.getCurrentLevel())
						scores[pp] += ESCAPE; //We died on this level so reward a safer approach
				}
				if (wantsToBeEaten) //Either end of level or many ghosts in edible range
					scores[pp] += 250;
			}
		}
	}
	
	private void scoreEdibleGhosts() {
		/*
		 * Score edible ghosts
		 * If the ghost can reach a junction before we catch it then we will have to chase it, otherwise we might be quicker intercepting it
		 */
		ArrayList<GHOST> edible = new ArrayList<GHOST>();
		for (GHOST g: GHOST.values())
			if (game.getGhostEdibleTime(g) > 0)
				edible.add(g);
		ArrayList<nodeMove> chase = maze.chaseOrder(edible);
		//System.out.printf("Tick %d: %d edible ghosts - %d in range. Score %d\n", game.getTotalTime(), edible.size(), chase.size(), maze.chaseScore(chase));
		if (chase.size() > 0)
			scores[chase.get(0).node] +=  400;
	}
	
	/*
	 * Populates the score for each node
	 * -1 means unsafe, anything positive means safe, the higher the score the better
	 * 
	 * We can run in 3 modes
	 * 1. Eat pills
	 * 2. Lure ghosts
	 * 3. Escape
	 */
	private void scoreNodes() {	
		float safeZone = initialiseScores();
		int pill = scorePills();
		int esc = countEscapeRoutes();
		if (esc > 0 && lastDeath == game.getCurrentLevel()) //If we died pretend we have 1 less escape route making us more cautious
			esc--;
		int cpp = countPowerPills();
		boolean danger = (maze.access() < 40 && esc == 0);
		boolean needPowerPill = (cpp > 0 && danger);
		boolean escape = (needPowerPill || (maze.access() < 40 && esc <= 2 && cpp == 0));
		boolean eatPill = (pill != -1 && !escape && safeZone > risk && (game.getActivePillsIndices().length > 20 || game.getActivePowerPillsIndices().length == 0));
		boolean noMorePowerPills = (game.getActivePowerPillsIndices().length == 0);
		boolean lure = (!noMorePowerPills && !escape && safeZone > risk);

		if (eatPill && (noMorePowerPills || game.getCurrentLevelTime() < 500)) {
			//System.out.printf("Game level %d (%d): mode PILL\n", game.getCurrentLevel(), game.getCurrentLevelTime());
			scores[pill] += ESCAPE; //score this nearest pill as if it were an escape route
		} else {
			//System.out.printf("Game level %d (%d): mode %s\n", game.getCurrentLevel(), game.getCurrentLevelTime(), lure?"LURE":"ESCAPE");
			scoreEscapeRoutes(lure);
		}
		scorePowerPills(needPowerPill);	
		scoreEdibleGhosts();
	}
	
	/*
	 * Find the nearest junction to this node in the accessible zone
	 */
	private int nearestJunction(int node) {	
		int prev = -1;
		while (!game.isJunction(node)) {
			boolean trapped = true;
			for (int next: game.getNeighbouringNodes(node))
				if (next != prev && maze.hasAccess(next)) {
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
	 * Returns false if we don't need to eat this power pill
	 * Returns true if
	 * 1. All ghosts are within edible range
	 * 2. All but 1 is in range and we have plenty of power pills left
	 * 3. We are near the end of the level and might as well eat the power pill to stay safe an get points
	 */
	private boolean eatMe(int node)
	{
		int edibleTime = (int)(EDIBLE_TIME*(Math.pow(EDIBLE_TIME_REDUCTION,game.getCurrentLevel())));
		int timeRemaining = LEVEL_LIMIT - game.getCurrentLevelTime();
		int powerPillsRemaining = game.getActivePowerPillsIndices().length;
		int timeToEat = maze.pacmanDistance(node) + (edibleTime+120)*powerPillsRemaining;
		
		if (timeRemaining < timeToEat)
			return true;
		
		/*
		 * Estimate how many ghosts we could eat for this power pill
		 */
		int count = 0;
		ArrayList<GHOST> ghosts = new ArrayList<GHOST>();
		for (GHOST g: GHOST.values())
			if ((game.getGhostEdibleTime(g) == 0 || game.getGhostEdibleTime(g) < maze.pacmanDistance(node) - EAT_DISTANCE) && game.getGhostLairTime(g) == 0)
				ghosts.add(g);
		count = maze.chaseOrder(ghosts).size();

		int maxPowerPills = game.getPowerPillIndices().length;
		int pillsToEat = maxPowerPills*game.getCurrentLevelTime()/LEVEL_LIMIT;
		int pillsCanEat = Math.max(0,pillsToEat - (maxPowerPills - powerPillsRemaining));
		//System.out.printf("eatMe(%d) = count %d, threshold %d\n", node, count, NUM_GHOSTS - pillsCanEat);
		if (count >= NUM_GHOSTS - pillsCanEat)
			return true;

		return false;
	}
	
	
	/*
	 * Returns the id of the highest scoring node
	 */
	private int bestNode() {
		if (best != -1)
			scores[best]++;
		best = -1;
		scoreNodes();
		for (int i=0; i<scores.length; i++)
			if (scores[i] >= 0 && (best == -1 || scores[i] > scores[best]))
				best = i;

		return best;
	}

	/*
	 * Travel backwards from the end point to the current pacman position
	 */
	private void travel(float [] pathScores, int node, int dist, float score, ArrayList<Integer> path) {
		if (pathScores[node] == 0 || score > pathScores[node]) {
			pathScores[node] = score;
			path.add(0, node);
			if (dist > 1) {
				for (MOVE m: game.getPossibleMoves(node)) {
					int next = game.getNeighbour(node, m);
					if (maze.pacmanDistance(next) == dist-1)
						travel(pathScores, next, dist-1, score+scores[next], path);
				}
			} else if (bestPath == null || path.size() < bestPath.length ||
					(path.size() == bestPath.length && (score > bestScore || (score == bestScore && rnd.nextInt(2) == 0)))) {
				bestPath = new int[path.size()+1];
				bestPath[0] = game.getPacmanCurrentNodeIndex();
				bestScore = score;
				for (int i=0; i<path.size(); i++)
					bestPath[i+1] = path.get(i);
			}
			path.remove(0);
		}
	}
	
	/*
	 * Works out the path to the given node and returns the direction to head safely to the first node in the path
	 * If we are at the best node we try to stay still
	 */
	private MOVE bestDir(int to) {
		if (to == -1) {
			//System.out.printf("No best move found - tick %d\n", game.getCurrentLevelTime());
			return MOVE.NEUTRAL;
		}

		if (to == game.getPacmanCurrentNodeIndex()) {
			//If the last move is now an invalid direction we can issue it again to stay still
			if (game.getNeighbour(game.getPacmanCurrentNodeIndex(), game.getPacmanLastMoveMade()) == -1)
				return game.getPacmanLastMoveMade();
			
			//Find a valid direction who's opposite direction is invalid
			for (MOVE v: game.getPossibleMoves(game.getPacmanCurrentNodeIndex()))
				if (game.getNeighbour(game.getPacmanCurrentNodeIndex(), v.opposite()) == -1 &&
						maze.isSafe(game.getNeighbour(game.getPacmanCurrentNodeIndex(), v)))
					return v;
			
			//No safe way to stay here - pick a safe move
			for (MOVE v: game.getPossibleMoves(game.getPacmanCurrentNodeIndex()))
				if (maze.isSafe(game.getNeighbour(game.getPacmanCurrentNodeIndex(), v)))
					return v;
			
			return MOVE.NEUTRAL;
		}
		
		//Walk the safe nodes and find the path with the shortest distance
		float[] pathScore = new float[game.getNumberOfNodes()]; //Total score of all nodes on path
		bestPath = null;
		bestScore = 0;
		travel(pathScore, to, maze.pacmanDistance(to), scores[game.getPacmanCurrentNodeIndex()], new ArrayList<Integer>());
		if (bestPath != null && bestPath.length > 1) {
			for (int i=1; i<bestPath.length; i++)
			if (PATH_DEBUG && game.getNodeXCood(bestPath[i]) != 0 && game.getNodeXCood(bestPath[i-1]) != 0)
				GameView.addLines(game, Color.YELLOW, bestPath[i], bestPath[i-1]);

			for (MOVE d: game.getPossibleMoves(bestPath[0]))
				if (game.getNeighbour(bestPath[0], d) == bestPath[1])
					return d;
		}
		
		return MOVE.NEUTRAL;
	}
	
	
}