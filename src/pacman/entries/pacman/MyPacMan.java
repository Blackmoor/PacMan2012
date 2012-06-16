package pacman.entries.pacman;


import java.awt.Color;
import java.util.ArrayList;
import java.util.Random;

import pacman.controllers.Controller;
import pacman.entries.pacman.maze.nodeMove;
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
	private Game			prev = null; // The game state on the last turn
	private float			risk = 30; //How much risk we can take (20 = lots, 60 = conservative)
	
	private static final Random			rnd = new Random(); //Used to break tie breaks in paths with the same score
	
	//Place your game logic here to play the game as Ms Pac-Man
	public MOVE getMove(Game game,long timeDue)
	{
		if (prev != null && wasEaten(prev, game))
			risk += 15;
		prev = this.game;
		risk -= 0.001f;
		
		this.game = game;
		maze = new maze(game);
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
					GameView.addPoints(game, new Color(0, maze.weighting(node), 0), node);
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
	private int furthestNode(ArrayList<Integer> edge) {
		//We use the event horizon as the starting locations for a breadth first search over the accessible zone
		int last = -1;
		boolean[] visited = new boolean[game.getNumberOfNodes()];
		
		while (edge.size() > 0) {
			for (int n: edge) {
				visited[n] = true;
			}
			/*
			 * New edge is 1 move from each point on the current edge
			 * Only allow moves in the save zone (score > 0)
			 */
			ArrayList<Integer>	next = new ArrayList<Integer>();
			for (int n: edge) {
				for (MOVE d: game.getPossibleMoves(n)) {
					int node = game.getNeighbour(n, d);
					if (!visited[node] && maze.hasAccess(node) && !next.contains(node))
						next.add(node);
				}
				last = n;
			}
			
			edge = next;
		}
		
		return last;
	}
	
	/*
	 * Score the escape routes and return true if trapped
	 */
	private boolean scoreEscapeRoutes() {	
		//Score escape routes and count the number of routes that are blocked by the same ghost
		int count = 0;
		int total = 0;
		ArrayList<Integer> edge = new ArrayList<Integer>();
		final Color[] ghostColour = new Color[] { Color.red, Color.pink, Color.cyan, Color.orange };
		int g = 0;
		
		for (ArrayList<Integer> gev: maze.eventHorizon()) {
			if (gev.size() > 1)
				count += gev.size() - 1;
			for (int n: gev) {
				if (g < NUM_GHOSTS) {
					total++;
					int jn = nearestJunction(n);
					if (jn != -1) {
						scores[jn] += ESCAPE*maze.weighting(jn);
					}
					if (ZONE_DEBUG)
						GameView.addPoints(game, ghostColour[g], n);
				}
				edge.add(n);
			}
			g++;
		}
		
		//Find the node in the safe zone furthest from the event horizon - when we are completely blocked, this is the best place to head
		int node = furthestNode(edge);	
		if (node != -1) {
			scores[node]+=3;
			if (ZONE_DEBUG)
				GameView.addPoints(game, Color.MAGENTA, node);
		}
		
		return (total <= NUM_GHOSTS || count == 0);
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

	private void scorePills(boolean safe) {
		int best = -1;
		for (int p: game.getActivePillsIndices())
			if (maze.isSafe(p)) {
				scores[p] += PILL;
				if (best == -1 || maze.pacmanDistance(p) < maze.pacmanDistance(best))
					best = p;
			}
		if (best != -1 && safe)
			scores[best] += POWER_PILL;
		
		/*
		 * Count how many ghosts would be in edible range
		 */
		ArrayList<GHOST> ghosts = new ArrayList<GHOST>();
		for (GHOST g: GHOST.values())
			if (game.getGhostLairTime(g) == 0)
				ghosts.add(g);
		int score = maze.chaseScore(maze.chaseOrder(ghosts));
		waiting = (score > edibleScore);
		edibleScore = score;		
		//System.out.printf("Tick %d - edible score %d\n", game.getTotalTime(), score);
		
		/*
		 * Pick the nearest safe power pill that wants to be eaten.
		 * If none want to be eaten, pick the nearest power pill
		 */
		int dist = Integer.MAX_VALUE;
		int pp = -1;
		boolean mustEat = false; //set to true if we are only comparing power pills we want to eat
		for (int p: game.getActivePowerPillsIndices()) {
			boolean eat = eatMe(p); //Do we want to eat this power pill
			if (maze.hasAccess(p) && ((eat && !mustEat) || ((eat || !mustEat) && maze.pacmanDistance(p) < dist))) {
				if (eat)
					mustEat = true;
				pp = p;
				dist = maze.pacmanDistance(p);
			}
		}
		
		if (pp != -1) {
			//If we don't want to eat the power pill yet we hover near by
			//This is achieved by not scoring the pp when we are 1 node away
			boolean scoreIt = (mustEat && !waiting);
			if (!scoreIt) {
				if (maze.pacmanDistance(pp) - EAT_DISTANCE > 1)
					scoreIt = true;
				else { //We are 1 node away from eating power pill
					GHOST g = nearestHunter(pp);
					if (g != null && maze.ghostDistance(g, pp) <= EAT_DISTANCE + 2)
						scoreIt = true;
					else {
						g = nearestHunter(game.getPacmanCurrentNodeIndex());
						if (g != null && maze.pacmanDistance(game.getGhostCurrentNodeIndex(g)) <= EAT_DISTANCE + 2)
							scoreIt = true;
					}
				}
			}
			if (scoreIt) {	
				scores[pp] += POWER_PILL;
				if (!safe)					
					scores[pp] += ESCAPE*maze.weighting(pp);
				if (mustEat)
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
	 */
	private void scoreNodes() {	
		float safeZone = initialiseScores();
		boolean trapped = false;
		
		if (safeZone < risk)
			trapped = scoreEscapeRoutes();
		scorePills(!trapped);	
		scoreEdibleGhosts();
	}
	
	/*
	 * Find the nearest junction to this node in the safe zone
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
				if (game.getNeighbour(game.getPacmanCurrentNodeIndex(), v.opposite()) == -1)
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