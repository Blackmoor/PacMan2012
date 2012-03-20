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
	private class ghostInfo {
		public int		distance;	// Distance to the node
		public MOVE		dir;		//Direction of arrival
		public boolean	hunter;		//true if the ghost is hunting, false if edible
		
		public ghostInfo() {
			this.distance = Integer.MAX_VALUE;
			this.dir = MOVE.NEUTRAL;
			this.hunter = false;
		}
	}
	
	private class pacmanInfo {
		public int		distance;	//Distance to node
		public MOVE		dir;		//Direction of arrival
		
		public pacmanInfo() {
			this.distance = Integer.MAX_VALUE;
			this.dir = MOVE.NEUTRAL;
		}
	}
	
	private Game 			game;
	private pacmanInfo[]	pacman;	//Distance of pacman from each node - updated each game tick
	private ghostInfo[][]	ghosts; //Distance of each ghost from each node - updated each game tick
	private float[] 		block; //The combined odds for any ghost to block each node
	private float[]			scores; //The score for each node
	private int[]			bestPath; //The nodes that make up the best path for the pacman to take
	private float			bestScore; //The total score of all nodes on the best path
	
	private static final Random			rnd = new Random(); //Used to break tie breaks in paths with the same score
	
	private static final int	CUTOFF = 350;
	private static final boolean PATH_DEBUG = false;
	private static final boolean ZONE_DEBUG = false;
	final Color[] ghostColour = new Color[] { Color.red, Color.pink, Color.orange, Color.cyan };
	
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
		ghosts = new ghostInfo[NUM_GHOSTS][game.getNumberOfNodes()];
		for (int g=0; g<NUM_GHOSTS; g++)
			for (int n=0; n<game.getNumberOfNodes(); n++)
				ghosts[g][n] = new ghostInfo();
		
		for (GHOST g: GHOST.values()) {
			if (game.getGhostLairTime(g) > 0)
				ghostWalk(g, game.getGhostInitialNodeIndex(), MOVE.NEUTRAL, game.getGhostLairTime(g));
			else
				ghostWalk(g, game.getGhostCurrentNodeIndex(g), game.getGhostLastMoveMade(g).opposite(), 0);
		}
	}
	
	private void pacmanMovement() {
		/*
		 * Populate the distance to each node for the PacMan, a node is blocked if the nearest ghost gets to it before the pacman
		 */
		pacman = new pacmanInfo[game.getNumberOfNodes()];
		for (int n=0; n<game.getNumberOfNodes(); n++)
			pacman[n] = new pacmanInfo();
		pacmanWalk(game.getPacmanCurrentNodeIndex(), 0, MOVE.NEUTRAL);
	}
	
	private float initialiseScores() {
		/*
		 * Set the score for each reachable node to 1 and unreachable to -1
		 */
		float result = 0; //Percentage of node that are safe
		block = new float[game.getNumberOfNodes()];
		scores = new float[game.getNumberOfNodes()];
		for (int node=0; node<scores.length; node++) {
			scores[node] = -1;
			if (game.getNeighbouringNodes(node).length > 0) {
				ArrayList<GHOST> nearestHunter = nearestBlocker(node);
				if (nearestHunter.size() == 0 || ghosts[ghostIndex(nearestHunter.get(0))][node].distance - pacman[node].distance > EAT_DISTANCE) { //Safe
					scores[node] = 1;
					result++;
					/*
					if (ZONE_DEBUG)
						GameView.addPoints(game, new Color(0, 128, 0), node);
					*/
				}
			}
		}
		return result / game.getNumberOfNodes() * 100;
	}
	
	private int scoreEscapeRoutes() {
		int escapeScore = 70;
		
		//Score escape routes
		int []eventHorizon = getEventHorizon(scores);
		for (int n: eventHorizon) {
			int jn = nearestJunction(n, scores);
			if (jn != -1)
				scores[jn] += escapeScore*(1-block[jn]);
		}
		
		return eventHorizon.length;
	}
	
	private ArrayList<GHOST> nearestBlocker(int node)
	{
		ArrayList<GHOST> result = new ArrayList<GHOST>();
		int nearestHunter = Integer.MAX_VALUE;
		
		//Find nearest Hunting Ghost going in a different direction to the pacman
		for (GHOST g: GHOST.values()) {
			int dist = ghosts[ghostIndex(g)][node].distance;
			if (ghosts[ghostIndex(g)][node].hunter && dist < nearestHunter && (pacman[node].distance == Integer.MAX_VALUE || ghosts[ghostIndex(g)][node].dir != pacman[node].dir)) {
				nearestHunter = dist;
				result.clear();
				result.add(g);
			} else if (ghosts[ghostIndex(g)][node].hunter && dist == nearestHunter && (pacman[node].distance == Integer.MAX_VALUE || ghosts[ghostIndex(g)][node].dir != pacman[node].dir)) {
				result.add(g);
			}
		}
		
		return result;
	}

	private void scorePills(boolean safe) {
		for (int p: game.getActivePillsIndices())
			if (scores[p] >= 0)
				scores[p] += PILL;
		
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
				if (pacman[p].distance < dist && (!safe || eat)) {
					pp = p;
					dist = pacman[p].distance;
				}
				//If we don't want to eat the power pill yet we hover near by
				//This is achieved by not scoring the pp when we are 1 node away if we are safe
				if (!eat || !safe) {
					if (pacman[p].distance - EAT_DISTANCE > 1)
						eat = true;
					else {
						ArrayList<GHOST> g = nearestBlocker(p);
						if (g.size() > 0 && pacman[game.getGhostCurrentNodeIndex(g.get(0))].distance <= 4)
							eat = true;
					}
				}
				if (eat)
					scores[p] += POWER_PILL;
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
				if (pacman[jn].distance <= ghosts[ghostIndex(g)][jn].distance) { //We are closer to the junction so we will catch it on the current path
					//Work out if it is quicker to chase the ghost or intercept it
					if (pacman[game.getGhostCurrentNodeIndex(g)].dir == game.getGhostLastMoveMade(g)) { //Chase distance is twice current distance
						target = getGhostNode(g, pacman[game.getGhostCurrentNodeIndex(g)].distance);
						chase = 2*pacman[target].distance;
					} else { //Intercept distance is 2/3 current distance
						target = getGhostNode(g, pacman[game.getGhostCurrentNodeIndex(g)].distance/3);
						chase = 2*pacman[target].distance/3;
					}
				} else { //We head to junction then chase it
					target = jn;
					chase = 3*pacman[target].distance-ghosts[ghostIndex(g)][target].distance;
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
		float safeZone = initialiseScores();
		int routes = scoreEscapeRoutes();
		scorePills(safeZone > 80 || routes > NUM_GHOSTS);	
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
	 * Find all nodes that are safe and have an unsafe neighbour
	 * Score each one accord to how likely a ghost is to block it
	 */
	private int [] getEventHorizon(float []scores) {
		ArrayList<Integer> edge = new ArrayList<Integer>();
		
		for (int n=0; n<game.getNumberOfNodes(); n++) {
			if (scores[n] >= 0) {
				for (int next: game.getNeighbouringNodes(n)) {
					if (scores[next] < 0) {
						edge.add(n);
						break;
					}
				}
			}
		}
		
		//Work out how much wiggle room we have, this is the time before we are eaten if we stay still
		ArrayList<GHOST> ng = nearestBlocker(game.getPacmanCurrentNodeIndex());
		int decisionTime = CUTOFF;
		if (ng.size() > 0)
			decisionTime = ghosts[ghostIndex(ng.get(0))][game.getPacmanCurrentNodeIndex()].distance - EAT_DISTANCE - 1;
		
		float likelihood[] = new float[edge.size()];
		for (GHOST g: GHOST.values()) {
			ArrayList<ArrayList<Integer>> segments = makeSegments(g, edge);
			if (PATH_DEBUG) {
				for (ArrayList<Integer> segment: segments)
					for (int i=1; i<segment.size(); i++)
						if (game.getNodeXCood(segment.get(i)) != 0 && game.getNodeXCood(segment.get(i-1)) != 0)
							GameView.addLines(game, ghostColour[ghostIndex(g)], segment.get(i), segment.get(i-1));
			}
			//Now we need to score the event horizon points according to how likely a ghost is to block it
			//When a ghost needs to make a decision, we check to see if the pacman needs to make one first, if so the pacman is blocked
			for (int i=0; i<edge.size(); i++) {			
				ArrayList<ArrayList<Integer>> pathSegments = makePath(segments, edge.get(i));
				//Check the likelihood of blocking each end point of the segments that make up the total path
				if (pathSegments.size() > 0) {
					int jn = nearestJunction(edge.get(i), scores); //This is the point the pacman must make a decision
					int pacmanChoice = decisionTime;
					if (jn != -1)
						pacmanChoice += pacman[jn].distance;
					int choices = pathSegments.size()+1;
					for (ArrayList<Integer> s: pathSegments) {
						int node = s.get(s.size()-1); //the next junction where ghost makes a choice
						if (pacmanChoice + decisionTime < ghosts[ghostIndex(g)][node].distance)
							choices--;
						else
							break;
					}
					likelihood[i] = 1f-(1f-1f/choices)*(1f-likelihood[i]);
					block[edge.get(i)] = 1f-likelihood[i];
					if (jn != -1)
						block[jn] = 1f - (1f-likelihood[i])*(1f-block[jn]);
				}
			}
		}
		
		int [] result = new int[edge.size()];
		for (int i=0; i<edge.size(); i++) {
			result[i] = edge.get(i);
			if (ZONE_DEBUG) {
				for (GHOST g: nearestBlocker(edge.get(i))) {
					Color c = ghostColour[ghostIndex(g)];
					GameView.addPoints(game, new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(255*(block[edge.get(i)]))), edge.get(i));
				}
			}
		}
		return result;
	}
	
	/*
	 * Given a set of path segments, return the subset that make up the path to the given node
	 * The entries are ordered such that the given node is at the end
	 */
	private ArrayList<ArrayList<Integer>> makePath(ArrayList<ArrayList<Integer>> segments,  int node) {
		ArrayList<ArrayList<Integer>> result = new ArrayList<ArrayList<Integer>>();
		
		ArrayList<Integer> found = getSegment(segments, node);
		while (found != null) {
			result.add(0, found);
			found = getSegment(segments, found.get(0));
			if (result.size() > 30)
				break;
		}
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
	 * Scan backwards from the event horizon and record the path segments that make up the best routes to the end points
	 */
	private ArrayList<ArrayList<Integer>> makeSegments(GHOST g, ArrayList<Integer> end) {
		ArrayList<ArrayList<Integer>> result = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> active = new ArrayList<ArrayList<Integer>>();
		
		int dist = 0;
		for (int node: end) {
			//Check to see if this ghost is the nearest blocker to this node
			if (nearestBlocker(node).contains(g)) {
				ArrayList<Integer> path = new ArrayList<Integer>();
				path.add(node);
				result.add(path);
				active.add(path);
				if (ghosts[ghostIndex(g)][node].distance > dist)
					dist = ghosts[ghostIndex(g)][node].distance;
			}
		}
		
		if (result.size() == 0)
			return result;
		
		//Changing the array list within the iterator breaks it, so we create a list of entries to remove or add and do it later
		ArrayList<ArrayList<Integer>> toAdd = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> toRemove = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> deactivate = new ArrayList<ArrayList<Integer>>();
		//Starting at the farthest point we add in the next node to each path
		while (dist-- > 0) {
			for (ArrayList<Integer> path: active) {
				ArrayList<Integer> next = nearestNeighbour(g, path.get(0), dist);
				if (next.size() > 1) { //There is more than 1 equi-distant route.
					//Create new segments with the current node as the end point
					for (int n: next) {
						ArrayList<Integer> s = new ArrayList<Integer>();
						s.add(0, path.get(0));
						s.add(0, n);
						toAdd.add(s);
					}
					deactivate.add(path);
				} else if (next.size() == 1) {
					//System.out.printf("Node %d -> %d\n", path.get(0), next.get(0));
					path.add(0, next.get(0));
				}
			}
			System.out.println("");
			result.addAll(toAdd);
			active.addAll(toAdd);
			toAdd.clear();
			active.removeAll(deactivate);
			deactivate.clear();

			//Search through the active segments, any that have the same starting node must be merged
			for (int i=0; i<active.size(); i++) {
				ArrayList<Integer> path = active.get(i);
				boolean merge = false;	//set to true if a merge is found
				for (int j=i+1; j<active.size(); j++) {
					ArrayList<Integer> compare = active.get(j);
					if ((int)path.get(0) == (int)compare.get(0)) {
						deactivate.add(compare);
						//Also remove any split segments that end at this end point
						for (int k=0; k<active.size(); k++) {
							ArrayList<Integer> split = active.get(k);
							if (k != j && (int)split.get(split.size()-1) == (int)compare.get(compare.size()-1))
								toRemove.add(split);
						}
						merge = true;
					}
				}
				if (merge) {
					deactivate.add(path);
					//Also remove any split segments that end at this end point
					for (int k=0; k<active.size(); k++) {
						ArrayList<Integer> split = active.get(k);
						if (k != i && (int)split.get(split.size()-1) == (int)path.get(path.size()-1))
							toRemove.add(split);
					}
					ArrayList<Integer> s = new ArrayList<Integer>();
					s.add(path.get(0));
					toAdd.add(s);
				}
			}
			active.removeAll(toRemove);
			result.removeAll(toRemove);
			toRemove.clear();
			active.addAll(toAdd);
			result.addAll(toAdd);
			toAdd.clear();
			active.removeAll(deactivate);
			deactivate.clear();
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
	 */
	private ArrayList<Integer> nearestNeighbour(GHOST g, int node, int dist) {
		ArrayList<Integer> result = new ArrayList<Integer>();
		
		for (int n: game.getNeighbouringNodes(node)) {
			if (ghosts[ghostIndex(g)][n].distance == dist)
				result.add(n);
		}
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
		int timeToEat = pacman[node].distance + 50 + edibleTime*powerPillsRemaining + (powerPillsRemaining-1)*120;
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
				if (!processed[ghostIndex(g)] && game.getGhostLairTime(g) < pacman[node].distance) {
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
	 * Hunting ghosts block the path
	 */
	private void pacmanWalk(int node, int dist, MOVE dir) {
		if (dist < CUTOFF && dist < pacman[node].distance) {
			pacman[node].distance = dist;
			pacman[node].dir = dir;
			if (!isBlocked(node)) {
				for (MOVE m: game.getPossibleMoves(node))
					pacmanWalk(getNeighbour(node, m), dist+1, m);
			}
		}
	}
	
	/*
	 * Walk the maze as a ghost and record the distance to each node and the number of turns at jns
	 */
	private void ghostWalk(GHOST g, int node, MOVE banned, int dist) {
		if (dist < CUTOFF && dist < ghosts[ghostIndex(g)][node].distance) {
			ghosts[ghostIndex(g)][node].distance = dist;	
			ghosts[ghostIndex(g)][node].dir = banned.opposite();			
			ghosts[ghostIndex(g)][node].hunter = (game.getGhostEdibleTime(g) <= dist);

			for (MOVE m: game.getPossibleMoves(node)) {
				int next = getNeighbour(node, m);
				if (next != -1 && m != banned)
					ghostWalk(g, next, m.opposite(), dist + (ghosts[ghostIndex(g)][node].hunter?1:2));
			}
		}
	}
	
	/*
	 * Hunter ghosts block the maze walk
	 */
	private boolean isBlocked(int node) {
		/*
		for (int pp: game.getActivePowerPillsIndices())
			if (pp == node)
				return true;
		*/
		for (GHOST g: GHOST.values()) {
			/*
			 * Only hunter ghosts can block and then only if they are
			 * 1. Get to the node before us from a different direction
			 * 2. Are travelling in the same direction and we are within 2 nodes of the eat distance from us as a game.reverse would kill us
			 */
			if (ghosts[ghostIndex(g)][node].hunter &&
					((ghosts[ghostIndex(g)][node].dir != pacman[node].dir && ghosts[ghostIndex(g)][node].distance - pacman[node].distance <= EAT_DISTANCE) ||
					 (ghosts[ghostIndex(g)][node].dir == pacman[node].dir && ghosts[ghostIndex(g)][node].distance < pacman[node].distance && pacman[node].distance - ghosts[ghostIndex(g)][node].distance <= EAT_DISTANCE + 2)))
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
