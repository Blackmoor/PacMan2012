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
	
	private class horizon {
		public int		safeNode;
		public int		unsafeNode;
		public float	blocked; //1 = blocked, 0.5 = 50% likely to be blocked
		public int		jn;		//nearest safe junction
		public float	jnBlocked; //as blocked - but for the junction (multiple ghosts can contribute to this value
		public ArrayList<GHOST> ghosts; //The ghost(s) that block this point
		
		public horizon(int safe, int unsafe) {
			safeNode = safe;
			unsafeNode = unsafe;
			jn = -1;
			ghosts = new ArrayList<GHOST>();
			ghosts.addAll(nearestBlocker(unsafe, safe));
		}
	}
	
	private Game 			game;
	private pacmanInfo[]	pacman;	//Distance of pacman from each node - updated each game tick
	private ghostInfo[][]	ghosts; //Distance of each ghost from each node - updated each game tick
	private horizon[] 		eventHorizon; //The nodes where a pacman and ghost meet plus the odds of it being blocked
	private float[]			scores; //The score for each node
	private int[]			bestPath; //The nodes that make up the best path for the pacman to take
	private float			bestScore; //The total score of all nodes on the best path
	
	private static final Random			rnd = new Random(); //Used to break tie breaks in paths with the same score
	
	private static final int	CUTOFF = 350;
	private static final boolean GHOST_DEBUG = false;
	private static final boolean PACMAN_DEBUG = false;
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
		scores = new float[game.getNumberOfNodes()];
		for (int node=0; node<scores.length; node++) {
			scores[node] = -1;
			if (game.getNeighbouringNodes(node).length > 0) {
				ArrayList<GHOST> nearestHunter = nearestBlocker(node, -1);
				if (nearestHunter.size() == 0 || ghosts[ghostIndex(nearestHunter.get(0))][node].distance - pacman[node].distance > EAT_DISTANCE) { //Safe
					scores[node] = 1;
					result++;
					if (PACMAN_DEBUG)
						GameView.addPoints(game, new Color(0, 128, 0), node);
				}
			}
		}
		return result / game.getNumberOfNodes() * 100;
	}
	
	/*
	 * Based on the junctions nearest the event horizon.
	 * Each one is scored according to how likely we are to escape. Any junction that has 2 or more event horizon points next to it from the same ghost is a guaranteed escape
	 * If all junctions have only one ghost blocking them, we pick the one with lowest chance of being blocked
	 */
	private int scoreEscapeRoutes() {
		
		getEventHorizon();
		int escapeScore = 70;
		
		//Count distinct junctions
		class junction {
			int id;
			int[] count;
			float blocked;
			
			public junction(int id, float blocked, ArrayList<GHOST>ghosts) {
				this.id = id;
				this.blocked = blocked;
				this.count = new int[NUM_GHOSTS];
				for (GHOST g: ghosts)
					this.count[ghostIndex(g)]++;
			}
		}
		ArrayList<junction> jns = new ArrayList<junction>();
		for (horizon h: eventHorizon) {
			if (h.jn != -1 && h.jnBlocked < 1) {
				boolean found = false;
				for (junction j: jns)
					if (j.id == h.jn) {
						for (GHOST g: h.ghosts)
							j.count[ghostIndex(g)]++;
						found = true;
						break;
					}
				if (!found)
					jns.add(new junction(h.jn, h.jnBlocked, h.ghosts));
			}
		}
		
		//Look for junctions with more than 1 ghost route to it from 1 ghost and none from the others
		for (junction j: jns) {
			int zeroes = 0;
			boolean multiple = false;
			for (int g=0; g<NUM_GHOSTS; g++)
				if (j.count[g] == 0)
					zeroes++;
				else if (j.count[g] > 1)
					multiple = true;
			if (zeroes == 3 && multiple)
				scores[j.id] += escapeScore*(1f-j.blocked)*1.5;
			else
				scores[j.id] += escapeScore*(1f-j.blocked);
			if (PACMAN_DEBUG) {
				Color y = Color.yellow;
				GameView.addPoints(game, new Color(y.getRed(), y.getGreen(), y.getBlue(), (int)(255*(1f-j.blocked))), j.id);
			}
		}
		
		return eventHorizon.length;
	}
	
	/*
	 * Find the nearest ghost that can block the pacman moving from the given node to the given node
	 */
	private ArrayList<GHOST> nearestBlocker(int to, int from)
	{
		ArrayList<GHOST> result = new ArrayList<GHOST>();
		int nearestHunter = Integer.MAX_VALUE;
		
		MOVE dir = MOVE.NEUTRAL;
		
		if (from != -1)
			for (MOVE d: MOVE.values()) {
				if (getNeighbour(from, d) == to) {
					dir = d;
					break;
				}
			}
		
		//Find nearest Hunting Ghost going in a different direction to the pacman
		for (GHOST g: GHOST.values()) {
			int dist = ghosts[ghostIndex(g)][to].distance;
			if (dir == MOVE.NEUTRAL || ghosts[ghostIndex(g)][to].dir != dir) {
				if (ghosts[ghostIndex(g)][to].hunter && dist < nearestHunter) {
					nearestHunter = dist;
					result.clear();
					result.add(g);
				} else if (ghosts[ghostIndex(g)][to].hunter && dist == nearestHunter) {
					result.add(g);
				}
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
				if (!eat) {
					if (pacman[p].distance - EAT_DISTANCE > 1)
						eat = true;
					else {
						ArrayList<GHOST> g = nearestBlocker(p, -1);
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
	private void getEventHorizon() {
		ArrayList<Integer> safe = new ArrayList<Integer>();
		ArrayList<Integer> unsafe = new ArrayList<Integer>();
		
		for (int n=0; n<game.getNumberOfNodes(); n++) {
			if (scores[n] >= 0) {
				for (int next: game.getNeighbouringNodes(n)) {
					if (scores[next] < 0) {
						boolean isPowerPill = false;
						for (int p: game.getActivePowerPillsIndices())
							if (p == n)
								isPowerPill = true;
						if (!isPowerPill) {
							safe.add(n);
							unsafe.add(next);
						}
						break;
					}
				}
			}
		}
		
		eventHorizon = new horizon[safe.size()];
		for (int i=0; i<safe.size(); i++) {
			eventHorizon[i] = new horizon(safe.get(i), unsafe.get(i));	
		}

		//Work out how much wiggle room we have, this is the time before we are eaten if we stay still
		ArrayList<GHOST> ng = nearestBlocker(game.getPacmanCurrentNodeIndex(), -1);
		int decisionTime = CUTOFF;
		if (ng.size() > 0)
			decisionTime = ghosts[ghostIndex(ng.get(0))][game.getPacmanCurrentNodeIndex()].distance - EAT_DISTANCE - 1;
		
		for (GHOST g: GHOST.values()) {
			ArrayList<ArrayList<Integer>> segments = makeSegments(g, safe, unsafe);
			if (GHOST_DEBUG) {
				for (ArrayList<Integer> segment: segments)
					for (int i=1; i<segment.size(); i++)
						if (game.getNodeXCood(segment.get(i)) != 0 && game.getNodeXCood(segment.get(i-1)) != 0)
							GameView.addLines(game, ghostColour[ghostIndex(g)], segment.get(i), segment.get(i-1));
			}
			//Now we need to score the event horizon points according to how likely a ghost is to block it
			//When a ghost needs to make a decision, we check to see if the pacman needs to make one first, if so the pacman is blocked
			for (int i=0; i<safe.size(); i++) {			
				int count = 0; //how many choices the ghost has to make before the pacman
				
				ArrayList<Integer> choices = findChoices(segments, unsafe.get(i));
				if (choices != null) {
					int jn = nearestJunction(safe.get(i), unsafe.get(i)); //This is the point the pacman must make a decision
					int pacmanChoice = decisionTime;
					if (jn != -1)
						pacmanChoice += pacman[jn].distance;
					
					if (choices.size() > 0) {
						//If the PacMan has to  make a choice before the ghost, then the ghost is guaranteed to be able to block					
						for (int node: choices) {
							if (ghosts[ghostIndex(g)][node].distance < pacmanChoice)
								count++;
						}
					}
					float likelihood = 1f/(float)Math.pow(2, count);
					
					eventHorizon[i].blocked = 1f - (1f-likelihood)*(1f-eventHorizon[i].blocked);
					eventHorizon[i].jn = jn;
					if (jn != -1)
						eventHorizon[i].jnBlocked = 1f - (1f-likelihood)*(1f-eventHorizon[i].jnBlocked);
					else
						eventHorizon[i].jnBlocked = 1f;
				}
			}
		}
		
		if (GHOST_DEBUG) {
			for (int i=0; i<unsafe.size(); i++) {
				for (GHOST g: nearestBlocker(unsafe.get(i), safe.get(i))) {
					Color c = ghostColour[ghostIndex(g)];
					int b = (int)Math.min(5,(Math.log(1f/eventHorizon[i].blocked)/Math.log(2)));
					GameView.addPoints(game, new Color(c.getRed(), c.getGreen(), c.getBlue(), (b==0)?255:180-b*30), unsafe.get(i));
				}
			}
		}
	}
	
	/*
	 * Given a set of path segments, return the nodes at which a ghost has to make a choice
	 * If there is no route to the node, return null
	 */
	private ArrayList<Integer> findChoices(ArrayList<ArrayList<Integer>> segments,  int node) {
		ArrayList<ArrayList<Integer>> path = new ArrayList<ArrayList<Integer>>();
		
		ArrayList<Integer> found = getSegment(segments, node);
		while (found != null) {
			path.add(0, found);
			found = getSegment(segments, found.get(0));
		}
		
		if (path.size() == 0)
			return null;
		
		ArrayList<Integer> result = new ArrayList<Integer>();
		int start = path.get(0).get(0);
		//Count how many segments start here to see if this point is the first decision.
		int count = 0;
		for (ArrayList<Integer> s: path) {
			if ((int)s.get(0) == start)
				count++;
			else
				result.add(s.get(0));
		}
		if (count > 1)
			result.add(0, start);
		
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
	 * Scan backwards from the event horizon and record the path segments that make up the best routes to the end points
	 */
	private ArrayList<ArrayList<Integer>> makeSegments(GHOST g, ArrayList<Integer> safe, ArrayList<Integer> unsafe) {
		ArrayList<ArrayList<Integer>> result = new ArrayList<ArrayList<Integer>>();
		ArrayList<ArrayList<Integer>> active = new ArrayList<ArrayList<Integer>>();
		
		int dist = 0;
		for (int i=0; i<safe.size(); i++) {
			int node = unsafe.get(i);
			//Check to see if this ghost is the nearest blocker to this node
			if (nearestBlocker(node, safe.get(i)).contains(g)) {
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
		ArrayList<ArrayList<Integer>> toDeactivate = new ArrayList<ArrayList<Integer>>();
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
						if (isActive(compare, active, toRemove, toDeactivate) && (int)path.get(0) == (int)compare.get(0)) {
							if (compare.size() == 1)
								toRemove.add(compare);
							else
								toDeactivate.add(compare);
							//Also remove any split segments that end at this end point
							for (int k=0; k<result.size(); k++) {
								ArrayList<Integer> split = result.get(k);
								if (k != j && isActive(split, active, toRemove, toDeactivate) && (int)split.get(split.size()-1) == (int)compare.get(compare.size()-1))
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
							if (k != i && isActive(split, active, toRemove, toDeactivate) && (int)split.get(split.size()-1) == (int)path.get(path.size()-1))
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
	 */
	private ArrayList<Integer> nearestNeighbour(GHOST g, int node, int dist) {
		ArrayList<Integer> result = new ArrayList<Integer>();
		
		if (ghosts[ghostIndex(g)][node].distance == dist+1)
			for (int n: game.getNeighbouringNodes(node)) {
				if (ghosts[ghostIndex(g)][n].distance == dist)
					result.add(n);
			}
		return result;
	}
	
	/*
	 * Find the nearest junction to this node in the safe zone
	 */
	private int nearestJunction(int node, int prev) {	
		while (!game.isJunction(node)) {
			boolean trapped = true;
			for (int next: game.getNeighbouringNodes(node))
				if (scores[next] != -1 && next != prev && scores[next] >= 0) {
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
	 * Hunter ghosts and power pills block the maze walk
	 */
	private boolean isBlocked(int node) {
		for (int pp: game.getActivePowerPillsIndices())
			if (pp == node)
				return true;
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
			if (PACMAN_DEBUG && game.getNodeXCood(bestPath[i]) != 0 && game.getNodeXCood(bestPath[i-1]) != 0)
				GameView.addLines(game, Color.YELLOW, bestPath[i], bestPath[i-1]);

			for (MOVE d: game.getPossibleMoves(bestPath[0]))
				if (getNeighbour(bestPath[0], d) == bestPath[1])
					return d;
		}
		
		return MOVE.NEUTRAL;
	}
}
