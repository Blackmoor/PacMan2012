package pacman.entries.pacman;


import java.awt.Color;
import java.util.ArrayList;
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
	private Game 			game;
	private int[]			pacman;	//Distance of pacman from each node - updated each game tick
	private MOVE[]			pacdir;	//The direction the pacman was moving when it reached each node for the first time.
	private int[][]			ghosts; //Distance of each ghost from each node - updated each game tick
	private MOVE[][]		dirs;	//The direction a ghost was moving at each node - updated each game tick
	private boolean[][]		hunter;	//True if the the ghost is not edible at this node - updated each game tick.
	private int[][] 		turns; //The number of turn choices the ghost makes to reach each node
	private float[] 		block; //The combined odds for any ghost to block each node
	private float[]			scores; //The score for each node
	private int[]			bestPath; //The nodes that make up the best path for the pacman to take
	private float			bestScore; //The total score of all nodes on the best path
	
	private static final Random			rnd = new Random(0); //Used to break tie breaks in paths with the same score

	//Debug info
	int				lastTarget;
	int				lastDir;
	int				lastLives;
	int				lastPos;
	
	private static final int	CUTOFF = 350;
	private static final boolean PATH_DEBUG = false;
	private static final boolean ZONE_DEBUG = false;
	
	//Place your game logic here to play the game as Ms Pac-Man
	public MOVE getMove(Game game,long timeDue)
	{
		this.game = game;
		int best = bestNode();
		MOVE dir = bestDir(best);
		
		return dir;
	}
	
	private int ghostIndex(GHOST g)
	{
		switch (g) {
		case BLINKY: return 0;
		case PINKY: return 1;
		case INKY: return 2;
		case SUE: return 3;
		}
		return -1;
	}
	
	private void ghostMovement() {
		/*
		 * For each ghost, populate the ghosts and turns arrays
		 */
		ghosts = new int[NUM_GHOSTS][game.getNumberOfNodes()];
		turns = new int[NUM_GHOSTS][game.getNumberOfNodes()];
		dirs = new MOVE[NUM_GHOSTS][game.getNumberOfNodes()];
		hunter = new boolean[NUM_GHOSTS][game.getNumberOfNodes()];
		for (int g=0; g<NUM_GHOSTS; g++)
			for (int n=0; n<game.getNumberOfNodes(); n++)
				ghosts[g][n] = CUTOFF;
		
		for (GHOST g: GHOST.values()) {
			if (game.getGhostLairTime(g) > 0)
				ghostWalk(g, game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, game.getGhostLairTime(g), 0);
			else
				ghostWalk(g, game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g).opposite(), 0, 0);
		}
	}
	
	private void pacmanMovement() {
		/*
		 * Populate the distance to each node for the PacMan, a node is blocked if the nearest ghost gets to it before the pacman
		 */
		pacman = new int[game.getNumberOfNodes()];
		pacdir = new MOVE[game.getNumberOfNodes()];
		walk(game.getPacmanCurrentNodeIndex(), 0, MOVE.NEUTRAL);
		pacman[game.getPacmanCurrentNodeIndex()] = 0;
	}
	
	private void initialiseScores() {
		/*
		 * Set the score for each reachable node to 1 and unreachable to -1
		 */
		block = new float[game.getNumberOfNodes()];
		scores = new float[game.getNumberOfNodes()];
		for (int node=0; node<scores.length; node++) {
			scores[node] = -1;
			float chance = 1f;
			for (GHOST g: GHOST.values())
				if (hunter[ghostIndex(g)][node])
					chance *= (1f-1f/(1+turns[ghostIndex(g)][node]));
			block[node] = 1f - chance;
			if (game.getNeighbouringNodes(node).length > 0) {
				GHOST nearestHunter = nearestBlocker(node);
				if (nearestHunter == null || ((node == game.getPacmanCurrentNodeIndex() || pacman[node] > 0) &&
												ghosts[ghostIndex(nearestHunter)][node] - pacman[node] > EAT_DISTANCE)) { //Safe
					scores[node] = 1;
					if (ZONE_DEBUG)
						GameView.addPoints(game, new Color(0, weighting(node), 0), node);
				}
			}
		}				
	}
	
	private int scoreEscapeRoutes() {
		int escapeScore = 70;
		final Color[] ghostColour = new Color[] { Color.red, Color.pink, Color.orange, Color.cyan };
		
		//Score escape routes
		int []eventHorizon = getEventHorizon(scores);
		for (int n: eventHorizon) {
			int jn = nearestJunction(n, scores);
			if (jn != -1)
				scores[jn] += escapeScore*weighting(jn);
			if (ZONE_DEBUG)
				GameView.addPoints(game, ghostColour[ghostIndex(nearestBlocker(n))], n);
		}
		
		return eventHorizon.length;
	}
	
	private GHOST nearestBlocker(int node)
	{
		int nearestHunter = Integer.MAX_VALUE;
		GHOST id = null;
		
		//Find nearest Hunting Ghost going in a different direction to the pacman
		for (GHOST g: GHOST.values()) {
			int dist = ghosts[ghostIndex(g)][node];
			if (hunter[ghostIndex(g)][node] && dist < nearestHunter && dirs[ghostIndex(g)][node] != pacdir[node]) {
				nearestHunter = dist;
				id = g;
			}
		}
		
		return id;
	}
	
	private float weighting(int node)
	{
		return 1f-block[node];
	}

	private void scorePills(boolean safe) {
		for (int p: game.getActivePillsIndices())
			if (scores[p] >= 0)
				scores[p] += PILL*weighting(p);
		
		/*
		 * Score the best power pill - we want to wait near to these until we have to eat it
		 * This is achieved by setting the score to zero if we are within 1 of the EAT_DISTANCE but the nearest ghost is not
		 * Pick the nearest reachable power pill that wants to be eaten.
		 */
		int dist = Integer.MAX_VALUE;
		int pp = -1;
		for (int p: game.getActivePowerPillsIndices()) {
			if (scores[p] >= 0) {
				boolean eat = eatMe(p);
				if (pacman[p] < dist && eat) {
					pp = p;
					dist = pacman[p];
				}
				//If we don't want to eat the power pill yet we hover near by
				//This is achieved by not scoring the pp when we are 1 node away if we are safe
				if (!eat || !safe) {
					if (pacman[p] - EAT_DISTANCE > 1)
						eat = true;
					else {
						GHOST g = nearestBlocker(p);
						if (g != null && pacman[game.getGhostCurrentNodeIndex(g)] <= 4)
							eat = true;
					}
				}
				if (eat)
				scores[p] += POWER_PILL*weighting(p);
			}
		}
		if (pp != -1)
			scores[pp] += 600;
	}
	
	private void scoreEdibleGhosts() {
		/*
		 * Score edible ghosts
		 * If the ghost can reach a junction before we catch it then we will have to chase it, otherwise we might be quicker intercepting it
		 */
		int ng = -1;	//nearest edible ghost
		int nd = Integer.MAX_VALUE;	//distance to nearest edible ghost
		for (GHOST g: GHOST.values()) {
			if (game.getGhostEdibleTime(g) > 0) {
				int jn = getGhostNode(g, Integer.MAX_VALUE);	//The junction the ghost is heading to
				int chase; //Distance travelled to catch ghost
				int target;
				if (pacman[jn] <= ghosts[ghostIndex(g)][jn]) { //We are closer to the junction so we will catch it on the current path
					//Work out if it is quicker to chase the ghost or intercept it
					if (pacdir[game.getGhostCurrentNodeIndex(g)] == game.getGhostLastMoveMade(g)) { //Chase distance is twice current distance
						target = getGhostNode(g, pacman[game.getGhostCurrentNodeIndex(g)]);
						chase = 2*pacman[target];
					} else { //Intercept distance is 2/3 current distance
						target = getGhostNode(g, pacman[game.getGhostCurrentNodeIndex(g)]/3);
						chase = 2*pacman[target]/3;
					}
				} else { //We head to junction then chase it
					target = jn;
					chase = 3*pacman[target]-ghosts[ghostIndex(g)][target];
				}
				if (scores[target] >= 0 && game.getGhostEdibleTime(g) - chase > EAT_DISTANCE && chase < nd) {
					nd = chase;
					ng = target;
				}
			}
		}
		if (ng != -1) //Add bonus points for nearest ghost
			scores[ng] += game.getGhostCurrentEdibleScore();
	}
	
	/*
	 * Returns the score for each node
	 * -1 means unsafe, anything positive means safe, the higher the score the better
	 */
	private void scoreNodes() {
		ghostMovement();
		pacmanMovement();
		initialiseScores();
		int routes = scoreEscapeRoutes();
		scorePills(routes > NUM_GHOSTS);
		scoreEdibleGhosts();	
	}
	
	/*
	 * Find the node where the ghost will be in N moves time
	 * Stop if we reach a junction
	 */
	private int getGhostNode(GHOST g, int turns) {
		MOVE dir = game.getGhostLastMoveMade(g);
		int node = game.getGhostCurrentNodeIndex(g);
		
		if (game.getGhostLairTime(g) > 0)
			return node;
		
		while (!game.isJunction(node) && turns-- > 0) {
			dir = game.getPossibleMoves(node, dir)[0];
			node = getNeighbour(node, dir);
		}
		
		return node;
	}
	
	/*
	 * Find all nodes that are unsafe and have an safe neighbour
	 */
	private int [] getEventHorizon(float []scores) {
		ArrayList<Integer> edge = new ArrayList<Integer>();
		
		for (int n=0; n<game.getNumberOfNodes(); n++) {
			if (scores[n] < 0) {
				for (int next: game.getNeighbouringNodes(n)) {
					if (scores[next] >= 0) {
						edge.add(n);
						break;
					}
				}
			}
		}
		int [] result = new int[edge.size()];
		for (int i=0; i<edge.size(); i++)
			result[i] = edge.get(i);
		return result;
	}
	
	/*
	 * Find the nearest junction to this node in the safe zone
	 */
	private int nearestJunction(int node, float[] scores) {	
		int prev = -1;
		while (!game.isJunction(node)) {
			boolean trapped = true;
			for (int next: game.getNeighbouringNodes(node))
				if (next != prev && scores[next] != -1) {
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
	 */
	private boolean eatMe(int node)
	{
		int edibleTime = (int)(EDIBLE_TIME*(Math.pow(EDIBLE_TIME_REDUCTION,game.getCurrentLevel())));
		int timeRemaining = LEVEL_LIMIT - game.getCurrentLevelTime();
		int powerPillsRemaining = game.getActivePowerPillsIndices().length;
		int timeToEat = pacman[node] + 50 + edibleTime*powerPillsRemaining + (powerPillsRemaining-1)*120;
		if (timeRemaining < timeToEat)
			return true;
		
		/*
		 * Estimate how many ghosts we could eat for this power pill
		 * We head to the nearest ghost first and then repeat for the next nearest ghost.
		 * We assume worst case scenario, where we have to chase down the edible ghost which doubles the distance travelled.
		 */
		int count = 0;
		int dist = 0;
		boolean processed[] = new boolean[NUM_GHOSTS];
		int from = game.getPacmanCurrentNodeIndex();
		while (dist < edibleTime / 2) {
			//Find nearest unprocessed ghost that would be edible
			GHOST ng = null;
			int gd = Integer.MAX_VALUE;
		for (GHOST g: GHOST.values()) {
				if (!processed[ghostIndex(g)] && game.getGhostLairTime(g) < pacman[node]) {
					int path = (int)game.getDistance(from, (game.getGhostLairTime(g) > 0)?game.getGhostInitialNodeIndex():game.getGhostCurrentNodeIndex(g), DM.PATH);
					if (path < gd) {
						ng = g;
						gd = path;
					}
				}
			}
			if (ng == null)
				break;	//No more ghosts
			
			processed[ghostIndex(ng)] = true;
			dist += gd;
			from = game.getGhostCurrentNodeIndex(ng);
			if (dist < edibleTime / 2)
				count++;
		}
		
		int maxPowerPills = game.getPowerPillIndices().length;
		int pillsToEat = maxPowerPills*game.getCurrentLevelTime()/LEVEL_LIMIT;
		if (count >=3 && pillsToEat > maxPowerPills - powerPillsRemaining)
			return true;
		return false;
	}
	
	/*
	 * Walk the maze and record the distance to get to each node
	 * Hunting ghosts and power pills block the path
	 */
	private void walk(int node, int dist, MOVE dir) {
		if (dist < CUTOFF && (pacman[node] == 0 || dist < pacman[node])) {
			pacman[node] = dist;
			pacdir[node] = dir;
			if (!isBlocked(node, dist, dir)) {
				for (MOVE m: game.getPossibleMoves(node))
					walk(getNeighbour(node, m), dist+1, m);
			}
		}
	}
	
	/*
	 * Walk the maze as a ghost and record the distance to each node and the number of turns at jns
	 */
	private void ghostWalk(GHOST g, int node, MOVE banned, int dist, int t) {
		if (dist < CUTOFF && dist < ghosts[ghostIndex(g)][node]) {
			ghosts[ghostIndex(g)][node] = dist;	
			turns[ghostIndex(g)][node] = t;
			dirs[ghostIndex(g)][node] = banned.opposite();			
			hunter[ghostIndex(g)][node] = (game.getGhostEdibleTime(g) <= dist);

			for (MOVE m: game.getPossibleMoves(node)) {
				int next = getNeighbour(node, m);
				if (next != -1 && m != banned)
					ghostWalk(g, next, m.opposite(), dist + (hunter[ghostIndex(g)][node]?1:2), t + countMoves(node));
			}
		}
	}
	
	/*
	 * How many valid moves goes a ghost have at this node
	 */
	private int countMoves(int node)
	{
		int choices = game.getNeighbouringNodes(node).length;
		if (choices > 2)
			return choices -1;	//Can't go backwards
		return 0;
	}
	
	/*
	 * Power pills and hunter ghosts block the maze walk
	 */
	private boolean isBlocked(int node, int dist, MOVE dir) {
		for (int pp: game.getActivePowerPillsIndices())
			if (pp == node)
				return true;
		for (GHOST g: GHOST.values()) {
			/*
			 * Only hunter ghosts can block and then only if they are
			 * 1. Get to the node before us from a different direction
			 * 2. Are travelling in the same direction and we are within 4 nodes of us as a game.reverse would kill us
			 */
			if (hunter[ghostIndex(g)][node] &&
					((dirs[ghostIndex(g)][node] != dir && ghosts[ghostIndex(g)][node] <= dist + EAT_DISTANCE) ||
					 (dirs[ghostIndex(g)][node] == dir && ghosts[ghostIndex(g)][node] < dist && dist - ghosts[ghostIndex(g)][node] <= EAT_DISTANCE + 2)))
				return true;
		}
		return false;
	}
	
	/*
	 * Returns the id of the highest scoring node
	 */
	private int bestNode() {
		int best = -1;
		scoreNodes();
		for (int i=0; i<scores.length; i++)
			if (scores[i] >= 0 && (best == -1 || scores[i] > scores[best]) && i != game.getPacmanCurrentNodeIndex())
				best = i;

		return best;
	}
	
	/*
	 * Given a node, return the node in the given direction or -1 if not a valid move
	 */
	private int getNeighbour(int node, MOVE d)
	{
		MOVE[]	m = game.getPossibleMoves(node);
		int[]	n = game.getNeighbouringNodes(node);
		for (int i=0; i<m.length; i++) {
			if (m[i] == d)
				return n[i];
		}
		return -1;
	}
	
	private void travel(int[] travelled, float [] scores, float [] pathScores, MOVE dir, int to, int node, int dist, float score, ArrayList<Integer> path) {
		if (scores[node] >= 0 && dist < CUTOFF && (pathScores[node] == 0 || dist < travelled[node] || (dist == travelled[node] && score > pathScores[node]))) {
			travelled[node] = dist;
			pathScores[node] = score;
			int ix = path.size();
			path.add(ix, node);
			if (node != to) {
				for (MOVE m: game.getPossibleMoves(node)) {
					int next = getNeighbour(node, m);
					if (next != -1 && scores[next] >= 0)
						travel(travelled, scores, pathScores, (dir == MOVE.NEUTRAL)?m:dir, to, next, dist+1, score+scores[next], path);
				}
			} else if (bestPath == null || path.size() < bestPath.length ||
					(path.size() == bestPath.length && (score > bestScore || (score == bestScore && rnd.nextInt(2) == 0)))) {
				bestPath = new int[path.size()];
				bestScore = score;
				for (int i=0; i<path.size(); i++)
					bestPath[i] = path.get(i);
			}
			path.remove(ix);
		}
	}

	/*
	 * Works out the path to the given node and returns the direction to head safely to the first node in the path
	 */
	private MOVE bestDir(int to) {
		if (to == -1) {
			//System.out.printf("No best move found - tick %d\n", game.getCurrentLevelTime());
			return MOVE.NEUTRAL;
		}

		//Walk the safe nodes (score > 0) and find the path with the shortest distance
		int[] travelled = new int[game.getNumberOfNodes()]; //The shortest distance to each node
		float[] pathScore = new float[game.getNumberOfNodes()]; //Total score of all nodes on path
		bestPath = null;
		bestScore = 0;
		travel(travelled, scores, pathScore, MOVE.NEUTRAL, to, game.getPacmanCurrentNodeIndex(), 0, scores[game.getPacmanCurrentNodeIndex()], new ArrayList<Integer>());
		if (bestPath != null && bestPath.length > 1) {
			for (int i=1; i<bestPath.length; i++)
			if (PATH_DEBUG && game.getNodeXCood(bestPath[i]) != 0 && game.getNodeXCood(bestPath[i-1]) != 0)
				GameView.addLines(game, Color.YELLOW, bestPath[i], bestPath[i-1]);

			for (MOVE d: game.getPossibleMoves(bestPath[0]))
				if (getNeighbour(bestPath[0], d) == bestPath[1])
					return d;
		}
		
		return MOVE.NEUTRAL;
	}
}
