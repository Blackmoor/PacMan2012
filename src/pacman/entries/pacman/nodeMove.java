package pacman.entries.pacman;

import pacman.game.Constants.GHOST;
import pacman.game.Constants.MOVE;

public class nodeMove {
	int node;
	MOVE move;
	GHOST ghost;
	int turns;
	
	public nodeMove(int n, MOVE m, GHOST g, int t) {
		node = n;
		move = m;
		ghost = g;
		turns = t;
	}
	
	public nodeMove(int n, MOVE m, GHOST g) {
		node = n;
		move = m;
		ghost = g;
		turns = -1;
	}
	
	public nodeMove(int n, MOVE m) {
		node = n;
		move = m;
		turns = -1;
	}
	
	@Override
    public boolean equals (Object x) {
      if (x instanceof nodeMove)
         return ((nodeMove)x).node == node && ((nodeMove)x).move == move && ((nodeMove)x).ghost == ghost;
      return false;
    }

    @Override
    public int hashCode ()
    {
        return node;
    }
}