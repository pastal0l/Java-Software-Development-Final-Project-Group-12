package AI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

public class Pathfinder {

    public static class Node implements Comparable<Node> {
        public int gridX, gridY;
        public int gCost, hCost;
        public Node parent;

        public Node(int gridX, int gridY) {
            this.gridX = gridX;
            this.gridY = gridY;
        }

        public int fCost() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(Node other) {
            int compare = Integer.compare(this.fCost(), other.fCost());
            if (compare == 0) {
                compare = Integer.compare(this.hCost, other.hCost);
            }
            return compare;
        }
    }

    public static List<Node> findPath(int[][] map, int startX, int startY, int targetX, int targetY) {
        int height = map.length;
        int width = map[0].length;

        // If target is out of bounds or inside a wall, return empty path
        if (targetX < 0 || targetX >= width || targetY < 0 || targetY >= height || map[targetY][targetX] > 0) {
            return new ArrayList<>();
        }

        Node[][] allNodes = new Node[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                allNodes[y][x] = new Node(x, y);
            }
        }

        Node startNode = allNodes[startY][startX];
        Node targetNode = allNodes[targetY][targetX];

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        boolean[][] closedSet = new boolean[height][width];

        openSet.add(startNode);

        while (!openSet.isEmpty()) {
            Node currentNode = openSet.poll();
            closedSet[currentNode.gridY][currentNode.gridX] = true;

            // Path found!
            if (currentNode == targetNode) {
                return retracePath(startNode, targetNode);
            }

            // Check 4-way neighbors
            int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
            
            for (int[] dir : directions) {
                int neighborX = currentNode.gridX + dir[0];
                int neighborY = currentNode.gridY + dir[1];

                // Bounds & Wall check
                if (neighborX < 0 || neighborX >= width || neighborY < 0 || neighborY >= height) continue;
                if (map[neighborY][neighborX] > 0 || closedSet[neighborY][neighborX]) continue;

                Node neighbor = allNodes[neighborY][neighborX];
                int newMovementCostToNeighbor = currentNode.gCost + 1; // 1 unit per grid move

                if (newMovementCostToNeighbor < neighbor.gCost || !openSet.contains(neighbor)) {
                    neighbor.gCost = newMovementCostToNeighbor;
                    neighbor.hCost = getManhattanDistance(neighbor, targetNode);
                    neighbor.parent = currentNode;

                    if (!openSet.contains(neighbor)) {
                        openSet.add(neighbor);
                    }
                }
            }
        }

        return new ArrayList<>(); // No path found
    }

    private static int getManhattanDistance(Node nodeA, Node nodeB) {
        int dstX = Math.abs(nodeA.gridX - nodeB.gridX);
        int dstY = Math.abs(nodeA.gridY - nodeB.gridY);
        return dstX + dstY;
    }

    private static List<Node> retracePath(Node startNode, Node endNode) {
        List<Node> path = new ArrayList<>();
        Node currentNode = endNode;

        while (currentNode != startNode) {
            path.add(currentNode);
            currentNode = currentNode.parent;
        }
        Collections.reverse(path);
        return path;
    }
} 
