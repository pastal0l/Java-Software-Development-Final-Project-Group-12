package world;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * MazeGenerator creates a connected maze map that guarantees a path from the
 * start position to the door exit adjacency.
 */
public class MazeGenerator implements IMapGenerator {
    private static final int WALL = 1;
    private static final int EMPTY = 0;

    @Override
    public int[][] generateMaze(int mapSize, int startX, int startY, int exitAdjX, int exitAdjY) {
        int[][] map = new int[mapSize][mapSize];
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                map[y][x] = WALL;
            }
        }

        Random random = new Random();
        carveMaze(map, startX, startY, random);
        map[startY][startX] = EMPTY;

        openExitConnector(map, exitAdjX, exitAdjY);
        if (!isReachable(map, startX, startY, exitAdjX, exitAdjY)) {
            carveStraightPath(map, startX, startY, exitAdjX, exitAdjY);
        }

        return map;
    }

    private static void carveMaze(int[][] map, int x, int y, Random random) {
        map[y][x] = EMPTY;
        List<int[]> directions = Arrays.asList(
                new int[]{1, 0},
                new int[]{-1, 0},
                new int[]{0, 1},
                new int[]{0, -1}
        );
        Collections.shuffle(directions, random);

        for (int[] dir : directions) {
            int nx = x + dir[0] * 2;
            int ny = y + dir[1] * 2;
            if (inBounds(map, nx, ny) && map[ny][nx] == WALL) {
                map[y + dir[1]][x + dir[0]] = EMPTY;
                carveMaze(map, nx, ny, random);
            }
        }
    }

    private static void openExitConnector(int[][] map, int exitAdjX, int exitAdjY) {
        map[exitAdjY][exitAdjX] = EMPTY;
        if (inBounds(map, exitAdjX, exitAdjY - 1)) {
            map[exitAdjY - 1][exitAdjX] = EMPTY;
        }
        if (inBounds(map, exitAdjX - 1, exitAdjY - 1)) {
            map[exitAdjY - 1][exitAdjX - 1] = EMPTY;
        }
        if (inBounds(map, exitAdjX + 1, exitAdjY - 1)) {
            map[exitAdjY - 1][exitAdjX + 1] = EMPTY;
        }
    }

    private static boolean inBounds(int[][] map, int x, int y) {
        return x > 0 && x < map.length - 1 && y > 0 && y < map.length - 1;
    }

    private static boolean isReachable(int[][] map, int startX, int startY, int targetX, int targetY) {
        int size = map.length;
        boolean[][] visited = new boolean[size][size];
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY});
        visited[startY][startX] = true;

        while (!queue.isEmpty()) {
            int[] pos = queue.removeFirst();
            int x = pos[0];
            int y = pos[1];
            if (x == targetX && y == targetY) {
                return true;
            }
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                if (nx >= 0 && nx < size && ny >= 0 && ny < size && !visited[ny][nx] && map[ny][nx] == EMPTY) {
                    visited[ny][nx] = true;
                    queue.addLast(new int[]{nx, ny});
                }
            }
        }
        return false;
    }

    private static void carveStraightPath(int[][] map, int startX, int startY, int targetX, int targetY) {
        int x = startX;
        int y = startY;
        map[y][x] = EMPTY;

        while (x != targetX) {
            x += Integer.signum(targetX - x);
            map[y][x] = EMPTY;
        }
        while (y != targetY) {
            y += Integer.signum(targetY - y);
            map[y][x] = EMPTY;
        }
    }
}